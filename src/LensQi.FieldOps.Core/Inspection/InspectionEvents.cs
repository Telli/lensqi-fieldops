using System.Text.Json.Serialization;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Core.Inspection;

/// <summary>
/// Append-only inspection events (PRD FR-09, design "State / evidence workflow"). Every event carries a server-owned
/// timestamp and the fixture digest in force. Events are never updated in place; state is a replay of the list.
/// </summary>
[JsonPolymorphic(TypeDiscriminatorPropertyName = "kind")]
[JsonDerivedType(typeof(InspectionCreated), "InspectionCreated")]
[JsonDerivedType(typeof(PhotoRecorded), "PhotoRecorded")]
[JsonDerivedType(typeof(SegmentationRecorded), "SegmentationRecorded")]
[JsonDerivedType(typeof(AssessmentRecorded), "AssessmentRecorded")]
[JsonDerivedType(typeof(ViewRequested), "ViewRequested")]
[JsonDerivedType(typeof(ViewDeclined), "ViewDeclined")]
[JsonDerivedType(typeof(PredictionRegistered), "PredictionRegistered")]
[JsonDerivedType(typeof(DiagnosticCompleted), "DiagnosticCompleted")]
[JsonDerivedType(typeof(PredictionScored), "PredictionScored")]
[JsonDerivedType(typeof(WorkOrderDrafted), "WorkOrderDrafted")]
[JsonDerivedType(typeof(RepairClaimed), "RepairClaimed")]
[JsonDerivedType(typeof(ChecklistReviewed), "ChecklistReviewed")]
[JsonDerivedType(typeof(TechnicianAcknowledged), "TechnicianAcknowledged")]
[JsonDerivedType(typeof(VerificationEvaluated), "VerificationEvaluated")]
[JsonDerivedType(typeof(StageChanged), "StageChanged")]
public abstract record InspectionEvent(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest)
{
    [JsonIgnore] public string EventKind => GetType().Name;
}

public sealed record InspectionCreated(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    string FixtureId, int FixtureRevision, string AssetId) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record PhotoRecorded(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    string EvidenceId, EvidenceOrigin Origin, string ArtifactSha256, DateTimeOffset ObservedAt, int Width, int Height, ImageQualitySignals? Quality = null) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record SegmentationRecorded(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    string EvidenceId, string SourcePhotoEvidenceId, string Concept, bool Matched, string? MaskSha256, string? ModelId) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record AssessmentRecorded(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    string EvidenceId, VisualAssessment Assessment) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record ViewRequested(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    int RequestNumber, string Reason, string? TargetFeatureId) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record ViewDeclined(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    int RequestNumber) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record PredictionRegistered(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    string PredictionId, string DiagnosticId, string PredictedValue, IReadOnlyList<string> BasedOnFactIds) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record DiagnosticCompleted(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    string EvidenceId, DiagnosticResult Result) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record PredictionScored(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    string PredictionId, string DiagnosticEvidenceId, string? MeasuredValue, PredictionOutcome Outcome) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record WorkOrderDrafted(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    string Summary) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record RepairClaimed(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    string ClaimId, string Description) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record ChecklistReviewed(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    string RequirementId, ChecklistStatus Status, string ReviewedByEvidenceId) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record TechnicianAcknowledged(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    string ClaimId) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record VerificationEvaluated(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    string RequestId, VerificationDecision Decision) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);

public sealed record StageChanged(long Sequence, string InspectionId, DateTimeOffset OccurredAt, string FixtureDigest,
    InspectionStage From, InspectionStage To, string Reason) : InspectionEvent(Sequence, InspectionId, OccurredAt, FixtureDigest);
