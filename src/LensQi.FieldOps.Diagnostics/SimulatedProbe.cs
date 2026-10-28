using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Diagnostics;

/// <summary>Deterministic stand-in for the lab endpoint. Never contacts a network; its results are labelled Simulated everywhere.</summary>
public sealed class SimulatedProbe(ISimulationState state, TimeProvider clock) : IDiagnosticProbe
{
    public DiagnosticSource Source => DiagnosticSource.Simulated;

    public Task<DiagnosticResult> RunAsync(ProbeRequest request, CancellationToken cancellationToken)
    {
        var passed = state.OutcomeFor(request.Diagnostic.DiagnosticId);
        var result = new DiagnosticResult(request.FixtureId, request.Diagnostic.DiagnosticId, request.Diagnostic.TargetAlias,
            passed, clock.GetUtcNow(), Source, request.TraceId, ProbeNormalization.Normalize(passed), RawDigest: null);
        return Task.FromResult(result);
    }
}
