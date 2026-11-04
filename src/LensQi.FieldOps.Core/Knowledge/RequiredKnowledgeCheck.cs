using System.Text.Json.Serialization;

namespace LensQi.FieldOps.Core.Knowledge;

/// <summary>
/// What the site binding requires of vendor documentation, resolved for the bound asset when a decision is made (VK-R08,
/// VK-T10). Recorded with the decision, so it states which pack, digest and revision the decision rested on.
/// </summary>
public sealed record RequiredKnowledgeCheck(
    KnowledgeResolutionStatus Status,
    string? PackId,
    string? PackSha256,
    int? PackRevision,
    IReadOnlyList<string> RequiredFactIds,
    IReadOnlyList<string> UnconfirmedFactIds)
{
    [JsonIgnore] public bool Confirmed => UnconfirmedFactIds.Count == 0;

    /// <summary>
    /// Null when no binding covers this asset or its binding requires nothing. A required fact the resolver did not return as
    /// applicable, for whatever reason, is unconfirmed.
    /// </summary>
    public static RequiredKnowledgeCheck? For(IEnumerable<FixtureKnowledgeBinding> bindings, IVendorKnowledgeResolver resolver, string fixtureId, string assetId)
    {
        var binding = bindings.FirstOrDefault(b => b.FixtureId == fixtureId && b.AssetId == assetId);
        if (binding is null || binding.VendorKnowledge.RequiredKnowledgeFactIds.Count == 0) return null;
        var required = binding.VendorKnowledge.RequiredKnowledgeFactIds;
        var resolution = resolver.Resolve(binding, binding.ToAssetIdentity(), []);
        var applicable = (resolution.ApplicableFactIds ?? []).ToHashSet(StringComparer.Ordinal);
        return new(resolution.Status, resolution.PackId, resolution.PackSha256, resolution.PackRevision, required, required.Where(id => !applicable.Contains(id)).ToList());
    }
}
