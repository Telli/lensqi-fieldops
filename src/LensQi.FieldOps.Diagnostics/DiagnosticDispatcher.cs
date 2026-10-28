using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Diagnostics;

/// <summary>
/// Resolves a fixture-approved diagnostic to the configured probe and target (design §6 dispatch sequence).
/// Input is a diagnostic ID and the bound twin, nothing else. Returns a DiagnosticResult the Core session validates again.
/// </summary>
public sealed class DiagnosticDispatcher
{
    private readonly DiagnosticsOptions _options;
    private readonly IReadOnlyDictionary<DiagnosticSource, IDiagnosticProbe> _probes;

    public DiagnosticDispatcher(DiagnosticsOptions options, IEnumerable<IDiagnosticProbe> probes)
    {
        _options = options;
        _probes = probes.ToDictionary(p => p.Source);
    }

    public DiagnosticSource Mode => _options.Mode;

    public Task<DiagnosticResult> RunAsync(ExpectedTwin twin, string diagnosticId, string traceId, CancellationToken cancellationToken)
    {
        var diag = twin.FindDiagnostic(diagnosticId)
                   ?? throw new InspectionConflictException(InspectionErrorCodes.ProbeNotAllowlisted, $"Diagnostic '{diagnosticId}' is not approved by fixture {twin.FixtureId}; no probe was run");
        if (!_probes.TryGetValue(_options.Mode, out var probe))
            throw new InvalidOperationException($"No probe registered for mode {_options.Mode}");

        ResolvedTarget? target = null;
        if (_options.Mode == DiagnosticSource.LiveLocal)
        {
            if (!_options.Targets.TryGetValue(diag.TargetAlias, out var url) || !Uri.TryCreate(url, UriKind.Absolute, out var uri) || !LiveLocalHttpHealthProbe.IsAcceptable(uri))
                throw new InspectionConflictException(InspectionErrorCodes.ProbeNotAllowlisted, $"Alias '{diag.TargetAlias}' has no acceptable server-side target; no probe was run");
            target = new ResolvedTarget(diag.TargetAlias, uri);
        }
        return probe.RunAsync(new ProbeRequest(twin.FixtureId, diag, target, traceId), cancellationToken);
    }
}
