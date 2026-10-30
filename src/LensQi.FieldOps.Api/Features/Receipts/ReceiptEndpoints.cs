using System.Text.Json;
using LensQi.FieldOps.Api.Infrastructure.Evidence;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Core.Receipts;
using LensQi.FieldOps.Meta.Vision;

namespace LensQi.FieldOps.Api.Features.Receipts;

/// <summary>Builds a receipt bundle under data/receipts/{inspectionId}/: receipt.json, receipt.html and a copy of every
/// photo artifact. The bundle is what `verify-receipt` checks. Rebuilt on every request from the append-only events.</summary>
public sealed class ReceiptService(InspectionStore store, EvidenceFileStore files, IReadOnlyList<FixtureKnowledgeBinding> bindings, IVendorKnowledgeResolver resolver, TimeProvider clock)
{
    public string BundleRoot => Path.Combine(files.DataDirectoryPath, "receipts");

    public (EvidenceReceipt Receipt, string BundleDir)? Build(string inspectionId)
    {
        var session = store.Find(inspectionId);
        if (session is null) return null;
        var dir = Path.Combine(BundleRoot, inspectionId);
        var artDir = Path.Combine(dir, "artifacts");
        Directory.CreateDirectory(artDir);

        var artifacts = new List<ReceiptArtifact>();
        var srcDir = Path.Combine(files.DataDirectoryPath, "evidence", inspectionId);
        foreach (var photo in session.State.Photos)
        {
            // The digest comes from the photo's event, never from the bytes on disk, and an existing copy is never
            // replaced: an altered or missing file then fails verification instead of being re-certified.
            var src = Directory.Exists(srcDir) ? Directory.EnumerateFiles(srcDir).FirstOrDefault(f => Path.GetFileName(f).StartsWith(photo.EvidenceId + "-", StringComparison.Ordinal)) : null;
            var name = src is not null ? Path.GetFileName(src) : photo.EvidenceId;
            var dst = Path.Combine(artDir, name);
            if (src is not null && !File.Exists(dst)) File.Copy(src, dst);
            var bytes = File.Exists(dst) ? File.ReadAllBytes(dst) : [];
            var media = EvidenceFileStore.DetectImageType(bytes) ?? "application/octet-stream";
            artifacts.Add(new ReceiptArtifact(photo.EvidenceId, "photo", Path.Combine("artifacts", name).Replace('\\', '/'),
                photo.ArtifactSha256, bytes.LongLength, media, photo.Origin.ToString(), photo.ObservedAt));
        }

        ReceiptKnowledge? knowledge = null;
        var binding = bindings.FirstOrDefault(b => b.FixtureId == session.State.FixtureId && b.AssetId == session.State.AssetId);
        if (binding is not null)
            knowledge = ReceiptBuilder.FromResolution(resolver.Resolve(binding, binding.ToAssetIdentity(), []));

        var receipt = ReceiptBuilder.Build(session, artifacts, knowledge, clock.GetUtcNow(), VisualAssessmentService.SchemaVersion);
        File.WriteAllText(Path.Combine(dir, "receipt.json"), JsonSerializer.Serialize(receipt, new JsonSerializerOptions(KnowledgeJson.Options) { WriteIndented = true }));
        File.WriteAllText(Path.Combine(dir, "receipt.html"), ReceiptHtml.Render(receipt));
        return (receipt, dir);
    }

    /// <summary>How many events the issued receipt covers; -1 when there is no readable receipt.</summary>
    public static int IssuedEventCount(string bundleDir)
    {
        try { return System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllBytes(Path.Combine(bundleDir, "receipt.json")))?["events"]?.AsArray().Count ?? -1; }
        catch (Exception ex) when (ex is IOException or JsonException or InvalidOperationException) { return -1; }
    }

    /// <summary>Failures where the bundle's artifact list disagrees with the photos the event log recorded.</summary>
    public static IEnumerable<string> AgainstRecord(string bundleDir, IEnumerable<PhotoEvidence> photos)
    {
        Dictionary<string, string?> listed;
        try
        {
            listed = (System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllBytes(Path.Combine(bundleDir, "receipt.json")))?["artifacts"]?.AsArray() ?? [])
                .Where(a => a?["evidenceId"] is not null)
                .ToDictionary(a => a!["evidenceId"]!.GetValue<string>(), a => a!["sha256"]?.GetValue<string>(), StringComparer.Ordinal);
        }
        catch (Exception ex) when (ex is IOException or JsonException or InvalidOperationException or ArgumentException) { yield break; }
        foreach (var photo in photos)
        {
            if (!listed.TryGetValue(photo.EvidenceId, out var sha)) yield return $"artifact {photo.EvidenceId}: recorded in the inspection but missing from the receipt";
            else if (!string.Equals(sha, photo.ArtifactSha256, StringComparison.Ordinal)) yield return $"artifact {photo.EvidenceId}: receipt digest differs from the inspection record";
        }
    }
}

public static class ReceiptEndpoints
{
    public static IEndpointRouteBuilder MapReceipts(this IEndpointRouteBuilder app)
    {
        app.MapGet("/v1/inspections/{id}/receipt", (string id, ReceiptService svc) =>
            svc.Build(id) is { } b ? Results.Json(b.Receipt, new JsonSerializerOptions(KnowledgeJson.Options)) : ApiResults.NotFound($"Inspection '{id}' not found"));

        app.MapGet("/v1/inspections/{id}/receipt.html", (string id, ReceiptService svc) =>
            svc.Build(id) is { } b ? Results.Content(ReceiptHtml.Render(b.Receipt), "text/html; charset=utf-8") : ApiResults.NotFound($"Inspection '{id}' not found"));

        // Verifies the issued bundle on disk the same way the CLI does, and checks every artifact digest against the photo
        // recorded in the event log. A bundle is (re)issued only when none exists or the log has grown since.
        app.MapGet("/v1/inspections/{id}/receipt/verify", (string id, ReceiptService svc, InspectionStore store) =>
        {
            if (store.Find(id) is not { } session) return ApiResults.NotFound($"Inspection '{id}' not found");
            var dir = Path.Combine(svc.BundleRoot, id);
            if (ReceiptService.IssuedEventCount(dir) < session.Events.Count) svc.Build(id);
            var v = ReceiptVerifier.Verify(dir);
            var failures = v.Failures.Concat(ReceiptService.AgainstRecord(dir, session.State.Photos)).ToList();
            return Results.Ok(new { bundle = dir, isValid = failures.Count == 0, v.Checks, failures });
        });
        return app;
    }
}
