using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Tests;

/// <summary>Loads the shipped LAB-RACK-01 twin and drives a session through the canonical flow with a controllable clock.</summary>
public sealed class InspectionTestKit
{
    public static readonly DateTimeOffset T0 = new(2026, 10, 30, 10, 0, 0, TimeSpan.Zero);

    public LoadedExpectedTwin Twin { get; }
    public InspectionSession Session { get; }
    public DateTimeOffset Now { get; private set; } = T0;
    private int _n;

    public InspectionTestKit(string assetId = "LAB-SW-01")
    {
        Twin = LoadTwin();
        Session = InspectionSession.Create("I-1001", Twin, assetId, Now);
    }

    public static LoadedExpectedTwin LoadTwin()
    {
        var path = Path.Combine(RepoPaths.FixturesDir, "..", "telecom-lab", "expected-twin.json");
        var bytes = File.ReadAllBytes(path);
        var twin = JsonSerializer.Deserialize<ExpectedTwin>(bytes, KnowledgeJson.Options)!;
        return new LoadedExpectedTwin(twin, Convert.ToHexStringLower(SHA256.HashData(bytes)), "expected-twin.json");
    }

    public DateTimeOffset Tick(int seconds = 10) => Now = Now.AddSeconds(seconds);
    private string Id(string prefix) => $"{prefix}-{++_n:000}";
    private static string Digest(string s) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(s)));

    public string Photo(EvidenceOrigin origin = EvidenceOrigin.Phone)
    {
        var id = Id("photo");
        Session.RecordPhoto(Id("req"), id, origin, Digest(id), Now, 1600, 1000, Now);
        return id;
    }

    public VisualAssessment Assessment(ProposedNextAction action, ObservationStatus status, string photoId, string? view = "Show the switch faceplate and the yellow cable connector") =>
        new("LAB-SW-01", [new Hypothesis("yellow_cable_near_end", status, "test", [photoId])], action, view, null, "muse-spark-1.3", "1.0");

    public void Assess(ProposedNextAction action, ObservationStatus status, string photoId, string? view = "Show the switch faceplate and the yellow cable connector") =>
        Session.RecordAssessment(Id("req"), Id("assess"), Assessment(action, status, photoId, view), Now);

    public DiagnosticResult Probe(bool? passed, DiagnosticSource source = DiagnosticSource.Simulated, string fixture = "LAB-RACK-01", DateTimeOffset? at = null) =>
        new(fixture, "NODE-A-HEALTH", "lab-node-a", passed, at ?? Now, source, Id("trace"), passed switch { true => "Reachable", false => "Unreachable", null => null });

    public void RecordProbe(DiagnosticResult result) => Session.RecordDiagnostic(Id("req"), Id("diag"), result, Now);

    /// <summary>Observe → suspect → failing probe → fault confirmed → work order → repair claim. Leaves the session in ReadyToVerify.</summary>
    public string ReachRepairClaim()
    {
        var p = Photo();
        Assess(ProposedNextAction.RunApprovedProbe, ObservationStatus.Suspected, p, null);
        Tick(); RecordProbe(Probe(false));
        Tick(); Session.DraftWorkOrder(Id("req"), "Reseat yellow cable at P2", Now);
        Tick(); Session.ClaimRepair(Id("req"), Id("claim"), "Reseated the cable", Now);
        return p;
    }

    /// <summary>Post-repair photo, satisfied checklist and acknowledgement, no diagnostic yet.</summary>
    public string PostRepairEvidence()
    {
        Tick(); var p = Photo();
        foreach (var req in Twin.Twin.MandatoryRequirements)
            Session.ReviewChecklist(Id("req"), req.RequirementId, ChecklistStatus.Satisfied, p, Now);
        Session.Acknowledge(Id("req"), Now);
        return p;
    }

    public VerificationDecision Verify(string? requestId = null) => Session.Verify(requestId ?? Id("verify"), Now);
}
