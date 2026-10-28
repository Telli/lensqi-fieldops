namespace LensQi.FieldOps.Core.Knowledge;

/// <summary>
/// The single next-action vocabulary for every model proposal (parent design.md, FieldOps v1.1). The knowledge
/// prompt offers all values except SegmentRegion, which only the parent visual-assessment prompt may propose.
/// </summary>
public enum ProposedNextAction
{
    RequestView,
    SegmentRegion,
    RunApprovedProbe,
    PrepareWorkOrder,
    RequestHumanReview,
    StopInconclusive,
}

public static class ProposedNextActions
{
    /// <summary>Actions a vendor-knowledge proposal may carry. SegmentRegion is excluded by design.</summary>
    public static readonly IReadOnlyList<ProposedNextAction> KnowledgeAllowed =
    [
        ProposedNextAction.RequestView,
        ProposedNextAction.RunApprovedProbe,
        ProposedNextAction.PrepareWorkOrder,
        ProposedNextAction.RequestHumanReview,
        ProposedNextAction.StopInconclusive,
    ];
}

public enum PredictionOutcome { Confirmed, Falsified, Unscorable }

/// <summary>Pre-registered prediction (VK-R11). Required when NextAction is RunApprovedProbe.</summary>
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
    ProposedNextAction NextAction,
    Prediction? Prediction);
