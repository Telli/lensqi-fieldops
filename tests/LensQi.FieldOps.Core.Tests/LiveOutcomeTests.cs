using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Tests;

/// <summary>The live outcome needs a fresh passing LiveLocal measurement; no fixture policy can reach it any other way.</summary>
public class LiveOutcomeTests
{
    private static LoadedExpectedTwin WithPolicy(LoadedExpectedTwin loaded, Func<TwinVerificationPolicy, TwinVerificationPolicy> change) =>
        loaded with { Twin = loaded.Twin with { VerificationPolicy = change(loaded.Twin.VerificationPolicy) } };

    private static InspectionTestKit ReadyForDiagnostic()
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim();
        k.PostRepairEvidence();
        k.Tick();
        return k;
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void A_policy_without_a_fresh_check_never_reports_live(bool measuredPass)
    {
        var k = ReadyForDiagnostic();
        k.RecordProbe(k.Probe(measuredPass));

        var d = VerificationPolicyEvaluator.Evaluate(k.Session.State, WithPolicy(k.Twin, p => p with { RequireFreshDiagnostic = false }), k.Now);

        Assert.Equal(VerificationResult.Denied, d.Result);
        Assert.Null(d.OutcomeKind);
        Assert.Contains("no measurement to verify against", d.Reasons[0]);
    }

    [Fact]
    public void A_policy_naming_a_simulated_source_for_live_stays_simulated()
    {
        var k = ReadyForDiagnostic();
        k.RecordProbe(k.Probe(true));

        var d = VerificationPolicyEvaluator.Evaluate(k.Session.State, WithPolicy(k.Twin, p => p with { VerifiedLiveRequiresSource = DiagnosticSource.Simulated }), k.Now);

        Assert.Equal(VerificationResult.Verified, d.Result);
        Assert.Equal(VerificationOutcomeKind.VerifiedCompleteSimulated, d.OutcomeKind);
    }

    [Theory]
    [InlineData(DiagnosticSource.LiveLocal, VerificationOutcomeKind.VerifiedCompleteLive)]
    [InlineData(DiagnosticSource.Simulated, VerificationOutcomeKind.VerifiedCompleteSimulated)]
    public void The_shipped_policy_labels_a_pass_by_what_measured_it(DiagnosticSource source, VerificationOutcomeKind expected)
    {
        var k = ReadyForDiagnostic();
        k.RecordProbe(k.Probe(true, source));

        var d = VerificationPolicyEvaluator.Evaluate(k.Session.State, k.Twin, k.Now);

        Assert.Equal(VerificationResult.Verified, d.Result);
        Assert.Equal(expected, d.OutcomeKind);
    }

    [Fact]
    public void A_fixture_that_weakens_the_contract_is_refused_at_load()
    {
        var shipped = InspectionTestKit.LoadTwin();
        Assert.Empty(ExpectedTwinInvariants.Check(shipped.Twin));

        Assert.Contains(ExpectedTwinInvariants.Check(WithPolicy(shipped, p => p with { RequireFreshDiagnostic = false }).Twin),
            e => e.StartsWith("requireFreshDiagnostic must be true"));
        Assert.Contains(ExpectedTwinInvariants.Check(WithPolicy(shipped, p => p with { VerifiedLiveRequiresSource = DiagnosticSource.Simulated }).Twin),
            e => e == "verifiedLiveRequiresSource must be LiveLocal");
    }
}
