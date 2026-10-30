namespace LensQi.FieldOps.Core.Tasks;

/// <summary>
/// The guided-task aggregate. Commands check the current state, append events and return them; a replayed request ID
/// returns the earlier outcome and appends nothing. Only this class changes a task's phase or step; models suggest, the
/// user decides, and the validator rejects any plan that breaks the safety rules.
/// </summary>
public sealed class TaskSession
{
    private readonly List<TaskEvent> _events;
    private readonly Dictionary<string, IReadOnlyList<TaskEvent>> _outcomes = new(StringComparer.Ordinal);
    private string? _requestId;

    private TaskSession(List<TaskEvent> events)
    {
        _events = events;
        State = TaskState.Replay(events);
        // Outcomes come back from the log: each event carries the request that appended it.
        foreach (var batch in events.Where(e => e.RequestId is not null).GroupBy(e => e.RequestId!, StringComparer.Ordinal))
            _outcomes[batch.Key] = batch.ToList();
    }

    public TaskState State { get; private set; }
    public IReadOnlyList<TaskEvent> Events => _events;

    public const string FreeformGoal = "Free-form help";

    public static TaskSession Create(string taskId, TaskMode mode, string goal, string? details, SiteLink? site, DateTimeOffset now)
    {
        goal = goal?.Trim() ?? "";
        if (mode == TaskMode.Freeform && goal.Length == 0) goal = FreeformGoal;
        details = string.IsNullOrWhiteSpace(details) ? null : details.Trim();
        if (goal.Length is < 3 or > 300) throw new TaskConflictException(TaskErrorCodes.InvalidInput, "Describe the goal in 3 to 300 characters");
        if (details is { Length: > 1000 }) throw new TaskConflictException(TaskErrorCodes.InvalidInput, "Details are limited to 1000 characters");
        if (site is not null && mode != TaskMode.Technician) throw new TaskConflictException(TaskErrorCodes.InvalidInput, "Only technician tasks attach to a site record");
        return new TaskSession([new TaskCreated(1, taskId, now, mode, goal, details, site)]);
    }

    public static TaskSession Rehydrate(IEnumerable<TaskEvent> events) => new(events.ToList());

    /// <summary>The events an earlier command with this request ID appended, or null when the ID has not been used.</summary>
    public IReadOnlyList<TaskEvent>? Replayed(string requestId) => _outcomes.GetValueOrDefault(requestId);

    // ---------------- commands ----------------

    public IReadOnlyList<TaskEvent> RecordPhoto(string requestId, string evidenceId, string sha256, int width, int height, TaskPhotoPurpose purpose, string? stepId, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            Require(!State.IsClosed, "The task is closed");
            if (State.Photos.Any(p => p.EvidenceId == evidenceId)) throw new TaskConflictException(TaskErrorCodes.DuplicateEvidence, $"Photo '{evidenceId}' is already recorded");
            if (stepId is not null) Step(stepId);
            return Commit([new TaskPhotoRecorded(0, State.TaskId, now, evidenceId, sha256, width, height, purpose, stepId)]);
        });

    /// <summary>Records the AI-written plan after <see cref="TaskPlanValidator"/> accepts it. One plan per task.</summary>
    public IReadOnlyList<TaskEvent> ProposePlan(string requestId, TaskPlan plan, string modelId, string promptDigest, string responseDigest, IReadOnlyList<string> manualPhotoIds, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            Require(State.Mode != TaskMode.Freeform, "Free-form help has no plan; start a planned task instead");
            Require(State.Phase == TaskPhase.Planned && State.Plan is null, "This task already has a plan");
            foreach (var id in manualPhotoIds)
                Require(State.Photos.Any(p => p.EvidenceId == id && p.Purpose == TaskPhotoPurpose.Manual), $"Manual photo '{id}' is not part of this task");
            TaskPlanValidator.Validate(plan, State.Goal, State.Details);
            return Commit([new PlanProposed(0, State.TaskId, now, plan, modelId, promptDigest, responseDigest, manualPhotoIds)]);
        });

    /// <summary>Starting a plan with any risk or caution needs the user's acknowledgement of its safety notes.</summary>
    public IReadOnlyList<TaskEvent> AcceptPlan(string requestId, bool safetyAcknowledged, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            var plan = State.Plan;
            Require(plan is not null && State.Phase == TaskPhase.Planned, "There is no plan waiting to start");
            var needsAck = plan!.Risk != RiskLevel.Low || plan.ProfessionalRequired || plan.Steps.Any(s => s.Cautions.Count > 0);
            if (needsAck && !safetyAcknowledged) throw new TaskConflictException(TaskErrorCodes.SafetyNotAcknowledged, "Read and accept the safety notes before starting");
            return Commit([new PlanAccepted(0, State.TaskId, now, safetyAcknowledged), new StepStarted(0, State.TaskId, now, plan.Steps[0].Id)]);
        });

    public IReadOnlyList<TaskEvent> StartStep(string requestId, string stepId, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            RequireInProgress();
            Step(stepId);
            // A finished step is looked at, not restarted: making it current would leave nothing to press but Abandon.
            Require(!State.IsFinished(stepId), $"Step {stepId} is already finished");
            return State.CurrentStepId == stepId ? [] : Commit([new StepStarted(0, State.TaskId, now, stepId)]);
        });

    /// <summary>Stores the AI's suggestion about a step photo. It never finishes the step.</summary>
    public IReadOnlyList<TaskEvent> RecordCheck(string requestId, string stepId, string photoId, StepCheckVerdict verdict, string reason, string modelId, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            RequireInProgress();
            Step(stepId);
            Require(State.Photos.Any(p => p.EvidenceId == photoId && p.Purpose == TaskPhotoPurpose.StepCheck && p.StepId == stepId), $"Photo '{photoId}' is not a check photo for step {stepId}");
            if (string.IsNullOrWhiteSpace(reason) || reason.Length > 400) throw new TaskConflictException(TaskErrorCodes.InvalidInput, "A check reason of 1 to 400 characters is required");
            return Commit([new StepChecked(0, State.TaskId, now, stepId, photoId, verdict, reason, modelId)]);
        });

    public IReadOnlyList<TaskEvent> RecordAnswer(string requestId, string? stepId, string question, string answer, string? photoId, string modelId, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            // Coaching starts only once the plan's safety notes are accepted (free-form help is in progress from the start).
            Require(State.Phase == TaskPhase.InProgress, "Questions need a started task, or free-form help");
            if (stepId is not null) Step(stepId);
            if (photoId is not null) Require(State.Photos.Any(p => p.EvidenceId == photoId), $"Photo '{photoId}' is not part of this task");
            if (string.IsNullOrWhiteSpace(question) || question.Length > 500) throw new TaskConflictException(TaskErrorCodes.InvalidInput, "Questions are 1 to 500 characters");
            if (string.IsNullOrWhiteSpace(answer) || answer.Length > 1200) throw new TaskConflictException(TaskErrorCodes.InvalidInput, "Answers are 1 to 1200 characters");
            return Commit([new QuestionAnswered(0, State.TaskId, now, stepId, question.Trim(), answer.Trim(), photoId, modelId)]);
        });

    /// <summary>
    /// The user marks a step done. A suggested photo check is a prompt in the clients, not a gate: the check is only ever a
    /// suggestion, so it never stands between the user and the next step.
    /// </summary>
    public IReadOnlyList<TaskEvent> CompleteStep(string requestId, string stepId, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            RequireInProgress();
            Step(stepId);
            Require(!State.IsFinished(stepId), $"Step {stepId} is already finished");
            return Commit(Advance(new StepCompleted(0, State.TaskId, now, stepId), stepId, now));
        });

    public IReadOnlyList<TaskEvent> SkipStep(string requestId, string stepId, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            RequireInProgress();
            Step(stepId);
            Require(!State.IsFinished(stepId), $"Step {stepId} is already finished");
            return Commit(Advance(new StepSkipped(0, State.TaskId, now, stepId), stepId, now));
        });

    /// <summary>Ends a free-form session; planned tasks finish by completing or skipping their steps.</summary>
    public IReadOnlyList<TaskEvent> Finish(string requestId, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            Require(State.Mode == TaskMode.Freeform && !State.IsClosed, "Only open free-form help is ended this way");
            return Commit([new TaskCompleted(0, State.TaskId, now)]);
        });

    public IReadOnlyList<TaskEvent> Abandon(string requestId, string? reason, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            Require(!State.IsClosed, "The task is closed");
            return Commit([new TaskAbandoned(0, State.TaskId, now, string.IsNullOrWhiteSpace(reason) ? null : reason.Trim()[..Math.Min(reason.Trim().Length, 300)])]);
        });

    public IReadOnlyList<TaskEvent> LinkInspection(string requestId, string inspectionId, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            Require(State.Mode == TaskMode.Technician && State.Site is not null, "Only a technician task on a site record can be verified with FieldProof");
            Require(State.InspectionId is null, "This task is already linked to an inspection");
            return Commit([new InspectionLinked(0, State.TaskId, now, inspectionId)]);
        });

    // ---------------- helpers ----------------

    /// <summary>After a step is finished: start the next unfinished step, or complete the task when none is left.</summary>
    private List<TaskEvent> Advance(TaskEvent finished, string stepId, DateTimeOffset now)
    {
        var steps = State.Plan!.Steps;
        var done = new HashSet<string>(State.CompletedStepIds.Concat(State.SkippedStepIds).Append(stepId), StringComparer.Ordinal);
        var index = steps.ToList().FindIndex(s => s.Id == stepId);
        var next = steps.Skip(index + 1).Concat(steps.Take(index)).FirstOrDefault(s => !done.Contains(s.Id));
        return next is null
            ? [finished, new TaskCompleted(0, State.TaskId, now)]
            : [finished, new StepStarted(0, State.TaskId, now, next.Id)];
    }

    private TaskStep Step(string stepId) =>
        State.Plan?.Steps.FirstOrDefault(s => s.Id == stepId) ?? throw new TaskConflictException(TaskErrorCodes.UnknownStep, $"Step '{stepId}' is not in this plan");

    private void RequireInProgress() => Require(State.Phase == TaskPhase.InProgress, "Start the plan first");

    private void Require(bool condition, string message)
    {
        if (!condition) throw new TaskConflictException(TaskErrorCodes.InvalidState, $"{message} (phase {State.Phase})");
    }

    private IReadOnlyList<TaskEvent> Commit(List<TaskEvent> batch)
    {
        var seq = State.LastSequence;
        var numbered = batch.Select(e => e with { Sequence = ++seq, RequestId = _requestId }).ToList();
        foreach (var e in numbered)
        {
            _events.Add(e);
            State = State.Apply(e);
        }
        return numbered;
    }

    private IReadOnlyList<TaskEvent> Idempotent(string requestId, Func<IReadOnlyList<TaskEvent>> action)
    {
        if (_outcomes.TryGetValue(requestId, out var earlier)) return earlier;
        _requestId = requestId;
        try
        {
            var result = action();
            _outcomes[requestId] = result;
            return result;
        }
        finally { _requestId = null; }
    }
}
