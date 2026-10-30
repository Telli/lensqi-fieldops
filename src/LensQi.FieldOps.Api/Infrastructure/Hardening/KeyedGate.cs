namespace LensQi.FieldOps.Api.Infrastructure.Hardening;

/// <summary>
/// Serialises requests that share one Idempotency-Key on one inspection. A retry that overlaps the first request waits
/// for it, then finds the recorded outcome and replays it, instead of paying for a second model call whose result the
/// session would discard. Requests without a key, or with different keys, never wait on each other.
/// </summary>
public sealed class KeyedGate
{
    private sealed class Entry { public readonly SemaphoreSlim Gate = new(1, 1); public int Users; }

    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);

    public async Task<IDisposable?> EnterAsync(string scope, string? key, CancellationToken ct)
    {
        if (key is null) return null;
        var name = scope + "\n" + key;
        Entry entry;
        lock (_entries)
        {
            if (!_entries.TryGetValue(name, out entry!)) _entries[name] = entry = new Entry();
            entry.Users++;
        }
        try { await entry.Gate.WaitAsync(ct).ConfigureAwait(false); }
        catch { Leave(name, entry, held: false); throw; }
        return new Release(() => Leave(name, entry, held: true));
    }

    private void Leave(string name, Entry entry, bool held)
    {
        if (held) entry.Gate.Release();
        lock (_entries) { if (--entry.Users == 0) _entries.Remove(name); }
    }

    private sealed class Release(Action leave) : IDisposable
    {
        private int _done;
        public void Dispose() { if (Interlocked.Exchange(ref _done, 1) == 0) leave(); }
    }
}
