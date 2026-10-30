using System.Text.Json.Serialization;

namespace LensQi.FieldOps.Core.Tasks;

/// <summary>Append-only guided-task events. Server-timestamped, sequential from 1, never updated; state is a replay of the list.</summary>
[JsonPolymorphic(TypeDiscriminatorPropertyName = "kind")]
[JsonDerivedType(typeof(TaskCreated), "TaskCreated")]
[JsonDerivedType(typeof(PlanProposed), "PlanProposed")]
[JsonDerivedType(typeof(PlanAccepted), "PlanAccepted")]
[JsonDerivedType(typeof(TaskPhotoRecorded), "TaskPhotoRecorded")]
[JsonDerivedType(typeof(StepStarted), "StepStarted")]
[JsonDerivedType(typeof(StepChecked), "StepChecked")]
[JsonDerivedType(typeof(QuestionAnswered), "QuestionAnswered")]
[JsonDerivedType(typeof(StepCompleted), "StepCompleted")]
[JsonDerivedType(typeof(StepSkipped), "StepSkipped")]
[JsonDerivedType(typeof(TaskCompleted), "TaskCompleted")]
[JsonDerivedType(typeof(TaskAbandoned), "TaskAbandoned")]
[JsonDerivedType(typeof(InspectionLinked), "InspectionLinked")]
public abstract record TaskEvent(long Sequence, string TaskId, DateTimeOffset OccurredAt)
{
    /// <summary>The request that appended this event, so a retried request replays its outcome after a restart too.</summary>
    public string? RequestId { get; init; }
}

public sealed record TaskCreated(long Sequence, string TaskId, DateTimeOffset OccurredAt, TaskMode Mode, string Goal, string? Details, SiteLink? Site)
    : TaskEvent(Sequence, TaskId, OccurredAt);

/// <summary>The AI-written plan as validated, with what produced it.</summary>
public sealed record PlanProposed(long Sequence, string TaskId, DateTimeOffset OccurredAt, TaskPlan Plan, string ModelId, string PromptDigest, string ResponseDigest,
    IReadOnlyList<string> ManualPhotoIds) : TaskEvent(Sequence, TaskId, OccurredAt);

public sealed record PlanAccepted(long Sequence, string TaskId, DateTimeOffset OccurredAt, bool SafetyAcknowledged) : TaskEvent(Sequence, TaskId, OccurredAt);

public sealed record TaskPhotoRecorded(long Sequence, string TaskId, DateTimeOffset OccurredAt, string EvidenceId, string Sha256, int Width, int Height,
    TaskPhotoPurpose Purpose, string? StepId) : TaskEvent(Sequence, TaskId, OccurredAt);

public sealed record StepStarted(long Sequence, string TaskId, DateTimeOffset OccurredAt, string StepId) : TaskEvent(Sequence, TaskId, OccurredAt);

/// <summary>An AI suggestion about a step photo; inferred, never a decision.</summary>
public sealed record StepChecked(long Sequence, string TaskId, DateTimeOffset OccurredAt, string StepId, string PhotoId, StepCheckVerdict Verdict, string Reason, string ModelId)
    : TaskEvent(Sequence, TaskId, OccurredAt);

public sealed record QuestionAnswered(long Sequence, string TaskId, DateTimeOffset OccurredAt, string? StepId, string Question, string Answer, string? PhotoId, string ModelId)
    : TaskEvent(Sequence, TaskId, OccurredAt);

public sealed record StepCompleted(long Sequence, string TaskId, DateTimeOffset OccurredAt, string StepId) : TaskEvent(Sequence, TaskId, OccurredAt);

public sealed record StepSkipped(long Sequence, string TaskId, DateTimeOffset OccurredAt, string StepId) : TaskEvent(Sequence, TaskId, OccurredAt);

public sealed record TaskCompleted(long Sequence, string TaskId, DateTimeOffset OccurredAt) : TaskEvent(Sequence, TaskId, OccurredAt);

public sealed record TaskAbandoned(long Sequence, string TaskId, DateTimeOffset OccurredAt, string? Reason) : TaskEvent(Sequence, TaskId, OccurredAt);

/// <summary>A technician task handed to the FieldProof inspection for its site asset.</summary>
public sealed record InspectionLinked(long Sequence, string TaskId, DateTimeOffset OccurredAt, string InspectionId) : TaskEvent(Sequence, TaskId, OccurredAt);
