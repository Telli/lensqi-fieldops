using System.Collections.Concurrent;
using System.Text.Json;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Api.Infrastructure.Inspections;

public sealed class StorageOptions
{
    public const string SectionName = "Storage";
    /// <summary>Root for append-only event logs and evidence artifacts. Relative paths resolve from the content root.</summary>
    public string DataDirectory { get; set; } = "data";
}

/// <summary>
/// Append-only inspection store: in-memory sessions plus a JSONL log per inspection (one event per line, never rewritten).
/// SQLite can replace the file later without touching the Core session. One lock per inspection serializes commands.
/// </summary>
public sealed class InspectionStore
{
    private readonly ConcurrentDictionary<string, Entry> _sessions = new(StringComparer.Ordinal);
    private readonly string _root;

    private sealed class Entry(InspectionSession session)
    {
        public InspectionSession Session { get; } = session;
        public SemaphoreSlim Gate { get; } = new(1, 1);
        public int PersistedCount { get; set; }
    }

    public InspectionStore(string dataDirectory)
    {
        _root = Path.Combine(dataDirectory, "inspections");
        Directory.CreateDirectory(_root);
    }

    public IEnumerable<InspectionSession> All => _sessions.Values.Select(e => e.Session);

    public InspectionSession? Find(string inspectionId) => _sessions.TryGetValue(inspectionId, out var e) ? e.Session : null;

    public InspectionSession Create(LoadedExpectedTwin twin, string assetId, DateTimeOffset now)
    {
        var id = "I-" + Guid.NewGuid().ToString("N")[..12];
        var session = InspectionSession.Create(id, twin, assetId, now);
        var entry = new Entry(session);
        _sessions[id] = entry;
        Persist(entry);
        return session;
    }

    /// <summary>Runs one command under the inspection's lock and appends any new events to the JSONL log.</summary>
    public async Task<T> ExecuteAsync<T>(string inspectionId, Func<InspectionSession, Task<T>> command, CancellationToken ct)
    {
        if (!_sessions.TryGetValue(inspectionId, out var entry))
            throw new KeyNotFoundException(inspectionId);
        await entry.Gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            var result = await command(entry.Session).ConfigureAwait(false);
            Persist(entry);
            return result;
        }
        finally
        {
            entry.Gate.Release();
        }
    }

    public T Execute<T>(string inspectionId, Func<InspectionSession, T> command) =>
        ExecuteAsync(inspectionId, s => Task.FromResult(command(s)), CancellationToken.None).GetAwaiter().GetResult();

    private void Persist(Entry entry)
    {
        var events = entry.Session.Events;
        if (events.Count <= entry.PersistedCount) return;
        var path = Path.Combine(_root, entry.Session.State.InspectionId + ".jsonl");
        using var writer = new StreamWriter(path, append: true);
        for (var i = entry.PersistedCount; i < events.Count; i++)
            writer.WriteLine(JsonSerializer.Serialize(events[i], KnowledgeJson.Options));
        entry.PersistedCount = events.Count;
    }
}
