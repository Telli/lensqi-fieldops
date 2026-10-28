using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Core.Inspection;

// Contracts from PRD v1.1 §7 and §17 and the parent design.md. Pure data; no provider types.

public enum InspectionStage
{
    Created, InitialScan, NeedsEvidence, FaultSuspected, FaultConfirmed,
    AwaitingRepair, ReadyToVerify, VerificationFailed, VerifiedComplete, Inconclusive,
}

public enum EvidenceOrigin { Phone, Glasses, RecordedFixture, MockWearables, Muse, Sam, LiveLocalProbe, SimulatedProbe, ReplayProbe, Technician }

public enum ObservationStatus { Observed, Suspected, Unknown, Conflicting }

/// <summary>Provenance of a diagnostic measurement. Only LiveLocal can ever yield VerifiedCompleteLive.</summary>
public enum DiagnosticSource { LiveLocal, Simulated, RecordedReplay, VendorReadOnly }

/// <summary>Label on a VerifiedComplete session. Kept distinct from InspectionStage (PRD §17).</summary>
public enum VerificationOutcomeKind { VerifiedCompleteLive, VerifiedCompleteSimulated }

public enum VerificationResult { Verified, Denied, NeedsHumanReview }

/// <summary>Technician's review of one visual requirement after the repair claim.</summary>
public enum ChecklistStatus { Satisfied, NotSatisfied, Unknown, Conflicting }

public sealed record Hypothesis(string FeatureId, ObservationStatus Status, string Reason, IReadOnlyList<string> EvidenceIds);

/// <summary>Typed Muse output for a scene. Untrusted until the session validates asset ID, features and action.</summary>
public sealed record VisualAssessment(
    string AssetId,
    IReadOnlyList<Hypothesis> Hypotheses,
    ProposedNextAction NextAction,
    string? RequestedView,
    string? RequestedSegmentationConcept,
    string ModelId,
    string SchemaVersion);

/// <summary>Result of an allowlisted probe. Produced by the Diagnostics layer; the model never constructs one.</summary>
public sealed record DiagnosticResult(
    string FixtureId,
    string DiagnosticId,
    string TargetAlias,
    bool? Passed,
    DateTimeOffset CompletedAt,
    DiagnosticSource Source,
    string TraceId,
    string? NormalizedValue = null,
    string? RawDigest = null);

public sealed record VerificationDecision(
    VerificationResult Result,
    VerificationOutcomeKind? OutcomeKind,
    IReadOnlyList<string> Reasons,
    IReadOnlyList<string> VerifiedClaims,
    string PolicyVersion,
    string FixtureDigest)
{
    public bool IsVerified => Result == VerificationResult.Verified;
}

/// <summary>Stable codes for typed conflicts raised by session commands.</summary>
public static class InspectionErrorCodes
{
    public const string InvalidState = "invalid_state";
    public const string UnknownAsset = "unknown_asset";
    public const string InvalidModelOutput = "invalid_model_output";
    public const string ProbeNotAllowlisted = "probe_not_allowlisted";
    public const string FixtureMismatch = "fixture_mismatch";
    public const string ViewBudgetExhausted = "view_budget_exhausted";
    public const string DuplicateEvidence = "duplicate_evidence";
    public const string PredictionLate = "prediction_late";
}

public sealed class InspectionConflictException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}
