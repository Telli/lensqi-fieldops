using System.Text.Json;
using LensQi.FieldOps.Api.Infrastructure.Evidence;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Core.Receipts;
using LensQi.FieldOps.Meta.Vision;

namespace LensQi.FieldOps.Api.Features.Receipts;

/// <summary>One issued receipt: the manifest the server stands behind, how many events it covers and when it was issued.</summary>
public sealed record ReceiptIssue(string ManifestSha256, int EventCount, DateTimeOffset IssuedAt, string ReceiptVersion);

/// <summary>
/// Issues receipt bundles under data/receipts/{inspectionId}/ (receipt.json, receipt.html and a copy of every photo) and
/// keeps a server-owned issuance record beside them in {inspectionId}.issued.jsonl. A new version is issued only when none
/// exists, the event log has grown or the receipt format changed; otherwise the issued receipt is rebuilt from the log in
/// memory with its original issue time, and the bundle on disk is never rewritten. Checking a bundle therefore compares
/// it with what was issued and never repairs it.
/// </summary>
public sealed class ReceiptService(InspectionStore store, EvidenceFileStore files, IReadOnlyList<FixtureKnowledgeBinding> bindings, IVendorKnowledgeResolver resolver, TimeProvider clock)
{
    private readonly Lock _issuing = new();

    public string BundleRoot => Path.Combine(files.DataDirectoryPath, "receipts");

    private string IssueLog(string inspectionId) => Path.Combine(BundleRoot, inspectionId + ".issued.jsonl");

    /// <summary>The receipt the server currently stands behind, issuing a new version first when the log has moved on.</summary>
    public (EvidenceReceipt Receipt, string BundleDir, ReceiptIssue Issue)? Current(string inspectionId)
    {
        var session = store.Find(inspectionId);
        if (session is null) return null;
        var dir = Path.Combine(BundleRoot, inspectionId);
        lock (_issuing)
        {
            if (LastIssue(inspectionId) is { } last && last.EventCount == session.Events.Count && last.ReceiptVersion == EvidenceReceipt.Version
                && Compose(session, last.IssuedAt) is { } again && again.ManifestSha256 == last.ManifestSha256)
                return (again, dir, last);
            return Issue(session, dir);
        }
    }

    public ReceiptIssue? LastIssue(string inspectionId)
    {
        var path = IssueLog(inspectionId);
        if (!File.Exists(path)) return null;
        var line = File.ReadLines(path).LastOrDefault(l => !string.IsNullOrWhiteSpace(l));
        return line is null ? null : JsonSerializer.Deserialize<ReceiptIssue>(line, KnowledgeJson.Options);
    }

    private (EvidenceReceipt, string, ReceiptIssue) Issue(InspectionSession session, string dir)
    {
        var id = session.State.InspectionId;
        var receipt = Compose(session, clock.GetUtcNow());
        var artDir = Path.Combine(dir, "artifacts");
        Directory.CreateDirectory(artDir);
        // An existing copy is never replaced: an altered or missing file then fails verification instead of being re-certified.
        foreach (var a in receipt.Artifacts)
            if (Original(id, a.EvidenceId, a.Kind) is { } src && Path.Combine(dir, a.Path) is var dst && !File.Exists(dst)) File.Copy(src, dst);
        File.WriteAllText(Path.Combine(dir, "receipt.json"), JsonSerializer.Serialize(receipt, new JsonSerializerOptions(KnowledgeJson.Options) { WriteIndented = true }));
        File.WriteAllText(Path.Combine(dir, "receipt.html"), ReceiptHtml.Render(receipt));
        var issue = new ReceiptIssue(receipt.ManifestSha256!, session.Events.Count, receipt.GeneratedAt, receipt.ReceiptVersion);
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

    /// <summary>Every file the receipt must carry, with the digest the event log recorded for it. Segmentations recorded
    /// before their geometry digest was kept are listed in the events only.</summary>
    public static IEnumerable<(string EvidenceId, string Kind, string Sha256, string Origin, DateTimeOffset At)> Expected(InspectionSession session) =>
        session.State.Photos.Select(p => (p.EvidenceId, "photo", p.ArtifactSha256, p.Origin.ToString(), p.ObservedAt))
            .Concat(session.Events.OfType<SegmentationRecorded>().Where(s => s.GeometrySha256 is not null)
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

        // Checks the bundle on disk the way the CLI does, against the photos in the event log, and against the issuance
        // record: a bundle whose manifest is not the one the server issued fails, even when it is internally consistent.
        // Nothing here rewrites the bundle; a new version is issued only when the log has grown.
        app.MapGet("/v1/inspections/{id}/receipt/verify", (string id, ReceiptService svc, InspectionStore store) =>
        {
            if (store.Find(id) is not { } session || svc.Current(id) is not { } current) return ApiResults.NotFound($"Inspection '{id}' not found");
            var v = ReceiptVerifier.Verify(current.BundleDir);
            var failures = v.Failures.Concat(ReceiptService.AgainstRecord(current.BundleDir, ReceiptService.Expected(session))).ToList();
            var checks = v.Checks.ToList();
            var onDisk = ReceiptService.BundleManifest(current.BundleDir);
            if (onDisk == current.Issue.ManifestSha256) checks.Add($"manifest is the receipt issued at {current.Issue.IssuedAt:u}");
            else failures.Add($"receipt.json is not the receipt issued at {current.Issue.IssuedAt:u} (issued manifest {current.Issue.ManifestSha256[..12]}…)");
            return Results.Ok(new { bundle = current.BundleDir, isValid = failures.Count == 0, checks, failures, issued = current.Issue });
        });
        return app;
    }
}
