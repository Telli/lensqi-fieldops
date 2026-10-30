using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Tests;

/// <summary>Child VK-R12 / VK-T26: deterministic change metrics, verdicts and their effect on the session.</summary>
public class VisualChangeTests
{
    private static readonly VisualChangeThresholds T = VisualChangeThresholds.Default;

    /// <summary>A w×h box whose filled sub-rectangle [fx0,fx1)×[fy0,fy1) is the object, as column-major COCO RLE.</summary>
    internal static PlacedMask Rect(int left, int top, int w, int h, int fx0, int fy0, int fx1, int fy1)
    {
        var runs = new List<int>(); var current = false; var run = 0;
        for (var x = 0; x < w; x++)
            for (var y = 0; y < h; y++)
            {
                var on = x >= fx0 && x < fx1 && y >= fy0 && y < fy1;
                if (on != current) { runs.Add(run); run = 0; current = on; }
                run++;
            }
        runs.Add(run);
        return new PlacedMask(left, top, w, h, CocoRle.Encode(runs));
    }

    private static PhotoEvidence Photo(string id, string sha, string? hash, int minute) =>
        new(id, EvidenceOrigin.Phone, sha, InspectionTestKit.T0.AddMinutes(minute), null, hash);

    [Fact]
    public void Rle_round_trips_and_decodes_column_major()
    {
        var runs = new[] { 1, 2, 3, 900, 17, 0, 64 };
        Assert.Equal(runs, CocoRle.Counts(CocoRle.Encode(runs)));
        // h=2, w=3: 1 zero, 2 ones, 3 zeros → column 0 = [0,1], column 1 = [1,0]
        Assert.Equal([false, true, false, true, false, false], CocoRle.Decode(CocoRle.Encode([1, 2, 3]), 2, 3));
    }

    [Fact]
    public void Hamming_distance_counts_differing_bits()
    {
        Assert.Equal(0, VisualChangeAnalyzer.HammingDistance("80808a65654cc8d8", "80808a65654cc8d8"));
        Assert.Equal(1, VisualChangeAnalyzer.HammingDistance("0000000000000000", "0000000000000001"));
        Assert.Equal(64, VisualChangeAnalyzer.HammingDistance("0000000000000000", "ffffffffffffffff"));
    }

    [Fact]
    public void Duplicates_are_found_by_byte_digest_first_then_by_perceptual_distance()
    {
        var before = Photo("photo-a", "sha-a", "00000000000000ff", 0);
        Assert.Equal(("photo-a", 0), VisualChangeAnalyzer.FindDuplicate(Photo("photo-b", "sha-a", null, 5), [before], T));
        Assert.Equal(("photo-a", 2), VisualChangeAnalyzer.FindDuplicate(Photo("photo-c", "sha-c", "00000000000000fc", 5), [before], T));
        Assert.Null(VisualChangeAnalyzer.FindDuplicate(Photo("photo-d", "sha-d", "000000000000000f", 5), [before], T));   // distance 4 > 3
        Assert.Null(VisualChangeAnalyzer.FindDuplicate(Photo("photo-e", "sha-e", null, 5), [before], T));                 // no hash, different bytes
    }

    [Fact]
    public void Same_target_mask_is_no_visual_change_and_a_moved_target_is_changed()
    {
        var reference = Photo("photo-pre", "sha-pre", "00000000000000ff", 0);
        var after = Photo("photo-post", "sha-post", "00000000000003ff", 5);   // dHash distance 2… but not compared for duplicates here
        var cable = Rect(300, 600, 80, 120, 10, 10, 60, 110);

        var same = VisualChangeAnalyzer.Compare("claim-1", reference, after, "yellow ethernet cable", cable, "m1", cable, "m1", T);
        Assert.Equal(VisualChangeVerdict.NoVisualChange, same.Verdict);
        Assert.Equal(1.0, same.TargetRegionIoU);
        Assert.Equal(0.0, same.ComponentShiftNormalized);
        Assert.True(same.RejectsPhoto);

        var moved = Rect(700, 600, 80, 120, 10, 10, 60, 110);   // same shape, 400 px to the right
        var changed = VisualChangeAnalyzer.Compare("claim-1", reference, after, "yellow ethernet cable", cable, "m1", moved, "m2", T);
        Assert.Equal(VisualChangeVerdict.Changed, changed.Verdict);
        Assert.Equal(0.0, changed.TargetRegionIoU);
        Assert.True(changed.ComponentShiftNormalized > 1);
        Assert.False(changed.RejectsPhoto);
        Assert.Equal("visual-change/1", changed.ThresholdsVersion);
    }

    [Fact]
    public void A_large_image_hash_distance_alone_counts_as_change_and_missing_or_tiny_masks_are_unscorable()
    {
        var reference = Photo("photo-pre", "sha-pre", "0000000000000000", 0);
        var far = Photo("photo-post", "sha-post", "0000000000000fff", 5);   // distance 12
        var cable = Rect(300, 600, 80, 120, 10, 10, 60, 110);
        Assert.Equal(VisualChangeVerdict.Changed, VisualChangeAnalyzer.Compare("c", reference, far, "cable", cable, null, cable, null, T).Verdict);
        Assert.Equal(VisualChangeVerdict.Unscorable, VisualChangeAnalyzer.Compare("c", reference, far, "cable", cable, null, null, null, T).Verdict);
        var speck = Rect(300, 600, 20, 20, 0, 0, 5, 5);   // 25 px < MinMaskPixels
        var tiny = VisualChangeAnalyzer.Compare("c", reference, far, "cable", speck, null, cable, null, T);
        Assert.Equal(VisualChangeVerdict.Unscorable, tiny.Verdict);
        Assert.False(tiny.RejectsPhoto);
    }

    /// <summary>A claim plus a post-claim photo whose change analysis is not recorded yet.</summary>
    private static (InspectionTestKit Kit, string PostPhoto) ClaimWithPostPhoto(string sha = "post-sha")
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim();
        k.Tick();
        k.Session.RecordPhoto("rp", "photo-post", EvidenceOrigin.Phone, sha, k.Now, 1600, 1000, k.Now, null, "00000000000000ff");
        return (k, "photo-post");
    }

    [Fact]
    public void Duplicate_returns_the_session_to_needs_evidence_locks_the_checklist_and_blocks_verification()
    {
        var (k, post) = ClaimWithPostPhoto();
        Assert.Equal(InspectionStage.ReadyToVerify, k.Session.State.Stage);
        var photo = k.Session.State.Photos.Single(p => p.EvidenceId == post);
        var dup = VisualChangeAnalyzer.Duplicate(k.Session.State.CurrentClaimId!, photo, k.Session.State.Photos[0].EvidenceId, 0, T);

        var events = k.Session.RecordVisualChange("rc", dup, k.Now);
        Assert.IsType<VisualChangeAnalyzed>(events[0]);
        Assert.Equal(InspectionStage.NeedsEvidence, k.Session.State.Stage);
        Assert.Null(k.Session.State.LatestPostRepairPhoto);
        var locked = Assert.Throws<InspectionConflictException>(() => k.Session.ReviewChecklist("rv", "switch_chassis_visible", ChecklistStatus.Satisfied, post, k.Now));
        Assert.Equal(InspectionErrorCodes.ChangeEvidenceRejected, locked.Code);
        Assert.Throws<InspectionConflictException>(() => k.Session.Verify("v", k.Now));

        // a genuinely new view brings the claim back to ReadyToVerify; replay reproduces everything
        k.Tick();
        k.Session.RecordPhoto("rp2", "photo-new", EvidenceOrigin.Phone, "new-sha", k.Now, 1600, 1000, k.Now, null, "ffffffffffffff00");
        Assert.Equal(InspectionStage.ReadyToVerify, k.Session.State.Stage);
        Assert.Null(k.Session.State.LatestPostRepairPhoto); // not evidence until its analysis is recorded
        k.Analyse("photo-new");
        Assert.Equal("photo-new", k.Session.State.LatestPostRepairPhoto!.EvidenceId);
        var replayed = InspectionState.Replay(k.Session.Events);
        Assert.Equal(k.Session.State.Stage, replayed.Stage);
        Assert.Equal(dup, replayed.ChangeAnalyses.First());
        Assert.True(replayed.IsRejectedForCurrentClaim(post));
    }

    [Fact]
    public void Changed_and_unscorable_never_advance_or_retreat_the_stage_and_a_new_claim_clears_rejections()
    {
        var (k, post) = ClaimWithPostPhoto();
        var photo = k.Session.State.Photos.Single(p => p.EvidenceId == post);
        k.Session.RecordVisualChange("u", VisualChangeAnalyzer.Unscorable(k.Session.State.CurrentClaimId!, photo, null, null, "no reference", T), k.Now);
        Assert.Equal(InspectionStage.ReadyToVerify, k.Session.State.Stage);
        Assert.Equal(post, k.Session.State.LatestPostRepairPhoto!.EvidenceId);

        var noChange = VisualChangeAnalyzer.Compare(k.Session.State.CurrentClaimId!, k.Session.State.Photos[0], photo, "cable",
            Rect(0, 0, 40, 40, 0, 0, 30, 30), "m", Rect(0, 0, 40, 40, 0, 0, 30, 30), "m", T);
        k.Session.RecordVisualChange("n", noChange, k.Now);
        Assert.Equal(InspectionStage.NeedsEvidence, k.Session.State.Stage);
        Assert.True(k.Session.State.IsRejectedForCurrentClaim(post));
    }

    [Fact]
    public void A_photo_awaiting_change_analysis_cannot_be_reviewed_or_verified()
    {
        var (k, post) = ClaimWithPostPhoto();
        Assert.True(k.Session.State.IsAwaitingAnalysis(post));
        Assert.Null(k.Session.State.LatestPostRepairPhoto);
        var pending = Assert.Throws<InspectionConflictException>(() => k.Session.ReviewChecklist("rv", "switch_chassis_visible", ChecklistStatus.Satisfied, post, k.Now));
        Assert.Equal(InspectionErrorCodes.ChangeAnalysisPending, pending.Code);
        k.Session.Acknowledge("ack", k.Now);
        k.Tick(); k.RecordProbe(k.Probe(true));
        var d = VerificationPolicyEvaluator.Evaluate(k.Session.State, k.Twin, k.Now);
        Assert.NotEqual(VerificationResult.Verified, d.Result);
        Assert.Contains("still being compared", d.Reasons[0]);
    }

    [Fact]
    public void Only_an_explicit_satisfied_review_meets_a_requirement()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim();
        k.Tick(); var p = k.Photo();
        Assert.Throws<InspectionConflictException>(() => k.Session.ReviewChecklist("rv", "switch_chassis_visible", (ChecklistStatus)999, p, k.Now));
        Assert.Empty(k.Session.State.Checklist);

        // An undefined value that reached the state anyway (an old log) still does not satisfy the policy.
        foreach (var req in k.Twin.Twin.MandatoryRequirements)
            k.Session.ReviewChecklist("rv-" + req.RequirementId, req.RequirementId, ChecklistStatus.Satisfied, p, k.Now);
        k.Session.Acknowledge("ack", k.Now);
        k.Tick(); k.RecordProbe(k.Probe(true));
        Assert.Equal(VerificationResult.Verified, VerificationPolicyEvaluator.Evaluate(k.Session.State, k.Twin, k.Now).Result);
        var forged = k.Session.State with { Checklist = k.Session.State.Checklist.ToDictionary(e => e.Key, e => e.Value with { Status = (ChecklistStatus)999 }) };
        Assert.Equal(VerificationResult.Denied, VerificationPolicyEvaluator.Evaluate(forged, k.Twin, k.Now).Result);
    }

    [Fact]
    public void Change_analysis_must_match_the_current_claim_and_a_post_claim_photo()
    {
        var (k, post) = ClaimWithPostPhoto();
        var photo = k.Session.State.Photos.Single(p => p.EvidenceId == post);
        Assert.Throws<InspectionConflictException>(() => k.Session.RecordVisualChange("x1", VisualChangeAnalyzer.Duplicate("claim-other", photo, "photo-001", 0, T), k.Now));
        var pre = k.Session.State.Photos[0];
        Assert.Throws<InspectionConflictException>(() => k.Session.RecordVisualChange("x2", VisualChangeAnalyzer.Duplicate(k.Session.State.CurrentClaimId!, pre, post, 0, T), k.Now));
        var forged = VisualChangeAnalyzer.Duplicate(k.Session.State.CurrentClaimId!, photo with { ArtifactSha256 = "other" }, pre.EvidenceId, 0, T);
        Assert.Throws<InspectionConflictException>(() => k.Session.RecordVisualChange("x3", forged, k.Now));
    }

    [Fact]
    public void Post_claim_photos_use_server_time_with_bounded_device_clock_skew()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim();
        var claimedAt = k.Session.State.RepairClaimedAt!.Value;
        k.Tick(5);
        // device clock truncated to the second (or a few seconds behind): still the post-repair photo
        k.Session.RecordPhoto("r1", "photo-skewed", EvidenceOrigin.Phone, "sha-1", claimedAt.AddSeconds(-3), 1600, 1000, k.Now, null, "0000000000000001");
        k.Analyse("photo-skewed");
        Assert.Equal("photo-skewed", k.Session.State.LatestPostRepairPhoto!.EvidenceId);
        Assert.Equal(k.Now, k.Session.State.Photos.Last().RecordedAt);

        // an old photo uploaded after the claim stays stale even though the server only just received it
        var stale = new InspectionTestKit();
        stale.ReachRepairClaim();
        var at = stale.Session.State.RepairClaimedAt!.Value;
        stale.Tick(5);
        stale.Session.RecordPhoto("r2", "photo-old", EvidenceOrigin.Phone, "sha-2", at.AddMinutes(-10), 1600, 1000, stale.Now, null, "0000000000000002");
        Assert.Null(stale.Session.State.LatestPostRepairPhoto);
        Assert.False(stale.Session.State.IsPostClaim(stale.Session.State.Photos.Last()));
        Assert.Equal(VerificationResult.Denied, VerificationPolicyEvaluator.Evaluate(stale.Session.State, stale.Twin, stale.Now).Result);
    }

    [Fact]
    public void Policy_denies_a_checklist_reviewed_against_a_photo_that_was_later_rejected()
    {
        var (k, post) = ClaimWithPostPhoto();
        k.Analyse(post);
        foreach (var req in k.Twin.Twin.MandatoryRequirements)
            k.Session.ReviewChecklist("rv-" + req.RequirementId, req.RequirementId, ChecklistStatus.Satisfied, post, k.Now);
        k.Session.Acknowledge("ack", k.Now);
        k.Tick();
        // a second, accepted post-claim photo exists, but the reviews still cite the rejected one
        k.Session.RecordPhoto("rp2", "photo-ok", EvidenceOrigin.Phone, "ok-sha", k.Now, 1600, 1000, k.Now, null, "ffffffffffffff00");
        k.Analyse("photo-ok");
        var photo = k.Session.State.Photos.Single(p => p.EvidenceId == post);
        k.Session.RecordVisualChange("rc", VisualChangeAnalyzer.Duplicate(k.Session.State.CurrentClaimId!, photo, k.Session.State.Photos[0].EvidenceId, 0, T), k.Now);
        k.Tick(); k.RecordProbe(k.Probe(true));
        var d = VerificationPolicyEvaluator.Evaluate(k.Session.State, k.Twin, k.Now);
        Assert.Equal(VerificationResult.Denied, d.Result);
        Assert.Contains("change analysis rejected", d.Reasons[0]);
        Assert.Equal(VerificationPolicyEvaluator.PolicyVersion, d.PolicyVersion);
    }
}
