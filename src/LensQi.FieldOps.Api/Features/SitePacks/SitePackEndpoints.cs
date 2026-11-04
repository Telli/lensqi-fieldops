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
        app.MapGet("/v1/fixtures/{fixtureId}/pack", async (string fixtureId, ExpectedTwinStore twins, InspectionStore store, IReadOnlyList<FixtureKnowledgeBinding> bindings,
            IVendorKnowledgeResolver resolver, IKnowledgePackStore packs, TimeProvider clock, CancellationToken ct) =>
        {
            var twin = twins.Find(fixtureId);
            if (twin is null) return ApiResults.NotFound($"Fixture '{fixtureId}' is not installed");
            var rows = new List<(InspectionEndpoints.InspectionSummary Summary, object? OpenView)>();
            foreach (var session in store.All.Where(s => s.State.FixtureId == twin.Twin.FixtureId))
            {
                var row = await store.ExecuteAsync(session.State.InspectionId, s => Task.FromResult((
                    InspectionEndpoints.Summary(s), InspectionEndpoints.IsOpen(s) ? InspectionEndpoints.View(s) : null)), ct);
                rows.Add(row);
            }
            var newest = rows.OrderByDescending(row => row.Summary.UpdatedAt).ToArray();
            return Results.Ok(new
            {
                fixtureId = twin.Twin.FixtureId, twin.Twin.FixtureRevision, twin.Sha256, generatedAt = clock.GetUtcNow().ToUniversalTime(),
                fixture = InspectionEndpoints.FixtureView(twin),
                assets = twin.Twin.Assets.Select(a => new
                {
                    a.AssetId,
                    knowledge = InspectionKnowledgeEndpoints.KnowledgeView(bindings.FirstOrDefault(b => b.FixtureId == twin.Twin.FixtureId && b.AssetId == a.AssetId), resolver, packs),
                }),
                inspections = newest.Select(row => row.Summary),
                open = newest.Select(row => row.OpenView).OfType<object>(),
            });
        });
        return app;
    }
}
