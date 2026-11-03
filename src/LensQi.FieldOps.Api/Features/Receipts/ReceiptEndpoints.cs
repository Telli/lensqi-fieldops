using System.Text.Json;
using LensQi.FieldOps.Api.Infrastructure.Evidence;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Core.Receipts;
using LensQi.FieldOps.Meta.Vision;

namespace LensQi.FieldOps.Api.Features.Receipts;

/// <summary>One issued receipt: the manifest the server stands behind, how many events it covers, when it was issued and
/// its number. Number 0 marks a receipt issued before versions were kept, whose bundle is the inspection's folder itself.</summary>
public sealed record ReceiptIssue(string ManifestSha256, int EventCount, DateTimeOffset IssuedAt, string ReceiptVersion, int Number = 0);

/// <summary>
/// Issues numbered receipt bundles under data/receipts/{inspectionId}/v{n}/ (receipt.json, receipt.html and a copy of every
/// photo and mask) and keeps a server-owned issuance record beside them in {inspectionId}.issued.jsonl. A new version is
/// issued only when none exists, the event log has grown or the receipt format changed; otherwise the issued receipt is
/// rebuilt from the log in memory with its original issue time. No bundle is ever rewritten, and every earlier version
/// stays on disk and can still be checked against the events it covered. Checking a bundle compares it with what was
/// issued and never repairs it.
/// </summary>
public sealed class ReceiptService(InspectionStore store, EvidenceFileStore files, IReadOnlyList<FixtureKnowledgeBinding> bindings, IVendorKnowledgeResolver resolver, TimeProvider clock)
{
    private readonly Lock _issuing = new();

    public (string Directory, string[] Paths, int Number) VerifiedExport(string id)
    {
        if (Current(id) is not { } bundle || store.Find(id) is not { } session)
            throw new InvalidDataException("Linked inspection is unavailable");
        if (!ReceiptVerifier.Verify(bundle.BundleDir).IsValid
                || BundleManifest(bundle.BundleDir) != bundle.Issue.ManifestSha256
                || AgainstRecord(bundle.BundleDir, Expected(session.Events.Take(bundle.Issue.EventCount))).Any()
                // The digest check alone passes an edited page that keeps the digest string; it must be the issued rendering.
                || !File.Exists(Path.Combine(bundle.BundleDir, "receipt.html"))
                || !ReceiptHtml.IsRenderingOf(File.ReadAllText(Path.Combine(bundle.BundleDir, "receipt.html")), bundle.Receipt))
            throw new InvalidDataException("The issued record failed its integrity check");
        return (bundle.BundleDir, new[] { "receipt.json", "receipt.html" }.Concat(bundle.Receipt.Artifacts.Select(a => a.Path)).Distinct().ToArray(), bundle.Issue.Number);
    }

    public string BundleRoot => Path.Combine(files.DataDirectoryPath, "receipts");

    private string IssueLog(string inspectionId) => Path.Combine(BundleRoot, inspectionId + ".issued.jsonl");

    /// <summary>The folder holding this issued version.</summary>
    public string BundleDir(string inspectionId, ReceiptIssue issue) =>
        issue.Number == 0 ? Path.Combine(BundleRoot, inspectionId) : Path.Combine(BundleRoot, inspectionId, $"v{issue.Number}");

    /// <summary>The receipt the server currently stands behind, issuing a new version first when the log has moved on.</summary>
    public (EvidenceReceipt Receipt, string BundleDir, ReceiptIssue Issue)? Current(string inspectionId)
    {
        var session = store.Find(inspectionId);
        if (session is null) return null;
        lock (_issuing)
        {
            var issued = Issues(inspectionId);
            if (issued.LastOrDefault() is { } last && last.EventCount == session.Events.Count && last.ReceiptVersion == EvidenceReceipt.Version
                && Compose(session, last.IssuedAt) is { } again && again.ManifestSha256 == last.ManifestSha256)
                return (again, BundleDir(inspectionId, last), last);
            return Issue(session, (issued.LastOrDefault()?.Number ?? 0) + 1);
        }
    }

    /// <summary>Every receipt version still on disk, oldest first. Before versions were kept each reissue overwrote the
    /// inspection's folder and added a line without a number, so of those lines only the last describes that folder.</summary>
    public IReadOnlyList<ReceiptIssue> Issues(string inspectionId)
    {
        var path = IssueLog(inspectionId);
        if (!File.Exists(path)) return [];
        var all = File.ReadLines(path).Where(l => !string.IsNullOrWhiteSpace(l))
            .Select(l => JsonSerializer.Deserialize<ReceiptIssue>(l, KnowledgeJson.Options)!).ToList();
        var legacy = all.LastOrDefault(i => i.Number == 0);
        return all.Where(i => i.Number != 0 || ReferenceEquals(i, legacy)).ToList();
    }

    private (EvidenceReceipt, string, ReceiptIssue) Issue(InspectionSession session, int number)
    {
        var id = session.State.InspectionId;
        var receipt = Compose(session, clock.GetUtcNow());
        var issue = new ReceiptIssue(receipt.ManifestSha256!, session.Events.Count, receipt.GeneratedAt, receipt.ReceiptVersion, number);
        var dir = BundleDir(id, issue);
        var artDir = Path.Combine(dir, "artifacts");
        Directory.CreateDirectory(artDir);
        // An existing copy is never replaced: an altered or missing file then fails verification instead of being re-certified.
        foreach (var a in receipt.Artifacts)
            if (Original(id, a.EvidenceId, a.Kind) is { } src && Path.Combine(dir, a.Path) is var dst && !File.Exists(dst)) File.Copy(src, dst);
        File.WriteAllText(Path.Combine(dir, "receipt.json"), JsonSerializer.Serialize(receipt, new JsonSerializerOptions(KnowledgeJson.Options) { WriteIndented = true }));
        File.WriteAllText(Path.Combine(dir, "receipt.html"), ReceiptHtml.Render(receipt));
        File.AppendAllText(IssueLog(id), JsonSerializer.Serialize(issue, KnowledgeJson.Options) + "\n");
        return (receipt, dir, issue);
    }

    /// <summary>The receipt for this log at this issue time. Digests come from the events (photos and the SAM geometry
    /// files with their masks) and sizes from the stored originals, never from the bundle copies, so the same log and issue
    /// time always give the same manifest.</summary>
    private EvidenceReceipt Compose(InspectionSession session, DateTimeOffset issuedAt)
    {
        var id = session.State.InspectionId;
        ReceiptArtifact Artifact(string evidenceId, string kind, string sha256, string origin, DateTimeOffset observedAt)
        {
            var src = Original(id, evidenceId, kind);
            var name = src is not null ? Path.GetFileName(src) : evidenceId;
            var bytes = src is not null ? File.ReadAllBytes(src) : [];
            var media = kind == "segmentation" ? "application/json" : EvidenceFileStore.DetectImageType(bytes) ?? "application/octet-stream";
            return new ReceiptArtifact(evidenceId, kind, "artifacts/" + name, sha256, bytes.LongLength, media, origin, observedAt);
        }
        var artifacts = Expected(session).Select(e => Artifact(e.EvidenceId, e.Kind, e.Sha256, e.Origin, e.At)).ToList();

        ReceiptKnowledge? knowledge = null;
        var binding = bindings.FirstOrDefault(b => b.FixtureId == session.State.FixtureId && b.AssetId == session.State.AssetId);
        if (binding is not null)
            knowledge = ReceiptBuilder.FromResolution(resolver.Resolve(binding, binding.ToAssetIdentity(), []));
        return ReceiptBuilder.Build(session, artifacts, knowledge, issuedAt, VisualAssessmentService.SchemaVersion);
    }

    public static IEnumerable<(string EvidenceId, string Kind, string Sha256, string Origin, DateTimeOffset At)> Expected(InspectionSession session) =>
        Expected(session.Events);

    /// <summary>Every file a receipt covering these events must carry, with the digest the event log recorded for it.
    /// Segmentations recorded before their geometry digest was kept are listed in the events only.</summary>
    public static IEnumerable<(string EvidenceId, string Kind, string Sha256, string Origin, DateTimeOffset At)> Expected(IEnumerable<InspectionEvent> events) =>
        events.OfType<PhotoRecorded>().Select(p => (p.EvidenceId, "photo", p.ArtifactSha256, p.Origin.ToString(), p.ObservedAt))
            .Concat(events.OfType<PhotoRecorded>().Where(p => p.OriginalSha256 is not null)
                .Select(p => ("original-" + p.EvidenceId, "original", p.OriginalSha256!, p.Origin.ToString(), p.ObservedAt)))
            .Concat(events.OfType<SegmentationRecorded>().Where(s => s.GeometrySha256 is not null)
                .Select(s => (s.EvidenceId, "segmentation", s.GeometrySha256!, "Sam", s.OccurredAt)));

    private string? Original(string inspectionId, string evidenceId, string kind)
    {
        var srcDir = Path.Combine(files.DataDirectoryPath, "evidence", inspectionId);
        if (!Directory.Exists(srcDir)) return null;
        if (kind == "segmentation") return Path.Combine(srcDir, evidenceId + ".json") is var json && File.Exists(json) ? json : null;
        return Directory.EnumerateFiles(srcDir).FirstOrDefault(f => Path.GetFileName(f).StartsWith(evidenceId + "-", StringComparison.Ordinal));
    }

    /// <summary>The manifest digest written in the bundle's receipt.json, or null when it cannot be read.</summary>
    public static string? BundleManifest(string bundleDir)
    {
        try { return System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllBytes(Path.Combine(bundleDir, "receipt.json")))?["manifestSha256"]?.GetValue<string>(); }
        catch (Exception ex) when (ex is IOException or JsonException or InvalidOperationException) { return null; }
    }

    /// <summary>Failures where the bundle's artifact list disagrees with the files the event log recorded.</summary>
    public static IEnumerable<string> AgainstRecord(string bundleDir, IEnumerable<(string EvidenceId, string Kind, string Sha256, string Origin, DateTimeOffset At)> expected)
    {
        Dictionary<string, string?> listed;
        try
        {
            listed = (System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllBytes(Path.Combine(bundleDir, "receipt.json")))?["artifacts"]?.AsArray() ?? [])
                .Where(a => a?["evidenceId"] is not null)
                .ToDictionary(a => a!["evidenceId"]!.GetValue<string>(), a => a!["sha256"]?.GetValue<string>(), StringComparer.Ordinal);
        }
        catch (Exception ex) when (ex is IOException or JsonException or InvalidOperationException or ArgumentException) { yield break; }
        foreach (var e in expected)
        {
            if (!listed.TryGetValue(e.EvidenceId, out var sha)) yield return $"artifact {e.EvidenceId}: recorded in the inspection but missing from the receipt";
            else if (!string.Equals(sha, e.Sha256, StringComparison.Ordinal)) yield return $"artifact {e.EvidenceId}: receipt digest differs from the inspection record";
        }
    }
}

public static class ReceiptEndpoints
{
    public static IEndpointRouteBuilder MapReceipts(this IEndpointRouteBuilder app)
    {
        app.MapGet("/v1/inspections/{id}/receipt", (string id, ReceiptService svc) =>
            svc.Current(id) is { } b ? Results.Json(b.Receipt, new JsonSerializerOptions(KnowledgeJson.Options)) : ApiResults.NotFound($"Inspection '{id}' not found"));

        app.MapGet("/v1/inspections/{id}/receipt.html", (string id, ReceiptService svc) =>
            svc.Current(id) is { } b ? Results.Content(ReceiptHtml.Render(b.Receipt), "text/html; charset=utf-8") : ApiResults.NotFound($"Inspection '{id}' not found"));

        // Export the issued snapshot, including original evidence; never silently repair a damaged bundle.
        app.MapGet("/v1/inspections/{id}/receipt.zip", (string id, ReceiptService svc, InspectionStore store) =>
        {
            if (store.Find(id) is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            (string Directory, string[] Paths, int Number) export;
            try { export = svc.VerifiedExport(id); }
            catch (InvalidDataException ex) { return ApiResults.Conflict("receipt_invalid", ex.Message); }
            var paths = export.Paths;
            return Results.Stream(async output =>
            {
                // Zip finalisation can write synchronously. Spool to a disposable file instead of buffering all
                // evidence in RAM or enabling synchronous writes on the HTTP response.
                await using var spool = new FileStream(Path.Combine(Path.GetTempPath(), "fieldproof-" + Guid.NewGuid().ToString("N") + ".zip"),
                    FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None, 65536, FileOptions.DeleteOnClose | FileOptions.Asynchronous);
                using (var zip = new System.IO.Compression.ZipArchive(spool, System.IO.Compression.ZipArchiveMode.Create, leaveOpen: true))
                {
                    foreach (var path in paths)
                    {
                        var entry = zip.CreateEntry(path.Replace('\\', '/'));
                        using var target = entry.Open();
                        await using var source = File.OpenRead(Path.Combine(export.Directory, path));
                        await source.CopyToAsync(target);
                    }
                }
                spool.Position = 0;
                await spool.CopyToAsync(output);
            }, "application/zip", $"fieldproof-{id}-v{export.Number}.zip");
        });

        app.MapGet("/v1/inspections/{id}/receipts", (string id, ReceiptService svc, InspectionStore store) =>
            store.Find(id) is null ? ApiResults.NotFound($"Inspection '{id}' not found") : Results.Ok(new { versions = svc.Issues(id) }));

        // Checks a bundle on disk the way the CLI does, against the files the event log recorded up to that version, and
        // against the issuance record: a bundle whose manifest is not the one the server issued fails, even when it is
        // internally consistent. Without ?number= it checks the current version, issuing one first if the log has grown;
        // with it, an earlier version, which is never reissued. Nothing here rewrites a bundle.
        app.MapGet("/v1/inspections/{id}/receipt/verify", (string id, int? number, ReceiptService svc, InspectionStore store) =>
        {
            if (store.Find(id) is not { } session) return ApiResults.NotFound($"Inspection '{id}' not found");
            ReceiptIssue issue;
            if (number is { } n)
            {
                if (svc.Issues(id).FirstOrDefault(i => i.Number == n) is not { } found) return ApiResults.NotFound($"Receipt version {n} of inspection '{id}' not found");
                issue = found;
            }
            else if (svc.Current(id) is { } current) issue = current.Issue;
            else return ApiResults.NotFound($"Inspection '{id}' not found");

            var bundle = svc.BundleDir(id, issue);
            var v = ReceiptVerifier.Verify(bundle);
            var failures = v.Failures.Concat(ReceiptService.AgainstRecord(bundle, ReceiptService.Expected(session.Events.Take(issue.EventCount)))).ToList();
            var checks = v.Checks.ToList();
            if (ReceiptService.BundleManifest(bundle) == issue.ManifestSha256) checks.Add($"manifest is the receipt issued at {issue.IssuedAt:u}");
            else failures.Add($"receipt.json is not the receipt issued at {issue.IssuedAt:u} (issued manifest {issue.ManifestSha256[..12]}…)");
            return Results.Ok(new { bundle, isValid = failures.Count == 0, checks, failures, issued = issue });
        });
        return app;
    }
}
