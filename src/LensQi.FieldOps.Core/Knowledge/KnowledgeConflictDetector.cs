namespace LensQi.FieldOps.Core.Knowledge;

public sealed record KnowledgeConflict(string FactKey, IReadOnlyList<PackFact> Facts)
{
    public string Describe() =>
        $"{KnowledgeReasons.KnowledgeConflict}: factKey '{FactKey}' asserted as " +
        string.Join(" vs ", Facts.Select(f => $"'{f.AssertedValue}' ({f.FactId})"));
}

/// <summary>
/// Deterministic conflict detection (design.md §4, Conflict handling). Two applicable facts with the same
/// factKey but different assertedValue are a conflict. Neither is chosen; the caller surfaces both.
/// Natural-language contradiction detection is out of scope (P2).
/// </summary>
public static class KnowledgeConflictDetector
{
    public static IReadOnlyList<KnowledgeConflict> FindConflicts(IEnumerable<PackFact> applicableFacts) =>
        applicableFacts
            .GroupBy(f => f.FactKey, StringComparer.Ordinal)
            .Where(g => g.Select(f => f.AssertedValue).Distinct(StringComparer.Ordinal).Count() > 1)
            .Select(g => new KnowledgeConflict(g.Key, g.ToList()))
            .ToList();
}
