using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Diagnostics;

namespace LensQi.FieldOps.Api.Features.Simulation;

/// <summary>Demo-only controls for the simulated probe. Unavailable unless Diagnostics:Mode is Simulated; every result it
/// influences is labelled Simulated in events, API responses and receipts.</summary>
public static class SimulationEndpoints
{
    public sealed record SetOutcomeRequest(bool? Passed);

    public static IEndpointRouteBuilder MapSimulation(this IEndpointRouteBuilder app)
    {
        var g = app.MapGroup("/v1/simulation");
        g.MapGet("/diagnostics", (ISimulationState state, DiagnosticsOptions options) =>
            Results.Ok(new { mode = options.Mode.ToString(), outcomes = state.Snapshot() }));
        g.MapPost("/diagnostics/{diagnosticId}", (string diagnosticId, SetOutcomeRequest req, ISimulationState state, DiagnosticsOptions options) =>
        {
            if (options.Mode != DiagnosticSource.Simulated)
                return ApiResults.Conflict("simulation_disabled", $"Diagnostics mode is {options.Mode}; simulated outcomes cannot be set");
            state.Set(diagnosticId, req.Passed);
            return Results.Ok(new { diagnosticId, passed = req.Passed, label = "SIMULATED_PROBE" });
        });
        return app;
    }
}
