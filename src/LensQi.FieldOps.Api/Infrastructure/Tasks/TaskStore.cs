using System.Collections.Concurrent;
using System.Text.Json;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Core.Tasks;

namespace LensQi.FieldOps.Api.Infrastructure.Tasks;

/// <summary>
/// Append-only guided-task store, the same mechanics as the inspection store: sessions in memory, one JSONL log per task
/// (one event per line, never rewritten), one lock per task serialising its commands.
/// </summary>
public sealed class TaskStore
{
    private readonly ConcurrentDictionary<string, Entry> _tasks = new(StringComparer.Ordinal);
    // Creation request key → task id, so a retried create returns the task it made (rebuilt from TaskCreated on load).
    private readonly ConcurrentDictionary<string, string> _created = new(StringComparer.Ordinal);
    private readonly Lock _createLock = new();
    private readonly string _root;

    private sealed class Entry(TaskSession session)
    {
        public TaskSession Session { get; } = session;
        public SemaphoreSlim Gate { get; } = new(1, 1);
        public int PersistedCount { get; set; }
    }

    public sealed record SkippedLog(string File, string Reason);

    public TaskStore(string dataDirectory)
    {
        _root = Path.Combine(dataDirectory, "tasks");
        Directory.CreateDirectory(_root);
    }

    public IEnumerable<TaskSession> All => _tasks.Values.Select(e => e.Session);

    public TaskSession? Find(string taskId) => _tasks.TryGetValue(taskId, out var e) ? e.Session : null;

    /// <summary>Rebuilds every persisted task. A log that does not parse or is not contiguous is left on disk and reported.</summary>
    public IReadOnlyList<SkippedLog> LoadPersisted()
    {
        var skipped = new List<SkippedLog>();
        foreach (var path in Directory.EnumerateFiles(_root, "*.jsonl").OrderBy(p => p, StringComparer.Ordinal))
        {
            var file = Path.GetFileName(path);
            try
            {
                var events = File.ReadLines(path).Where(l => !string.IsNullOrWhiteSpace(l))
                    .Select(l => JsonSerializer.Deserialize<TaskEvent>(l, KnowledgeJson.Options) ?? throw new JsonException("null event")).ToList();
                if (events.Count == 0 || events[0] is not TaskCreated created) { skipped.Add(new(file, "does not start with TaskCreated")); continue; }
                if (created.TaskId + ".jsonl" != file) { skipped.Add(new(file, $"file name does not match {created.TaskId}")); continue; }
                if (events.Select((e, i) => e.Sequence == i + 1 && e.TaskId == created.TaskId).Any(ok => !ok)) { skipped.Add(new(file, "sequence is not contiguous from 1 or mixes tasks")); continue; }
                _tasks[created.TaskId] = new Entry(TaskSession.Rehydrate(events)) { PersistedCount = events.Count };
                if (created.RequestId is { } key) _created[key] = created.TaskId;
            }
            catch (Exception ex) when (ex is JsonException or NotSupportedException or InvalidOperationException or TaskConflictException)
            {
                skipped.Add(new(file, ex.GetType().Name + ": " + ex.Message));
            }
        }
        return skipped;
    }

    /// <summary>The task an earlier creation request with this key made, if any.</summary>
    public TaskSession? FindCreated(string? requestKey) =>
        requestKey is not null && _created.TryGetValue(requestKey, out var id) ? Find(id) : null;

    /// <summary>
    /// The task a create key made, only when the retry asks for the very same job: mode, goal, details, site, person, hand-off
    /// source and manual photos. A key reused for different inputs is refused, never answered with another job's task (the
    /// inspection store's <c>SameCreate</c> rule).
    /// </summary>
    public static TaskSession SameCreate(TaskSession earlier, TaskMode mode, string goal, string? details, SiteLink? site, PersonContext? person,
        string? handoffSourceTaskId, IReadOnlyCollection<string> manualDigests)
    {
        // The same normalisation the session applies on create, so a retry of the very same request matches its record.
        goal = goal.Trim();
        if (mode == TaskMode.Freeform && goal.Length == 0) goal = TaskSession.FreeformGoal;
        details = string.IsNullOrWhiteSpace(details) ? null : details.Trim();
        var s = earlier.State;
        var samePerson = (s.Person, person) switch
        {
            (null, null) => true,
            ({ } a, { } b) => a.HeightCm == b.HeightCm && a.Tools.SequenceEqual(b.Tools, StringComparer.Ordinal),
            _ => false,
        };
        var sameManuals = s.Photos.Where(p => p.Purpose == TaskPhotoPurpose.Manual).Select(p => p.Sha256).ToHashSet(StringComparer.Ordinal).SetEquals(manualDigests);
        return s.Mode == mode && string.Equals(s.Goal, goal, StringComparison.Ordinal) && string.Equals(s.Details, details, StringComparison.Ordinal)
            && s.Site == site && samePerson && s.Handoff?.SourceTaskId == handoffSourceTaskId && sameManuals
            ? earlier
            : throw new TaskConflictException(TaskErrorCodes.InvalidState, TaskSession.DifferentCommandMessage);
    }

    /// <summary>Creates a task, or returns the one an earlier request with the same key created (<c>Created</c> false).</summary>
    public (TaskSession Session, bool Created) Create(TaskMode mode, string goal, string? details, SiteLink? site, DateTimeOffset now, string? requestKey = null,
        PersonContext? person = null, TaskHandoff? handoff = null)
    {
        lock (_createLock)
        {
            if (FindCreated(requestKey) is { } earlier) return (earlier, false);
            var id = "T-" + Guid.NewGuid().ToString("N")[..12];
            var entry = new Entry(TaskSession.Create(id, mode, goal, details, site, now, requestKey, person, handoff));
            Persist(entry);
            _tasks[id] = entry;
            if (requestKey is not null) _created[requestKey] = id;
            return (entry.Session, true);
        }
    }

    /// <summary>Runs one command under the task's lock and appends any new events to its log.</summary>
    public async Task<T> ExecuteAsync<T>(string taskId, Func<TaskSession, Task<T>> command, CancellationToken ct)
    {
        if (!_tasks.TryGetValue(taskId, out var entry)) throw new KeyNotFoundException(taskId);
        await entry.Gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            var result = await command(entry.Session).ConfigureAwait(false);
            Persist(entry);
            return result;
        }
        finally { entry.Gate.Release(); }
    }

    public T Execute<T>(string taskId, Func<TaskSession, T> command) =>
        ExecuteAsync(taskId, s => Task.FromResult(command(s)), CancellationToken.None).GetAwaiter().GetResult();

    private void Persist(Entry entry)
    {
        var events = entry.Session.Events;
        if (events.Count <= entry.PersistedCount) return;
        using var writer = new StreamWriter(Path.Combine(_root, entry.Session.State.TaskId + ".jsonl"), append: true);
        for (var i = entry.PersistedCount; i < events.Count; i++)
            writer.WriteLine(JsonSerializer.Serialize(events[i], KnowledgeJson.Options));
        entry.PersistedCount = events.Count;
    }
}
