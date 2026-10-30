using System.Text.Json;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Tests;

/// <summary>VK-T06/T25: proposals and rejections are on the inspection log; predictions are registered before the probe and scored by Core.</summary>
public class KnowledgeProposalRecordTests
{
    private static VendorGroundedProposal ProbeProposal(string predicted = "Reachable") =>
        new("LAB-SW-01", ["LAB-HEALTH-CHECK-01"], "The endpoint should answer once the cable is reseated.", null, "NODE-A-HEALTH",
            ProposedNextAction.RunApprovedProbe, new Prediction("NODE-A-HEALTH", predicted, ["LAB-HEALTH-CHECK-01"], "cable reseated"));

    private static IReadOnlyList<InspectionEvent> Propose(InspectionTestKit k, string key, VendorGroundedProposal? proposal, IReadOnlyList<ProposalRejection>? rejections = null, string? attempted = null) =>
        k.Session.RecordKnowledgeProposal(key, "prop-" + key, "fake-muse", "prompt-digest", "response-digest", proposal, attempted, rejections ?? [], "pred-" + key, k.Now);

    [Fact]
    public void An_accepted_probe_proposal_registers_its_prediction_in_the_same_batch_and_survives_a_restart()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim(); k.PostRepairEvidence();
        var stage = k.Session.State.Stage;

        var events = Propose(k, "kp1", ProbeProposal());
        Assert.Equal(["KnowledgeProposalAccepted", "PredictionRegistered"], events.Select(e => e.GetType().Name));
        var registered = (PredictionRegistered)events[1];
        Assert.Equal("prop-kp1", registered.ProposalId);
        Assert.Equal(64, registered.PredictionDigest!.Length);
        Assert.Equal(stage, k.Session.State.Stage);                       // the model never moves the stage
        var count = k.Session.Events.Count;
        Assert.Same(events, Propose(k, "kp1", ProbeProposal()));          // a retried key appends nothing
        Assert.Equal(count, k.Session.Events.Count);

        var json = k.Session.Events.Select(e => JsonSerializer.Serialize(e, KnowledgeJson.Options)).ToList();
        var back = InspectionSession.Rehydrate(k.Twin, json.Select(l => JsonSerializer.Deserialize<InspectionEvent>(l, KnowledgeJson.Options)!));
        Assert.Equal(json, back.Events.Select(e => JsonSerializer.Serialize(e, KnowledgeJson.Options)));
        Assert.Equal("pred-kp1", Assert.Single(back.State.PendingPredictions).PredictionId);
        Assert.NotNull(back.Recorded("kp1"));

        k.Tick(); k.RecordProbe(k.Probe(false));
        var scored = Assert.Single(k.Session.State.PredictionScores);
        Assert.Equal(("pred-kp1", PredictionOutcome.Falsified), (scored.PredictionId, scored.Outcome));
    }

    [Fact]
    public void Rejected_and_late_proposals_are_recorded_and_register_nothing()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim();
        var stage = k.Session.State.Stage;

        var rejected = Assert.Single(Propose(k, "kp1", null,
            [new(ProposalErrorCodes.ProbeNotAllowlisted, new string('x', 5000), ["POST /rest/system/reboot", .. Enumerable.Repeat(new string('y', 500), 20)])], "RunApprovedProbe"));
        var r = Assert.IsType<KnowledgeProposalRejected>(rejected);
        Assert.Equal("RunApprovedProbe", r.NextAction);
        Assert.Equal(300, r.Rejections[0].Message.Length);                // model text in a rejection is bounded
        Assert.Equal(10, r.Rejections[0].OffendingIds.Count);
        Assert.All(r.Rejections[0].OffendingIds, id => Assert.True(id.Length <= 120));
        Assert.Equal(stage, k.Session.State.Stage);

        // After the probe reports for this claim, its outcome is known: a prediction now is late, from a model or a client.
        k.Tick(); k.RecordProbe(k.Probe(false));
        var late = Assert.IsType<KnowledgeProposalRejected>(Assert.Single(Propose(k, "kp2", ProbeProposal())));
        Assert.Equal(InspectionErrorCodes.PredictionLate, Assert.Single(late.Rejections).Code);
        Assert.Equal(InspectionErrorCodes.PredictionLate,
            Assert.Throws<InspectionConflictException>(() => k.Session.RegisterPrediction("rp", "pred-x", "NODE-A-HEALTH", "Reachable", [], k.Now)).Code);
        Assert.DoesNotContain(k.Session.Events, e => e is PredictionRegistered);

        // A proposal that is not about a probe is accepted without a prediction.
        var view = new VendorGroundedProposal("LAB-SW-01", ["LAB-PORT-MAP-01"], null, "Show the P2 label", null, ProposedNextAction.RequestView, null);
        Assert.IsType<KnowledgeProposalAccepted>(Assert.Single(Propose(k, "kp3", view)));
    }

    [Fact]
    public void State_transitions_and_decisions_are_identical_with_and_without_predictions()
    {
        static (string Stages, string Decisions, int Scored) Run(bool predict)
        {
            var k = new InspectionTestKit();
            k.ReachRepairClaim(); k.PostRepairEvidence();
            if (predict) Propose(k, "kp1", ProbeProposal());
            k.Tick(); k.RecordProbe(k.Probe(false));
            var denied = k.Verify();
            k.Tick(); k.Session.ClaimRepair("c2", "claim-2", "Moved the cable to P2", k.Now); k.PostRepairEvidence();
            if (predict) Propose(k, "kp2", ProbeProposal());
            k.Tick(); k.RecordProbe(k.Probe(true));
            var verified = k.Verify();
            Assert.Equal((VerificationResult.Denied, VerificationResult.Verified), (denied.Result, verified.Result));
            var stages = k.Session.Events.OfType<StageChanged>().Select(s => new { s.From, s.To, s.Reason, s.OccurredAt });
            return (JsonSerializer.Serialize(stages), JsonSerializer.Serialize(new[] { denied, verified }), k.Session.State.PredictionScores.Count);
        }

        var with = Run(predict: true);
        var without = Run(predict: false);
        Assert.Equal(without.Stages, with.Stages);
        Assert.Equal(without.Decisions, with.Decisions);
        Assert.Equal((2, 0), (with.Scored, without.Scored));
    }

    [Fact]
    public void Core_scores_by_ordinal_equality_and_a_probe_without_a_measurement_is_unscorable()
    {
        var k = new InspectionTestKit();
        Assert.Equal(PredictionOutcome.Confirmed, PredictionScorer.Score("Reachable", k.Probe(true)));
        Assert.Equal(PredictionOutcome.Falsified, PredictionScorer.Score("reachable", k.Probe(true)));
        Assert.Equal(PredictionOutcome.Falsified, PredictionScorer.Score("Reachable", k.Probe(false)));
        Assert.Equal(PredictionOutcome.Unscorable, PredictionScorer.Score("Reachable", k.Probe(null)));
    }
}
