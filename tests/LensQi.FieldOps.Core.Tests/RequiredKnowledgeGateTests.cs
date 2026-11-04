using System.Text.Json;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Tests;

/// <summary>VK-R08 / VK-T10: documentation the site requires adds a gate and never replaces one.</summary>
public class RequiredKnowledgeGateTests
{
    private static readonly string[] Required = ["LAB-PORT-MAP-01", "LAB-HEALTH-CHECK-01"];
    private static RequiredKnowledgeCheck Confirmed => new(KnowledgeResolutionStatus.Applicable, "pack", "digest", 3, Required, []);
    private static RequiredKnowledgeCheck Unconfirmed => new(KnowledgeResolutionStatus.ApplicabilityUnverified, "pack", "digest", 3, Required, Required);

    private static InspectionTestKit ReadyWithProbe(bool passed)
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim();
        k.PostRepairEvidence();
        k.Tick(); k.RecordProbe(k.Probe(passed));
        return k;
    }

    [Fact]
    public void Unconfirmed_documentation_holds_an_otherwise_passing_inspection_for_review()
    {
        var k = ReadyWithProbe(true);
        var d = k.Verify(knowledge: Unconfirmed);
        Assert.Equal(VerificationResult.NeedsHumanReview, d.Result);
        Assert.Null(d.OutcomeKind);
        Assert.Empty(d.VerifiedClaims);
        Assert.StartsWith(VerificationPolicyEvaluator.KnowledgeUnconfirmedPrefix, d.Reasons[0]);
        Assert.Contains("LAB-PORT-MAP-01", d.Reasons[0]);
        Assert.Equal(InspectionStage.NeedsEvidence, k.Session.State.Stage);
        Assert.Equal(Unconfirmed.UnconfirmedFactIds, k.Session.State.LastDecision!.RequiredKnowledge!.UnconfirmedFactIds);
    }

    [Fact]
    public void A_failing_probe_still_decides_verification_failed_whatever_the_documentation_says()
    {
        foreach (var knowledge in new[] { Confirmed, Unconfirmed })
        {
            var k = ReadyWithProbe(false);
            Assert.Equal(VerificationResult.Denied, k.Verify(knowledge: knowledge).Result);
            Assert.Equal(InspectionStage.VerificationFailed, k.Session.State.Stage);
        }
    }

    [Fact]
    public void Confirmed_documentation_never_replaces_a_missing_gate()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim();
        k.Tick(); k.RecordProbe(k.Probe(true));   // no photo, review or acknowledgement
        Assert.NotEqual(VerificationResult.Verified, k.Verify(knowledge: Confirmed).Result);
    }

    [Fact]
    public void A_verified_decision_records_the_pack_it_rested_on_and_survives_replay_through_json()
    {
        var k = ReadyWithProbe(true);
        var d = k.Verify(knowledge: Confirmed);
        Assert.Equal(VerificationOutcomeKind.VerifiedCompleteSimulated, d.OutcomeKind);
        Assert.Equal("digest", d.RequiredKnowledge!.PackSha256);

        var json = k.Session.Events.Select(e => JsonSerializer.Serialize(e, KnowledgeJson.Options)).ToList();
        var back = InspectionSession.Rehydrate(k.Twin, json.Select(j => JsonSerializer.Deserialize<InspectionEvent>(j, KnowledgeJson.Options)!));
        Assert.Equal(3, back.State.LastDecision!.RequiredKnowledge!.PackRevision);
        Assert.Equal(InspectionStage.VerifiedComplete, back.State.Stage);
    }

    [Fact]
    public void A_decision_without_a_requirement_serializes_as_it_always_did()
    {
        var k = ReadyWithProbe(true);
        k.Verify();
        var evaluated = k.Session.Events.OfType<VerificationEvaluated>().Single();
        Assert.DoesNotContain("requiredKnowledge", JsonSerializer.Serialize<InspectionEvent>(evaluated, KnowledgeJson.Options));
    }

    [Fact]
    public void The_shipped_binding_is_confirmed_and_a_pack_for_another_revision_is_not()
    {
        var binding = TestPacks.SyntheticBinding;
        var shipped = RequiredKnowledgeCheck.For([binding], new KnowledgeResolver(TestPacks.Store(TestPacks.Synthetic)), binding.FixtureId, binding.AssetId)!;
        Assert.True(shipped.Confirmed);
        Assert.Equal(binding.VendorKnowledge.RequiredKnowledgeFactIds, shipped.RequiredFactIds);

        var other = TestPacks.Synthetic with { Identity = TestPacks.Synthetic.Identity with { HardwareRevisions = ["R2"] } };
        var check = RequiredKnowledgeCheck.For([binding], new KnowledgeResolver(TestPacks.Store(other)), binding.FixtureId, binding.AssetId)!;
        Assert.False(check.Confirmed);
        Assert.Equal(KnowledgeResolutionStatus.ApplicabilityUnverified, check.Status);
        Assert.Equal(binding.VendorKnowledge.RequiredKnowledgeFactIds, check.UnconfirmedFactIds);
    }

    [Fact]
    public void Required_facts_beyond_the_fact_card_limit_are_still_judged()
    {
        var binding = TestPacks.SyntheticBinding;
        Assert.True(binding.VendorKnowledge.RequiredKnowledgeFactIds.Count > 1);
        var resolver = new KnowledgeResolver(TestPacks.Store(TestPacks.Synthetic), new KnowledgeResolverOptions { MaxFacts = 1 });
        Assert.Single(resolver.Resolve(binding, binding.ToAssetIdentity(), []).Facts);
        Assert.True(RequiredKnowledgeCheck.For([binding], resolver, binding.FixtureId, binding.AssetId)!.Confirmed);
    }

    [Fact]
    public void A_required_fact_excluded_for_this_firmware_is_unconfirmed()
    {
        var binding = TestPacks.MikroTikBinding("6", "MK-REST-GET-INTERFACE");
        var check = RequiredKnowledgeCheck.For([binding], new KnowledgeResolver(TestPacks.Store(TestPacks.MikroTik)), binding.FixtureId, binding.AssetId)!;
        Assert.Contains("MK-REST-GET-INTERFACE", check.UnconfirmedFactIds);
    }

    [Fact]
    public void No_binding_or_no_required_facts_asks_nothing()
    {
        var resolver = new KnowledgeResolver(TestPacks.Store(TestPacks.Synthetic));
        Assert.Null(RequiredKnowledgeCheck.For([], resolver, "LAB-RACK-01", "LAB-SW-01"));
        var binding = TestPacks.SyntheticBinding;
        var none = binding with { VendorKnowledge = binding.VendorKnowledge with { RequiredKnowledgeFactIds = [] } };
        Assert.Null(RequiredKnowledgeCheck.For([none], resolver, none.FixtureId, none.AssetId));
    }
}
