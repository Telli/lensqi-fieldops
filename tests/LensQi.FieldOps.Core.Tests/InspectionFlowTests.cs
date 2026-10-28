using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Tests;

public class InspectionFlowTests
{
    [Fact]
    public void Happy_path_fake_fix_then_real_fix_is_simulated_verified_with_full_event_trail()
    {
        var k = new InspectionTestKit();
        var p0 = k.Photo();
        k.Assess(ProposedNextAction.RequestView, ObservationStatus.Unknown, p0);
        Assert.Equal(InspectionStage.NeedsEvidence, k.Session.State.Stage);
        var p1 = k.Photo();                                             // close-up
        k.Assess(ProposedNextAction.RunApprovedProbe, ObservationStatus.Suspected, p1, null);
        Assert.Equal(InspectionStage.FaultSuspected, k.Session.State.Stage);
        k.Tick(); k.RecordProbe(k.Probe(false));
        Assert.Equal(InspectionStage.FaultConfirmed, k.Session.State.Stage);
        k.Session.DraftWorkOrder("wo", "reseat", k.Now);
        Assert.Equal(InspectionStage.AwaitingRepair, k.Session.State.Stage);
        k.Tick(); k.Session.ClaimRepair("c1", "claim-1", "reseated", k.Now);
        Assert.Equal(InspectionStage.ReadyToVerify, k.Session.State.Stage);

        // fake fix
        k.PostRepairEvidence(); k.Tick(); k.RecordProbe(k.Probe(false));
        Assert.Equal(VerificationResult.Denied, k.Verify().Result);
        Assert.Equal(InspectionStage.VerificationFailed, k.Session.State.Stage);

        // real fix: new claim invalidates the earlier post-repair evidence
        k.Tick(); k.Session.ClaimRepair("c2", "claim-2", "moved cable to P2", k.Now);
        Assert.False(k.Session.State.TechnicianAcknowledged);
        Assert.Empty(k.Session.State.Checklist);
        Assert.Equal(VerificationResult.Denied, k.Verify("v-early").Result);    // nothing fresh yet
        k.PostRepairEvidence(); k.Tick(); k.RecordProbe(k.Probe(true));
        var d = k.Verify();
        Assert.Equal(VerificationResult.Verified, d.Result);
        Assert.Equal(VerificationOutcomeKind.VerifiedCompleteSimulated, d.OutcomeKind);
        Assert.Equal(InspectionStage.VerifiedComplete, k.Session.State.Stage);
        Assert.Equal(VerificationPolicyEvaluator.PolicyVersion, d.PolicyVersion);
        Assert.Equal(k.Twin.Sha256, d.FixtureDigest);

        // append-only, strictly increasing, replayable
        var seqs = k.Session.Events.Select(e => e.Sequence).ToList();
        Assert.Equal(Enumerable.Range(1, seqs.Count).Select(i => (long)i), seqs);
        var replayed = InspectionState.Replay(k.Session.Events);
        Assert.Equal(k.Session.State.Stage, replayed.Stage);
        Assert.Equal(k.Session.State.LastDecision, replayed.LastDecision);
        var rehydrated = InspectionSession.Rehydrate(k.Twin, k.Session.Events);
        Assert.Equal(InspectionStage.VerifiedComplete, rehydrated.State.Stage);
        Assert.Throws<InspectionConflictException>(() => rehydrated.RecordPhoto("late", "photo-late", EvidenceOrigin.Phone, "x", k.Now, 1, 1, k.Now));
    }

    [Fact]
    public void Predictions_are_scored_by_core_and_never_change_the_decision()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim(); k.PostRepairEvidence();
        k.Session.RegisterPrediction("rp", "pred-1", "NODE-A-HEALTH", "Reachable", ["LAB-HEALTH-CHECK-01"], k.Now);
        k.Tick(); k.RecordProbe(k.Probe(false));
        var scored = Assert.Single(k.Session.State.PredictionScores);
        Assert.Equal(PredictionOutcome.Falsified, scored.Outcome);
        Assert.Equal(VerificationResult.Denied, k.Verify().Result);          // measurement decides, not the prediction

        k.Tick(); k.Session.ClaimRepair("c2", "claim-2", "fixed", k.Now); k.PostRepairEvidence();
        k.Session.RegisterPrediction("rp2", "pred-2", "NODE-A-HEALTH", "Reachable", [], k.Now);
        k.Tick(); k.RecordProbe(k.Probe(true));
        Assert.Equal(PredictionOutcome.Confirmed, k.Session.State.PredictionScores.Last().Outcome);
        Assert.Equal(VerificationResult.Verified, k.Verify().Result);
        Assert.Equal(InspectionErrorCodes.ProbeNotAllowlisted,
            Assert.Throws<InspectionConflictException>(() => k.Session.RegisterPrediction("rp3", "pred-3", "GHOST", "Reachable", [], k.Now)).Code);
    }

    [Fact]
    public void Unknown_asset_and_broken_twin_cannot_create_a_session()
    {
        var twin = InspectionTestKit.LoadTwin();
        Assert.Equal(InspectionErrorCodes.UnknownAsset, Assert.Throws<InspectionConflictException>(() => InspectionSession.Create("I", twin, "LAB-SW-99", InspectionTestKit.T0)).Code);
        var broken = twin with { Twin = twin.Twin with { Connections = [twin.Twin.Connections[0] with { FromAssetId = "GHOST" }] } };
        Assert.Equal(InspectionErrorCodes.FixtureMismatch, Assert.Throws<InspectionConflictException>(() => InspectionSession.Create("I", broken, "LAB-SW-01", InspectionTestKit.T0)).Code);
    }

    [Fact]
    public void Duplicate_commands_replay_their_outcome_and_a_declined_view_is_inconclusive()
    {
        var k = new InspectionTestKit();
        var p = k.Photo();
        var first = k.Session.RecordAssessment("same-request", "a1", k.Assessment(ProposedNextAction.RequestView, ObservationStatus.Unknown, p), k.Now);
        var count = k.Session.Events.Count;
        var again = k.Session.RecordAssessment("same-request", "a1", k.Assessment(ProposedNextAction.RequestView, ObservationStatus.Unknown, p), k.Now);
        Assert.Same(first, again);
        Assert.Equal(count, k.Session.Events.Count);
        k.Session.DeclineView("decline", k.Now);
        Assert.Equal(InspectionStage.Inconclusive, k.Session.State.Stage);
        Assert.Equal(InspectionErrorCodes.InvalidState, Assert.Throws<InspectionConflictException>(() => k.Photo()).Code);
    }
}
