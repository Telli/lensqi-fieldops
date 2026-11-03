using LensQi.FieldOps.Api.Features.Inspections;
using LensQi.FieldOps.Api.Features.VendorKnowledge;
using LensQi.FieldOps.Api.Infrastructure.Fixtures;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Api.Features.SitePacks;

/// <summary>
/// Everything a technician needs on the phone before going somewhere without signal: the fixture as GET /v1/fixtures lists
/// it, every asset's vendor knowledge as GET /v1/inspections/{id}/knowledge would resolve it, and this fixture's
/// inspections (summaries of all, full views of the open ones). Read-only and derived from installed files and event logs;
/// downloading a pack records nothing.
/// </summary>
public static class SitePackEndpoints
{
    public static IEndpointRouteBuilder MapSitePacks(this IEndpointRouteBuilder app)
    {
        app.MapGet("/v1/fixtures/{fixtureId}/pack", (string fixtureId, ExpectedTwinStore twins, InspectionStore store, IReadOnlyList<FixtureKnowledgeBinding> bindings,
            IVendorKnowledgeResolver resolver, IKnowledgePackStore packs, TimeProvider clock) =>
        {
            var twin = twins.Find(fixtureId);
            if (twin is null) return ApiResults.NotFound($"Fixture '{fixtureId}' is not installed");
            var sessions = store.All.Where(s => s.State.FixtureId == twin.Twin.FixtureId).OrderByDescending(s => s.Events[^1].OccurredAt).ToList();
            return Results.Ok(new
            {
                fixtureId = twin.Twin.FixtureId, twin.Twin.FixtureRevision, twin.Sha256, generatedAt = clock.GetUtcNow().ToUniversalTime(),
                fixture = InspectionEndpoints.FixtureView(twin),
                assets = twin.Twin.Assets.Select(a => new
                {
                    a.AssetId,
                    knowledge = InspectionKnowledgeEndpoints.KnowledgeView(bindings.FirstOrDefault(b => b.FixtureId == twin.Twin.FixtureId && b.AssetId == a.AssetId), resolver, packs),
                }),
                inspections = sessions.Select(InspectionEndpoints.Summary),
                open = sessions.Where(InspectionEndpoints.IsOpen).Select(InspectionEndpoints.View),
            });
        });
        return app;
    }
}
