using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Core.Diagnostics;

/// <summary>A server-registered probe: the only place a diagnostic ID gains an observation kind and a closed value set.</summary>
public sealed record DiagnosticRegistration(string DiagnosticId, ObservationKind ObservationKind, string Description);

/// <summary>Server-owned probe registry (VK-R06.1). A diagnostic must be here AND in the fixture allowlist to run.</summary>
public interface IDiagnosticRegistry
{
    DiagnosticRegistration? Find(string diagnosticId);
    IReadOnlyList<DiagnosticRegistration> All { get; }
}

public sealed class InMemoryDiagnosticRegistry : IDiagnosticRegistry
{
    private readonly Dictionary<string, DiagnosticRegistration> _byId;

    public InMemoryDiagnosticRegistry(IEnumerable<DiagnosticRegistration> entries)
    {
        All = entries.ToList();
        _byId = All.ToDictionary(e => e.DiagnosticId, StringComparer.Ordinal);
    }

    public DiagnosticRegistration? Find(string diagnosticId) => _byId.GetValueOrDefault(diagnosticId);
    public IReadOnlyList<DiagnosticRegistration> All { get; }
}

/// <summary>Closed predicted-value sets per observation kind (design.md §6a). The model may only predict these strings.</summary>
public static class PredictionValueSets
{
    public static IReadOnlyList<string> For(ObservationKind kind) => kind switch
    {
        ObservationKind.EndpointReachability => ["Reachable", "Unreachable"],
        ObservationKind.InterfaceLinkState => ["LinkReportedUp", "LinkReportedDown"],
        ObservationKind.AssetInventory => ["MatchesExpected", "DiffersFromExpected"],
        _ => [], // Visual is not predictable through a probe.
    };
}
