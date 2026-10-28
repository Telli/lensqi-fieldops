namespace LensQi.FieldOps.Core.Knowledge;

public sealed record FactVerdict(PackFact Fact, FactApplicability Verdict, string? Reason);

public sealed record ApplicabilityResult(
    KnowledgeResolutionStatus Status,
    IReadOnlyList<FactVerdict> Verdicts,
    IReadOnlyList<string> Warnings)
{
    public IEnumerable<PackFact> ApplicableFacts =>
        Verdicts.Where(v => v.Verdict == FactApplicability.Applicable).Select(v => v.Fact);
}

/// <summary>
/// Pure applicability function from design.md §4. Exact, case-normalized ordinal comparison only;
/// never fuzzy matching. A near-miss product code is a ModelMismatch, not a suggestion.
/// </summary>
public static class KnowledgeApplicability
{
    public static string Normalize(string? value) => (value ?? string.Empty).Trim().ToUpperInvariant();

    public static bool SameIdentifier(string? a, string? b) =>
        string.Equals(Normalize(a), Normalize(b), StringComparison.Ordinal);

    public static ApplicabilityResult Evaluate(KnowledgePack pack, AssetIdentity asset)
    {
        var warnings = new List<string>();

        if (!SameIdentifier(pack.Identity.ManufacturerId, asset.ManufacturerId))
            return AllExcluded(pack, KnowledgeResolutionStatus.ManufacturerMismatch, FactApplicability.Inapplicable, KnowledgeReasons.ManufacturerMismatch);

        if (!SameIdentifier(pack.Identity.ProductCode, asset.ProductCode))
            return AllExcluded(pack, KnowledgeResolutionStatus.ModelMismatch, FactApplicability.Inapplicable, KnowledgeReasons.ModelMismatch);

        // Empty hardwareRevisions means the pack does not restrict by revision.
        if (pack.Identity.HardwareRevisions.Count > 0)
        {
            if (string.IsNullOrWhiteSpace(asset.HardwareRevision))
                return AllExcluded(pack, KnowledgeResolutionStatus.ApplicabilityUnverified, FactApplicability.Unverified, KnowledgeReasons.HardwareRevisionUnknown);

            if (!pack.Identity.HardwareRevisions.Any(r => SameIdentifier(r, asset.HardwareRevision)))
                return AllExcluded(pack, KnowledgeResolutionStatus.HardwareRevisionMismatch, FactApplicability.Inapplicable, KnowledgeReasons.HardwareRevisionMismatch);
        }

        var firmwareUnknown = string.IsNullOrWhiteSpace(asset.FirmwareMajor);
        var verdicts = new List<FactVerdict>(pack.Facts.Count);
        foreach (var fact in pack.Facts)
        {
            if (fact.RequiresFirmwareMajor is null)
                verdicts.Add(new FactVerdict(fact, FactApplicability.Applicable, null));
            else if (firmwareUnknown)
                verdicts.Add(new FactVerdict(fact, FactApplicability.Unverified, KnowledgeReasons.FirmwareUnknown));
            else if (!SameIdentifier(fact.RequiresFirmwareMajor, asset.FirmwareMajor))
                verdicts.Add(new FactVerdict(fact, FactApplicability.Inapplicable, KnowledgeReasons.FirmwareMismatch));
            else
                verdicts.Add(new FactVerdict(fact, FactApplicability.Applicable, null));
        }

        var applicable = verdicts.Where(v => v.Verdict == FactApplicability.Applicable).Select(v => v.Fact).ToList();
        if (applicable.Count == 0)
        {
            // Unverified facts exist: the honest status is "confirm firmware", not "nothing applies".
            var status = verdicts.Any(v => v.Verdict == FactApplicability.Unverified)
                ? KnowledgeResolutionStatus.ApplicabilityUnverified
                : KnowledgeResolutionStatus.NoApplicableKnowledge;
            return new ApplicabilityResult(status, verdicts, warnings);
        }

        var conflicts = KnowledgeConflictDetector.FindConflicts(applicable);
        if (conflicts.Count > 0)
        {
            warnings.AddRange(conflicts.Select(c => c.Describe()));
            return new ApplicabilityResult(KnowledgeResolutionStatus.KnowledgeConflict, verdicts, warnings);
        }

        if (firmwareUnknown && verdicts.Any(v => v.Verdict == FactApplicability.Unverified))
            warnings.Add("Asset firmware is unknown; firmware-scoped facts were withheld as Unverified.");

        return new ApplicabilityResult(KnowledgeResolutionStatus.Applicable, verdicts, warnings);
    }

    private static ApplicabilityResult AllExcluded(KnowledgePack pack, KnowledgeResolutionStatus status, FactApplicability verdict, string reason) =>
        new(status, pack.Facts.Select(f => new FactVerdict(f, verdict, reason)).ToList(), []);
}
