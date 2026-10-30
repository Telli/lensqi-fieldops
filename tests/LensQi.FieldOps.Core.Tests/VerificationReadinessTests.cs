using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Tests;

/// <summary>Readiness reports the server's own gate: the policy's first blocker stays the decision, every blocker is
/// listed, and the next action follows the order the work is done in.</summary>
public class VerificationReadinessTests
{
    private static VerifyReadiness Of(InspectionTestKit k) => VerificationReadiness.Of(k.Session.State, k.Twin);

    [Fact]
    public void The_next_action_walks_photo_review_acknowledge_then_verify()
    {
        var k = new InspectionTestKit();
        Assert.Equal(NextVerifyAction.None, Of(k).Next);                   // no claim yet: still diagnosing

        k.ReachRepairClaim();
        var r = Of(k);
        Assert.Equal(NextVerifyAction.CapturePhoto, r.Next);
        // The policy checks the acknowledgement first and that stays the decision, but all blockers are listed.
        Assert.Equal(VerificationPolicyEvaluator.Prerequisites(k.Session.State, k.Twin)!.Reasons[0], r.Blocking[0]);
        Assert.Contains(r.Blocking, b => b.Contains("post-repair image"));
        Assert.Contains(r.Blocking, b => b.Contains("has not been reviewed"));

        k.Tick();
        k.Session.RecordPhoto("rp-a", "photo-a", EvidenceOrigin.Phone, "sha-a", k.Now, 1600, 1000, k.Now);
        Assert.Equal(NextVerifyAction.WaitForAnalysis, Of(k).Next);
        k.Analyse("photo-a");
        Assert.Equal(NextVerifyAction.ReviewChecklist, Of(k).Next);

        foreach (var req in k.Twin.Twin.MandatoryRequirements)
            k.Session.ReviewChecklist("rv-" + req.RequirementId, req.RequirementId, ChecklistStatus.Satisfied, "photo-a", k.Now);
        Assert.Equal(NextVerifyAction.Acknowledge, Of(k).Next);

        k.Session.Acknowledge("ack-a", k.Now);
        r = Of(k);
        Assert.True(r.Ready);
        Assert.Equal(NextVerifyAction.Verify, r.Next);
        Assert.Empty(r.Blocking);
        Assert.Null(VerificationPolicyEvaluator.Prerequisites(k.Session.State, k.Twin));
    }

    [Fact]
    public void An_unknown_review_held_by_verify_asks_for_a_new_photo_and_a_failed_check_asks_for_a_new_claim()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim();
        var photo = k.PostRepairEvidence();
        var first = k.Twin.Twin.MandatoryRequirements.First().RequirementId;
        k.Session.ReviewChecklist("unknown", first, ChecklistStatus.Unknown, photo, k.Now);
        Assert.Equal(NextVerifyAction.ReviewChecklist, Of(k).Next);
        k.Session.Acknowledge("ack-u", k.Now);
        Assert.Equal(VerificationResult.NeedsHumanReview, k.Verify().Result);

        // The decision sent the claim back: correcting the review alone does not reopen it, a new photo does.
        k.Session.ReviewChecklist("corrected", first, ChecklistStatus.Satisfied, photo, k.Now);
        var r = Of(k);
        Assert.Equal(NextVerifyAction.CapturePhoto, r.Next);
        Assert.Contains("remains unknown", r.Blocking[0]);

        k.Tick();
        var fresh = k.Photo();
        foreach (var req in k.Twin.Twin.MandatoryRequirements)
            k.Session.ReviewChecklist("again-" + req.RequirementId, req.RequirementId, ChecklistStatus.Satisfied, fresh, k.Now);
        k.Session.Acknowledge("ack-v", k.Now);
        Assert.True(Of(k).Ready);

        k.Tick(); k.RecordProbe(k.Probe(false));
        Assert.Equal(VerificationResult.Denied, k.Verify().Result);
        Assert.Equal(NextVerifyAction.ClaimRepair, Of(k).Next);
    }

    [Fact]
    public void A_closed_inspection_has_nothing_next()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim(); k.PostRepairEvidence();
        k.Tick(); k.RecordProbe(k.Probe(true));
        Assert.Equal(VerificationResult.Verified, k.Verify().Result);
        var r = Of(k);
        Assert.False(r.Ready);
        Assert.Equal(NextVerifyAction.None, r.Next);
        Assert.Contains("closed", r.Blocking[0]);
    }
}
