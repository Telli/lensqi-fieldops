namespace LensQi.FieldOps.Core.Knowledge;

public sealed class KnowledgeResolverOptions
{
    /// <summary>Upper bound on returned fact cards (VK-R03.1). Required facts are always included first.</summary>
    public int MaxFacts { get; set; } = 10;
}

public interface IVendorKnowledgeResolver
{
    /// <summary>
    /// Resolve the applicable fact cards for an asset through its fixture binding. Pure and synchronous:
    /// the pack store is in memory and no network is involved.
    /// </summary>
    KnowledgeResolution Resolve(FixtureKnowledgeBinding binding, AssetIdentity asset, IReadOnlyList<string> issueTags);
}

/// <summary>
/// Bounded, deterministic retrieval (VK-R03). Selects exactly the pack the site binding names, evaluates exact
/// applicability, composes citations from the pack, and returns excluded facts with reasons instead of dropping them.
/// </summary>
public sealed class KnowledgeResolver : IVendorKnowledgeResolver
{
    private readonly IKnowledgePackStore _store;
    private readonly KnowledgeResolverOptions _options;

    public KnowledgeResolver(IKnowledgePackStore store, KnowledgeResolverOptions? options = null)
    {
        _store = store;
        _options = options ?? new KnowledgeResolverOptions();
    }

    public KnowledgeResolution Resolve(FixtureKnowledgeBinding binding, AssetIdentity asset, IReadOnlyList<string> issueTags)
    {
        if (!string.Equals(binding.AssetId, asset.AssetId, StringComparison.Ordinal))
            return KnowledgeResolution.Empty(KnowledgeResolutionStatus.NoPack,
                $"Asset '{asset.AssetId}' is not the asset bound by fixture '{binding.FixtureId}' ('{binding.AssetId}').");

        var loaded = _store.Find(binding.VendorKnowledge.PackId);
        if (loaded is null)
            return KnowledgeResolution.Empty(KnowledgeResolutionStatus.NoPack,
                $"Pack '{binding.VendorKnowledge.PackId}' is not installed.");

        var pack = loaded.Pack;
        var applicability = KnowledgeApplicability.Evaluate(pack, asset);
        var excluded = applicability.Verdicts
            .Where(v => v.Verdict != FactApplicability.Applicable)
            .Select(v => new ExcludedFact(v.Fact.FactId, v.Verdict, v.Reason ?? string.Empty))
            .ToList();
        var warnings = new List<string>(applicability.Warnings);

        if (applicability.Status != KnowledgeResolutionStatus.Applicable)
            return new KnowledgeResolution(applicability.Status, pack.PackId, loaded.Sha256, pack.PackRevision, [], excluded, warnings);

        var required = binding.VendorKnowledge.RequiredKnowledgeFactIds.ToHashSet(StringComparer.Ordinal);
        foreach (var id in required.Where(id => excluded.Any(e => e.FactId == id)))
            warnings.Add($"Required fact '{id}' is not applicable to this asset.");

        var tagSet = issueTags.Select(t => t.Trim().ToLowerInvariant()).Where(t => t.Length > 0).ToHashSet(StringComparer.Ordinal);
        var ranked = applicability.ApplicableFacts
            .Select((f, index) => (Fact: f, Index: index, IsRequired: required.Contains(f.FactId),
                TagHits: tagSet.Count == 0 ? 0 : f.Tags.Count(t => tagSet.Contains(t))))
            .Where(x => x.IsRequired || tagSet.Count == 0 || x.TagHits > 0)
            .OrderByDescending(x => x.IsRequired)
            .ThenByDescending(x => x.TagHits)
            .ThenBy(x => x.Index)
            .Select(x => x.Fact)
            .ToList();

        if (ranked.Count > _options.MaxFacts)
        {
            warnings.Add($"{ranked.Count - _options.MaxFacts} applicable fact(s) omitted by the {_options.MaxFacts}-fact bound.");
            ranked = ranked.Take(_options.MaxFacts).ToList();
        }

        var facts = ranked.Select(f => ToApplicableFact(f, pack, loaded.Sha256)).ToList();
        var status = facts.Count == 0 ? KnowledgeResolutionStatus.NoApplicableKnowledge : KnowledgeResolutionStatus.Applicable;
        return new KnowledgeResolution(status, pack.PackId, loaded.Sha256, pack.PackRevision, facts, excluded, warnings);
    }

    private static ApplicableFact ToApplicableFact(PackFact fact, KnowledgePack pack, string sha256)
    {
        // Invariants guarantee the document exists; a missing one here is a programming error, not model input.
        var doc = pack.FindDocument(fact.SourceDocumentId)
                  ?? throw new InvalidOperationException($"Pack {pack.PackId} fact {fact.FactId} cites unknown document {fact.SourceDocumentId}");
        Uri? url = doc.Url is null ? null : new Uri(doc.Url, UriKind.Absolute);
        var citation = new KnowledgeCitation(fact.FactId, pack.PackId, sha256, doc.DocumentId, doc.Title, doc.Publisher, url, fact.SourceLocator, doc.AccessedOn);
        return new ApplicableFact(fact.FactId, fact.FactKey, fact.AssertedValue, fact.Statement, fact.ClaimKind, fact.Tags, fact.SafetyClass, citation);
    }
}
