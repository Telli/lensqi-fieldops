using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Diagnostics;

/// <summary>A target the server resolved from a fixture alias. The model and client never construct one.</summary>
public sealed record ResolvedTarget(string Alias, Uri Url);

public sealed record ProbeRequest(string FixtureId, TwinDiagnostic Diagnostic, ResolvedTarget? Target, string TraceId);

/// <summary>Single execution interface for every probe kind. Implementations stamp their own DiagnosticSource.</summary>
public interface IDiagnosticProbe
{
    DiagnosticSource Source { get; }
    Task<DiagnosticResult> RunAsync(ProbeRequest request, CancellationToken cancellationToken);
}

public sealed class DiagnosticsOptions
{
    public const string SectionName = "Diagnostics";

    /// <summary>LiveLocal, Simulated or RecordedReplay. The mode is server configuration, never a request parameter.</summary>
    public DiagnosticSource Mode { get; set; } = DiagnosticSource.Simulated;

    /// <summary>Server-owned alias → URL map used only in LiveLocal mode. No other host can be probed.</summary>
    public Dictionary<string, string> Targets { get; set; } = new(StringComparer.Ordinal);

    /// <summary>Directory of recorded replay files (one JSON array of results per diagnosticId) for RecordedReplay mode.</summary>
    public string? ReplayDirectory { get; set; }

    public int MaxResponseBytes { get; set; } = 64 * 1024;
}

/// <summary>Demo-only switch for the simulated probe. Visible in every result as Source = Simulated.</summary>
public interface ISimulationState
{
    bool? OutcomeFor(string diagnosticId);
    void Set(string diagnosticId, bool? passed);
    IReadOnlyDictionary<string, bool?> Snapshot();
}

public sealed class InMemorySimulationState : ISimulationState
{
    private readonly Dictionary<string, bool?> _outcomes = new(StringComparer.Ordinal);
    private readonly object _gate = new();

    public bool? OutcomeFor(string diagnosticId)
    {
        lock (_gate) return _outcomes.TryGetValue(diagnosticId, out var v) ? v : false; // default: the fault is present
    }

    public void Set(string diagnosticId, bool? passed)
    {
        lock (_gate) _outcomes[diagnosticId] = passed;
    }

    public IReadOnlyDictionary<string, bool?> Snapshot()
    {
        lock (_gate) return new Dictionary<string, bool?>(_outcomes);
    }
}

public static class ProbeNormalization
{
    public static string? Normalize(bool? passed) => passed switch { true => "Reachable", false => "Unreachable", null => null };
}
