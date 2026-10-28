using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Tests;

/// <summary>The ten required negative tests from PRD v1.1 §17, plus the view budget and freshness window.</summary>
public class InspectionNegativeTests
{
    [Fact]
    public void N01_Visually_plausible_connection_with_failed_probe_is_verification_failed()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim();
        k.PostRepairEvidence();                       // the photo "looks" repaired and the technician says so
        k.Tick(); k.RecordProbe(k.Probe(false));      // remote endpoint still unreachable
        var d = k.Verify();
        Assert.Equal(VerificationResult.Denied, d.Result);
        var reason = Assert.Single(d.Reasons);
        Assert.StartsWith(VerificationPolicyEvaluator.DiagnosticFailedPrefix, reason);
        Assert.Contains("NODE-A-HEALTH measured Unreachable (Simulated probe)", reason);
        Assert.EndsWith("Still required: “The approved lab endpoint returned HTTP 200 at test time (not physical port identity).”", reason);
        Assert.DoesNotContain("not satisfied", reason);
        Assert.Equal(InspectionStage.VerificationFailed, k.Session.State.Stage);
        Assert.Null(d.OutcomeKind);
    }

    [Fact]
    public void N02_Passed_pre_repair_probe_is_stale_after_the_claim()
    {
        var k = new InspectionTestKit();
        var p = k.Photo();
        k.Assess(ProposedNextAction.RunApprovedProbe, ObservationStatus.Suspected, p, null);
        k.Tick(); k.RecordProbe(k.Probe(true));       // passing probe BEFORE any repair claim
        k.Tick(); k.RecordProbe(k.Probe(false));      // then it fails → fault confirmed
        k.Tick(); k.Session.DraftWorkOrder("r-wo", "fix", k.Now);
        k.Tick(); k.Session.ClaimRepair("r-claim", "c1", "fixed", k.Now);
        k.PostRepairEvidence();
        var d = k.Verify();                           // no diagnostic after the claim
        Assert.Equal(VerificationResult.Denied, d.Result);
        Assert.Contains(d.Reasons, r => r.Contains("Fresh post-repair diagnostic"));
        Assert.NotEqual(InspectionStage.VerifiedComplete, k.Session.State.Stage);
    }

    [Fact]
    public void N03_Health_check_for_the_wrong_fixture_is_rejected()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim();
        k.PostRepairEvidence();
        k.Tick();
        var ex = Assert.Throws<InspectionConflictException>(() => k.RecordProbe(k.Probe(true, fixture: "LAB-RACK-02")));
        Assert.Equal(InspectionErrorCodes.FixtureMismatch, ex.Code);
        Assert.Equal(VerificationResult.Denied, k.Verify().Result);
    }

    [Fact]
    public void N04_Simulated_or_replay_probe_never_yields_live_verification()
    {
        foreach (var source in new[] { DiagnosticSource.Simulated, DiagnosticSource.RecordedReplay })
        {
            var k = new InspectionTestKit();
            k.ReachRepairClaim(); k.PostRepairEvidence();
            k.Tick(); k.RecordProbe(k.Probe(true, source));
            var d = k.Verify();
            Assert.Equal(VerificationResult.Verified, d.Result);
            Assert.Equal(VerificationOutcomeKind.VerifiedCompleteSimulated, d.OutcomeKind);
            Assert.NotEqual(VerificationOutcomeKind.VerifiedCompleteLive, d.OutcomeKind);
            Assert.Contains(d.Reasons, r => r.Contains("not a live measurement"));
        }
    }

    [Fact]
    public void N05_Missing_sam_mask_requests_more_evidence_and_never_fabricates_a_mask()
    {
        var k = new InspectionTestKit();
        var p = k.Photo();
        Assert.Equal(InspectionStage.InitialScan, k.Session.State.Stage);
        k.Session.RecordSegmentation("r-seg", "seg-1", p, "yellow ethernet cable", matched: false, maskSha256: null, "sam-3.1", k.Now);
        Assert.Equal(InspectionStage.NeedsEvidence, k.Session.State.Stage);
        var ex = Assert.Throws<InspectionConflictException>(() =>
            k.Session.RecordSegmentation("r-seg2", "seg-2", p, "yellow ethernet cable", matched: false, maskSha256: "deadbeef", "sam-3.1", k.Now));
        Assert.Equal(InspectionErrorCodes.InvalidModelOutput, ex.Code);
        Assert.DoesNotContain(k.Session.Events, e => e is SegmentationRecorded { EvidenceId: "seg-2" });
    }

    [Fact]
    public void N06_Invalid_model_output_or_asset_id_is_rejected_without_state_change()
    {
        var k = new InspectionTestKit();
        var p = k.Photo();
        var before = (k.Session.State.Stage, k.Session.Events.Count);

        var wrongAsset = k.Assessment(ProposedNextAction.RequestView, ObservationStatus.Unknown, p) with { AssetId = "LAB-SW-99" };
        Assert.Equal(InspectionErrorCodes.InvalidModelOutput, Assert.Throws<InspectionConflictException>(() => k.Session.RecordAssessment("r1", "a1", wrongAsset, k.Now)).Code);

        var unknownEvidence = new VisualAssessment("LAB-SW-01", [new Hypothesis("f", ObservationStatus.Unknown, "x", ["photo-999"])], ProposedNextAction.RequestView, "closer", null, "m", "1.0");
        Assert.Equal(InspectionErrorCodes.InvalidModelOutput, Assert.Throws<InspectionConflictException>(() => k.Session.RecordAssessment("r2", "a2", unknownEvidence, k.Now)).Code);

        var badAction = k.Assessment((ProposedNextAction)42, ObservationStatus.Unknown, p);
        Assert.Equal(InspectionErrorCodes.InvalidModelOutput, Assert.Throws<InspectionConflictException>(() => k.Session.RecordAssessment("r3", "a3", badAction, k.Now)).Code);

        Assert.Equal(before, (k.Session.State.Stage, k.Session.Events.Count));
    }

    [Theory]
    [InlineData("http://10.0.0.1/health")]
    [InlineData("NODE-B-HEALTH")]
    [InlineData("POST /rest/system/reboot")]
    public void N07_Probe_target_not_in_fixture_allowlist_is_denied(string diagnosticId)
    {
        var k = new InspectionTestKit();
        var p = k.Photo(); k.Assess(ProposedNextAction.RunApprovedProbe, ObservationStatus.Suspected, p, null);
        var result = k.Probe(true) with { DiagnosticId = diagnosticId };
        var ex = Assert.Throws<InspectionConflictException>(() => k.RecordProbe(result));
        Assert.Equal(InspectionErrorCodes.ProbeNotAllowlisted, ex.Code);
        Assert.Contains("no probe was run", ex.Message);

        // Even the right ID with a tampered alias is refused.
        var aliasSwap = k.Probe(true) with { TargetAlias = "attacker-host" };
        Assert.Equal(InspectionErrorCodes.ProbeNotAllowlisted, Assert.Throws<InspectionConflictException>(() => k.RecordProbe(aliasSwap)).Code);
        Assert.Empty(k.Session.State.Diagnostics);
    }

    [Fact]
    public void N08_Duplicate_verify_is_idempotent()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim(); k.PostRepairEvidence();
        k.Tick(); k.RecordProbe(k.Probe(true));
        var first = k.Verify("verify-abc");
        var count = k.Session.Events.Count;
        var second = k.Verify("verify-abc");
        Assert.Equal(first, second);
        Assert.Equal(count, k.Session.Events.Count);
        Assert.Single(k.Session.Events.OfType<VerificationEvaluated>());
    }

    [Fact]
    public void N09_Readable_image_without_telemetry_is_not_verified()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim();
        k.PostRepairEvidence();                       // clear photo, checklist satisfied, acknowledged
        var d = k.Verify();                           // but no post-repair diagnostic at all
        Assert.Equal(VerificationResult.Denied, d.Result);
        Assert.Contains(d.Reasons, r => r.Contains("Fresh post-repair diagnostic"));
        Assert.Equal(InspectionStage.NeedsEvidence, k.Session.State.Stage);
    }

    [Fact]
    public void N10_Passing_check_verifies_only_the_declared_claim_never_the_port_mapping()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim(); k.PostRepairEvidence();
        k.Tick(); k.RecordProbe(k.Probe(true, DiagnosticSource.LiveLocal));
        var d = k.Verify();
        Assert.Equal(VerificationOutcomeKind.VerifiedCompleteLive, d.OutcomeKind);
        var claim = Assert.Single(d.VerifiedClaims);
        Assert.Contains("HTTP 200", claim);
        Assert.Contains("not physical port identity", claim);
        Assert.DoesNotContain(d.VerifiedClaims, c => c.Contains("P2"));
    }

    [Fact]
    public void View_budget_of_two_then_inconclusive()
    {
        var k = new InspectionTestKit();
        var p = k.Photo();
        k.Assess(ProposedNextAction.RequestView, ObservationStatus.Unknown, p);
        Assert.Equal(InspectionStage.NeedsEvidence, k.Session.State.Stage);
        var p2 = k.Photo(); k.Assess(ProposedNextAction.RequestView, ObservationStatus.Unknown, p2);
        var p3 = k.Photo(); k.Assess(ProposedNextAction.RequestView, ObservationStatus.Unknown, p3);
        Assert.Equal(InspectionStage.Inconclusive, k.Session.State.Stage);
        Assert.Equal(2, k.Session.State.ViewRequests);
    }

    [Fact]
    public void Diagnostic_older_than_policy_window_is_denied()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim(); k.PostRepairEvidence();
        k.Tick(); k.RecordProbe(k.Probe(true));
        k.Tick(120);                                   // policy allows 60 s
        var d = k.Verify();
        Assert.Equal(VerificationResult.Denied, d.Result);
        Assert.Contains(d.Reasons, r => r.Contains("older than 60s"));
    }

    [Fact]
    public void Unreviewed_or_conflicting_checklist_needs_human_review()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim();
        k.Tick(); var p = k.Photo();
        k.Session.Acknowledge("r-ack", k.Now);
        k.Tick(); k.RecordProbe(k.Probe(true));
        Assert.Equal(VerificationResult.NeedsHumanReview, k.Verify().Result);
        // after review marks one item conflicting the gate still refuses
        k.Session.RecordPhoto("r-p", "photo-x", EvidenceOrigin.Phone, "abc", k.Now, 10, 10, k.Now);
        foreach (var req in k.Twin.Twin.MandatoryRequirements)
            k.Session.ReviewChecklist($"r-{req.RequirementId}", req.RequirementId, req.RequirementId == "yellow_cable_near_end_visible" ? ChecklistStatus.Conflicting : ChecklistStatus.Satisfied, p, k.Now);
        k.Tick(); k.RecordProbe(k.Probe(true));
        Assert.Equal(VerificationResult.NeedsHumanReview, k.Verify().Result);
    }
}
