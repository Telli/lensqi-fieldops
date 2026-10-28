using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Api.Features.VendorKnowledge;

public static class VendorKnowledgeEndpoints
{
    public sealed record PackSummary(string PackId, int PackRevision, string OriginKind, string Sha256,
        string ManufacturerId, string ProductCode, string? FirmwareMajor, int FactCount, int DocumentCount, DateTimeOffset LoadedAt);

    public static IEndpointRouteBuilder MapVendorKnowledge(this IEndpointRouteBuilder app)
    {
        // Enumerates server-installed pack metadata only; never file contents or paths (design.md §8).
        app.MapGet("/api/vendors/knowledge-packs", (IKnowledgePackStore store) =>
            Results.Ok(store.Packs.Select(p => new PackSummary(
                p.Pack.PackId, p.Pack.PackRevision, p.Pack.OriginKind.ToString(), p.Sha256,
                p.Pack.Identity.ManufacturerId, p.Pack.Identity.ProductCode, p.Pack.Identity.FirmwareMajor,
                p.Pack.Facts.Count, p.Pack.Documents.Count, p.LoadedAt))));
        return app;
    }
}
