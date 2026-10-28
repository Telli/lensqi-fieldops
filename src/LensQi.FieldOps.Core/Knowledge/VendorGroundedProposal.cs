namespace LensQi.FieldOps.Core.Knowledge;

/// <summary>
/// Model-facing action vocabulary owned by this spec (design.md §5). Core maps each value onto the parent
/// FieldProof allowlist; the model never sees parent action names.
/// </summary>
public enum VendorGroundedNextAction
{
    RequestView,
    RunApprovedDiagnostic,
    RequestAssetConfirmation,
    RequestHumanAction,
    Abstain,
}

public enum PredictionOutcome { Confirmed, Falsified, Unscorable }

/// <summary>Pre-registered prediction (VK-R11). Required when NextAction is RunApprovedDiagnostic.</summary>
public sealed record Prediction(
    string DiagnosticId,
    string PredictedValue,
    IReadOnlyList<string> BasedOnFactIds,
    string? Rationale);

/// <summary>Typed structured output from Muse. Untrusted until <see cref="KnowledgeActionValidator"/> accepts it.</summary>
public sealed record VendorGroundedProposal(
    string AssetId,
    IReadOnlyList<string> UsedFactIds,
    string? Hypothesis,
    string? RequestedVisualEvidence,
    string? ProposedDiagnosticId,
    VendorGroundedNextAction NextAction,
    Prediction? Prediction);
