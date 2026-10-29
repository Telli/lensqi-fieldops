namespace LensQi.FieldOps.Core.Tasks;

public sealed record TaskPhoto(string EvidenceId, string Sha256, int Width, int Height, TaskPhotoPurpose Purpose, string? StepId, DateTimeOffset RecordedAt);

public sealed record StepCheckRecord(string StepId, string PhotoId, StepCheckVerdict Verdict, string Reason, string ModelId, DateTimeOffset At);

public sealed record AnswerRecord(string? StepId, string Question, string Answer, string? PhotoId, string ModelId, DateTimeOffset At);

/// <summary>A task as its events say it is. Built only by <see cref="Replay"/>.</summary>
public sealed record TaskState
{
    public required string TaskId { get; init; }
    public required TaskMode Mode { get; init; }
    public required string Goal { get; init; }
    public string? Details { get; init; }
    public SiteLink? Site { get; init; }
    public DateTimeOffset CreatedAt { get; init; }
    public DateTimeOffset UpdatedAt { get; init; }
    public TaskPhase Phase { get; init; } = TaskPhase.Planned;
    public TaskPlan? Plan { get; init; }
    public string? PlanModelId { get; init; }
    public IReadOnlyList<string> ManualPhotoIds { get; init; } = [];
    public bool SafetyAcknowledged { get; init; }
    public string? CurrentStepId { get; init; }
    public IReadOnlyList<string> CompletedStepIds { get; init; } = [];
    public IReadOnlyList<string> SkippedStepIds { get; init; } = [];
    public IReadOnlyList<TaskPhoto> Photos { get; init; } = [];
    public IReadOnlyList<StepCheckRecord> Checks { get; init; } = [];
    public IReadOnlyList<AnswerRecord> Answers { get; init; } = [];
    public string? InspectionId { get; init; }
    public string? AbandonReason { get; init; }
    public long LastSequence { get; init; }

    public TaskStep? CurrentStep => Plan?.Steps.FirstOrDefault(s => s.Id == CurrentStepId);
    public bool IsClosed => Phase is TaskPhase.Completed or TaskPhase.Abandoned;
    public bool IsFinished(string stepId) => CompletedStepIds.Contains(stepId) || SkippedStepIds.Contains(stepId);

    public static TaskState Replay(IEnumerable<TaskEvent> events)
    {
        TaskState? state = null;
        foreach (var e in events)
            state = state is null
                ? e is TaskCreated c ? new TaskState { TaskId = c.TaskId, Mode = c.Mode, Goal = c.Goal, Details = c.Details, Site = c.Site, CreatedAt = c.OccurredAt, UpdatedAt = c.OccurredAt, LastSequence = c.Sequence,
                    // Free-form help has no plan to accept: it is in progress from the start.
                    Phase = c.Mode == TaskMode.Freeform ? TaskPhase.InProgress : TaskPhase.Planned }
                    : throw new InvalidOperationException("A task log must start with TaskCreated")
                : state.Apply(e);
        return state ?? throw new InvalidOperationException("A task log cannot be empty");
    }

    public TaskState Apply(TaskEvent e)
    {
        var next = this with { LastSequence = e.Sequence, UpdatedAt = e.OccurredAt };
        return e switch
        {
            PlanProposed p => next with { Plan = p.Plan, PlanModelId = p.ModelId, ManualPhotoIds = p.ManualPhotoIds },
            PlanAccepted a => next with { SafetyAcknowledged = a.SafetyAcknowledged, Phase = TaskPhase.InProgress },
            TaskPhotoRecorded ph => next with { Photos = [.. Photos, new TaskPhoto(ph.EvidenceId, ph.Sha256, ph.Width, ph.Height, ph.Purpose, ph.StepId, ph.OccurredAt)] },
            StepStarted s => next with { CurrentStepId = s.StepId },
            StepChecked c => next with { Checks = [.. Checks, new StepCheckRecord(c.StepId, c.PhotoId, c.Verdict, c.Reason, c.ModelId, c.OccurredAt)] },
            QuestionAnswered q => next with { Answers = [.. Answers, new AnswerRecord(q.StepId, q.Question, q.Answer, q.PhotoId, q.ModelId, q.OccurredAt)] },
            StepCompleted s => next with { CompletedStepIds = [.. CompletedStepIds, s.StepId], CurrentStepId = CurrentStepId == s.StepId ? null : CurrentStepId },
            StepSkipped s => next with { SkippedStepIds = [.. SkippedStepIds, s.StepId], CurrentStepId = CurrentStepId == s.StepId ? null : CurrentStepId },
            TaskCompleted => next with { Phase = TaskPhase.Completed, CurrentStepId = null },
            TaskAbandoned a => next with { Phase = TaskPhase.Abandoned, AbandonReason = a.Reason, CurrentStepId = null },
            InspectionLinked l => next with { InspectionId = l.InspectionId },
            _ => next,
        };
    }
}
