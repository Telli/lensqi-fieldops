namespace LensQi.FieldOps.Core.Inspection;

// Typed mirror of fixtures/telecom-lab/expected-twin.json. The JSON Schema is validated in the Api loader;
// ExpectedTwinInvariants mirrors scripts/validate_expected_twin.py.

public sealed record ExpectedTwin(
    string SchemaVersion,
    string FixtureId,
    int FixtureRevision,
    string? FixtureDescription,
    IReadOnlyList<TwinAsset> Assets,
    IReadOnlyList<TwinConnection> Connections,
    IReadOnlyList<TwinDiagnostic> Diagnostics,
    IReadOnlyList<TwinVisualRequirement> VisualRequirements,
    TwinVerificationPolicy VerificationPolicy)
{
    public TwinAsset? FindAsset(string assetId) => Assets.FirstOrDefault(a => a.AssetId == assetId);
    public TwinDiagnostic? FindDiagnostic(string diagnosticId) => Diagnostics.FirstOrDefault(d => d.DiagnosticId == diagnosticId);
    public IEnumerable<TwinVisualRequirement> MandatoryRequirements => VisualRequirements.Where(v => v.Mandatory);
}

public sealed record TwinAsset(string AssetId, string Kind, string DisplayName, IReadOnlyList<string>? VisualLabels);

public sealed record TwinConnection(string ConnectionId, string FromAssetId, string? FromPortLabel, string ToAssetId, string CableLabel, string ExpectedState);

public sealed record TwinDiagnostic(string DiagnosticId, string Type, string TargetAlias, int ExpectedHttpStatus, int TimeoutMs, string? ClaimVerified);

public sealed record TwinVisualRequirement(string RequirementId, bool Mandatory, string? SegmentationPrompt = null);

public sealed record TwinVerificationPolicy(
    bool RequirePostRepairPhoto,
    bool RequireFreshDiagnostic,
    bool RequireTechnicianAcknowledgement,
    bool RequireHumanReviewedVisualChecklist,
    int DiagnosticMaxAgeSeconds,
    IReadOnlyList<DiagnosticSource> AllowedDiagnosticSources,
    DiagnosticSource VerifiedLiveRequiresSource);

/// <summary>A twin as installed: parsed document plus the SHA-256 of the exact file bytes (PRD FR-01).</summary>
public sealed record LoadedExpectedTwin(ExpectedTwin Twin, string Sha256, string SourceName);

public static class ExpectedTwinInvariants
{
    public static IReadOnlyList<string> Check(ExpectedTwin twin)
    {
        var errors = new List<string>();
        void Unique(IEnumerable<string> ids, string label)
        {
            foreach (var d in ids.GroupBy(i => i, StringComparer.Ordinal).Where(g => g.Count() > 1))
                errors.Add($"Duplicate {label} '{d.Key}'");
        }
        Unique(twin.Assets.Select(a => a.AssetId), "assetId");
        Unique(twin.Connections.Select(c => c.ConnectionId), "connectionId");
        Unique(twin.Diagnostics.Select(d => d.DiagnosticId), "diagnosticId");
        Unique(twin.Diagnostics.Select(d => d.TargetAlias), "targetAlias");
        Unique(twin.VisualRequirements.Select(v => v.RequirementId), "requirementId");

        var known = twin.Assets.Select(a => a.AssetId).ToHashSet(StringComparer.Ordinal);
        foreach (var c in twin.Connections)
        {
            if (!known.Contains(c.FromAssetId)) errors.Add($"Connection {c.ConnectionId} fromAssetId '{c.FromAssetId}' is not a declared asset");
            if (!known.Contains(c.ToAssetId)) errors.Add($"Connection {c.ConnectionId} toAssetId '{c.ToAssetId}' is not a declared asset");
            if (c.FromAssetId == c.ToAssetId) errors.Add($"Connection {c.ConnectionId} connects an asset to itself");
        }
        foreach (var d in twin.Diagnostics)
        {
            if (d.TargetAlias.Contains("://") || d.TargetAlias.Contains('/') || d.TargetAlias.Any(char.IsWhiteSpace))
                errors.Add($"Diagnostic {d.DiagnosticId} targetAlias must be a server-owned alias, not a URL or address");
        }
        var p = twin.VerificationPolicy;
        if (!p.AllowedDiagnosticSources.Contains(p.VerifiedLiveRequiresSource))
            errors.Add("verifiedLiveRequiresSource must be one of allowedDiagnosticSources");
        if (p.VerifiedLiveRequiresSource != DiagnosticSource.LiveLocal)
            errors.Add("verifiedLiveRequiresSource must be LiveLocal");
        if (!p.RequireFreshDiagnostic)
            errors.Add("requireFreshDiagnostic must be true: a verified outcome needs a fresh equipment check");
        if (p.RequireFreshDiagnostic && twin.Diagnostics.Count == 0)
            errors.Add("requireFreshDiagnostic is true but no diagnostics are declared");
        if (p.DiagnosticMaxAgeSeconds <= 0)
            errors.Add("diagnosticMaxAgeSeconds must be positive");
        return errors;
    }
}
