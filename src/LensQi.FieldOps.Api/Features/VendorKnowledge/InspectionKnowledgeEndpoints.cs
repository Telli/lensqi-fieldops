using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Meta.Knowledge;

namespace LensQi.FieldOps.Api.Features.VendorKnowledge;

/// <summary>Child design §8: resolve applicable facts for an inspection and ask Muse for a bounded, validated proposal.</summary>
public static class InspectionKnowledgeEndpoints
{
    public sealed record ReasonRequest(string Observation);

    public static IEndpointRouteBuilder MapInspectionKnowledge(this IEndpointRouteBuilder app)
    {
        app.MapGet("/v1/inspections/{id}/knowledge", (string id, InspectionStore store, IReadOnlyList<FixtureKnowledgeBinding> bindings, IVendorKnowledgeResolver resolver) =>
        {
            var s = store.Find(id);
            if (s is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            var binding = bindings.FirstOrDefault(b => b.FixtureId == s.State.FixtureId && b.AssetId == s.State.AssetId);
            if (binding is null) return Results.Ok(new { inspectionId = id, status = KnowledgeResolutionStatus.NoPack.ToString(), facts = Array.Empty<object>(), excluded = Array.Empty<object>(), warnings = new[] { "No vendor-knowledge binding for this asset" } });
            var r = resolver.Resolve(binding, binding.ToAssetIdentity(), []);
            return Results.Ok(new
            {
                inspectionId = id, status = r.Status.ToString(), r.PackId, r.PackSha256, r.PackRevision,
                facts = r.Facts.Select(f => new { f.FactId, f.FactKey, f.AssertedValue, f.Statement, claimKind = f.ClaimKind.ToString(), provenance = f.Provenance.ToString(), safety = f.Safety.ToString(), f.Tags,
                    citation = new { f.Citation.DocumentTitle, f.Citation.Publisher, sourceUrl = f.Citation.SourceUrl?.ToString(), f.Citation.SourceLocator, f.Citation.AccessedOn } }),
                excluded = r.Excluded, r.Warnings, approvedDiagnostics = binding.ApprovedDiagnostics,
            });
        });

        app.MapPost("/v1/inspections/{id}/knowledge/reason", async (string id, ReasonRequest req, InspectionStore store, IReadOnlyList<FixtureKnowledgeBinding> bindings, IVendorKnowledgeResolver resolver, GroundedProposalService service, CancellationToken ct) =>
        {
            var s = store.Find(id);
            if (s is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            var binding = bindings.FirstOrDefault(b => b.FixtureId == s.State.FixtureId && b.AssetId == s.State.AssetId);
            if (binding is null) return ApiResults.Conflict(KnowledgeResolutionStatus.NoPack.ToString(), "No vendor-knowledge binding for this asset");
            if (string.IsNullOrWhiteSpace(req.Observation) || req.Observation.Length > 2000) return ApiResults.BadRequest("observation must be 1-2000 characters");
            var resolution = resolver.Resolve(binding, binding.ToAssetIdentity(), []);
            GroundedProposalResult result;
            try { result = await service.ProposeAsync(binding, binding.ToAssetIdentity(), resolution, new Observation(req.Observation), ct); }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or InvalidOperationException) { return ApiResults.Conflict("provider_unavailable", ex.Message); }
            return Results.Ok(new { result.Accepted, result.ModelId, result.PromptDigest, result.ResponseDigest, usage = result.Usage, proposal = result.Proposal, rejections = result.Validation.Rejections, result.ParseError, raw = result.RawResponse });
        });
        return app;
    }
}
