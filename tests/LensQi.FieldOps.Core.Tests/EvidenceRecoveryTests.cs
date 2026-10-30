using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Tests;

/// <summary>Policy 1.4: a newer photo still being analysed holds the decision, its analysis restores readiness, and new
/// evidence needs a fresh acknowledgement. Both arrival orders end in the same place.</summary>
public class EvidenceRecoveryTests
{
    private static (InspectionTestKit Kit, string Newer) AcceptedPhotoThenNewerPending()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim(); k.PostRepairEvidence();                       // photo A analysed, reviewed and acknowledged
        Assert.True(k.Session.State.TechnicianAcknowledged);
        k.Tick();
        k.Session.RecordPhoto("rp-b", "photo-b", EvidenceOrigin.Phone, "sha-b", k.Now, 1600, 1000, k.Now);   // B: analysis not recorded yet
        return (k, "photo-b");
    }

    [Fact]
    public void A_newer_photo_awaiting_analysis_holds_the_decision_and_spends_no_probe_on_it()
    {
        var (k, newer) = AcceptedPhotoThenNewerPending();
        Assert.False(k.Session.State.TechnicianAcknowledged);                // new evidence: acknowledge again
        k.Session.Acknowledge("ack-b", k.Now);
        Assert.Contains(newer, VerificationPolicyEvaluator.Prerequisites(k.Session.State, k.Twin)!.Reasons[0]);
        k.Tick(); k.RecordProbe(k.Probe(true));
        var held = k.Verify();
        Assert.Equal(VerificationResult.NeedsHumanReview, held.Result);
        Assert.Contains("still being compared", held.Reasons[0]);
        Assert.Equal(InspectionStage.NeedsEvidence, k.Session.State.Stage);

        // The analysis arrives and accepts B: the claim is ready again without another photo, and verification completes.
        k.Analyse(newer);
        Assert.Equal(InspectionStage.ReadyToVerify, k.Session.State.Stage);
        Assert.Equal(VerificationResult.Verified, k.Verify().Result);
    }

    [Fact]
    public void A_newer_photo_rejected_after_a_held_decision_never_completes_the_inspection()
    {
        var (k, newer) = AcceptedPhotoThenNewerPending();
        k.Session.Acknowledge("ack-b", k.Now);
        k.Tick(); k.RecordProbe(k.Probe(true));
        Assert.Equal(VerificationResult.NeedsHumanReview, k.Verify().Result);

        var photo = k.Session.State.Photos.Single(p => p.EvidenceId == newer);
        k.Session.RecordVisualChange("rc-b", VisualChangeAnalyzer.Duplicate(k.Session.State.CurrentClaimId!, photo, k.Session.State.Photos[0].EvidenceId, 0, VisualChangeThresholds.Default), k.Now);
        Assert.Equal(InspectionStage.NeedsEvidence, k.Session.State.Stage);  // a rejection never restores readiness
        Assert.NotEqual(InspectionStage.VerifiedComplete, k.Session.State.Stage);
    }

    [Fact]
    public void A_changed_review_needs_a_fresh_acknowledgement()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim();
        var photo = k.PostRepairEvidence();
        Assert.True(k.Session.State.TechnicianAcknowledged);
        k.Session.ReviewChecklist("re-review", k.Twin.Twin.MandatoryRequirements.First().RequirementId, ChecklistStatus.Satisfied, photo, k.Now);
        Assert.False(k.Session.State.TechnicianAcknowledged);
        Assert.Contains("acknowledged", k.Verify().Reasons[0]);
    }
}
