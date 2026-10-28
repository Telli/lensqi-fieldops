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
            var src = Directory.Exists(srcDir) ? Directory.EnumerateFiles(srcDir).FirstOrDefault(f => Path.GetFileName(f).StartsWith(photo.EvidenceId + "-", StringComparison.Ordinal)) : null;
            if (src is null) continue;
            var name = Path.GetFileName(src);
            var dst = Path.Combine(artDir, name);
            if (!File.Exists(dst)) File.Copy(src, dst);
            var bytes = File.ReadAllBytes(dst);
            var media = EvidenceFileStore.DetectImageType(bytes) ?? "application/octet-stream";
            artifacts.Add(new ReceiptArtifact(photo.EvidenceId, "photo", Path.Combine("artifacts", name).Replace('\\', '/'),
                Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(bytes)), bytes.LongLength, media, photo.Origin.ToString(), photo.ObservedAt));
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
}

public static class ReceiptEndpoints
{
    public static IEndpointRouteBuilder MapReceipts(this IEndpointRouteBuilder app)
    {
        app.MapGet("/v1/inspections/{id}/receipt", (string id, ReceiptService svc) =>
            svc.Build(id) is { } b ? Results.Json(b.Receipt, new JsonSerializerOptions(KnowledgeJson.Options)) : ApiResults.NotFound($"Inspection '{id}' not found"));

        app.MapGet("/v1/inspections/{id}/receipt.html", (string id, ReceiptService svc) =>
            svc.Build(id) is { } b ? Results.Content(ReceiptHtml.Render(b.Receipt), "text/html; charset=utf-8") : ApiResults.NotFound($"Inspection '{id}' not found"));

        // Re-verifies the bundle on disk the same way the CLI does; useful for the demo and for judges without .NET tooling handy.
        app.MapGet("/v1/inspections/{id}/receipt/verify", (string id, ReceiptService svc) =>
        {
            var built = svc.Build(id);
            if (built is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            var v = ReceiptVerifier.Verify(built.Value.BundleDir);
            return Results.Ok(new { bundle = built.Value.BundleDir, v.IsValid, v.Checks, v.Failures });
        });
        return app;
    }
}
