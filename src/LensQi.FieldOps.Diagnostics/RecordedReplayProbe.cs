using System.Text.Json;
using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Diagnostics;

/// <summary>Replays previously recorded results in order from `{ReplayDirectory}/{diagnosticId}.json`. Labelled RecordedReplay.</summary>
public sealed class RecordedReplayProbe(DiagnosticsOptions options, TimeProvider clock) : IDiagnosticProbe
{
    private readonly Dictionary<string, Queue<bool?>> _queues = new(StringComparer.Ordinal);
    private readonly object _gate = new();

    public DiagnosticSource Source => DiagnosticSource.RecordedReplay;

    public Task<DiagnosticResult> RunAsync(ProbeRequest request, CancellationToken cancellationToken)
    {
        bool? passed;
        lock (_gate)
        {
            if (!_queues.TryGetValue(request.Diagnostic.DiagnosticId, out var q))
            {
                q = Load(request.Diagnostic.DiagnosticId);
                _queues[request.Diagnostic.DiagnosticId] = q;
            }
            passed = q.Count > 0 ? q.Dequeue() : null; // exhausted recording → Unavailable, never a presumed pass
        }
        return Task.FromResult(new DiagnosticResult(request.FixtureId, request.Diagnostic.DiagnosticId, request.Diagnostic.TargetAlias,
            passed, clock.GetUtcNow(), Source, request.TraceId, ProbeNormalization.Normalize(passed), null));
    }

    private Queue<bool?> Load(string diagnosticId)
    {
        if (options.ReplayDirectory is null) return new Queue<bool?>();
        var path = Path.Combine(options.ReplayDirectory, diagnosticId + ".json");
        if (!File.Exists(path)) return new Queue<bool?>();
        var values = JsonSerializer.Deserialize<List<bool?>>(File.ReadAllText(path)) ?? [];
        return new Queue<bool?>(values);
    }
}
