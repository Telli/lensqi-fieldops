using LensQi.FieldOps.Core.Diagnostics;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Tests;

public class KnowledgeActionValidatorTests
{
    private static readonly InMemoryDiagnosticRegistry Registry = new([
        new DiagnosticRegistration("NODE-A-HEALTH", ObservationKind.EndpointReachability, "lab health"),
        new DiagnosticRegistration("UNBOUND-PROBE", ObservationKind.InterfaceLinkState, "registered but not approved by the fixture"),
    ]);

    private static (FixtureKnowledgeBinding Binding, KnowledgeResolution Resolution) Context()
    {
        var binding = TestPacks.SyntheticBinding;
        var resolution = new KnowledgeResolver(TestPacks.Store(TestPacks.Synthetic, TestPacks.MikroTik)).Resolve(binding, binding.ToAssetIdentity(), []);
        return (binding, resolution);
    }

    private static VendorGroundedProposal Proposal(
        string asset = "LAB-SW-01", string[]? facts = null, VendorGroundedNextAction action = VendorGroundedNextAction.RequestView,
        string? view = "Show the yellow cable and the P1-P3 labels clearly.", string? diag = null, Prediction? prediction = null) =>
        new(asset, facts ?? ["LAB-PORT-MAP-01"], "Port cannot be confirmed yet.", view, diag, action, prediction);

    private static IEnumerable<string> Codes(ProposalValidationResult r) => r.Rejections.Select(x => x.Code);

    [Fact]
    public void Well_formed_request_view_is_accepted()
    {
        var (b, r) = Context();
        Assert.True(KnowledgeActionValidator.Validate(Proposal(), r, b, Registry).IsValid);
    }

    [Fact]
    public void Fabricated_fact_id_is_rejected()
    {
        var (b, r) = Context();
        var result = KnowledgeActionValidator.Validate(Proposal(facts: ["LAB-PORT-MAP-01", "MK-REST-GET-INTERFACE"]), r, b, Registry);
        var rej = Assert.Single(result.Rejections);
        Assert.Equal(ProposalErrorCodes.InvalidFactReference, rej.Code);
        Assert.Equal(["MK-REST-GET-INTERFACE"], rej.OffendingIds);
    }

    [Fact]
    public void Foreign_asset_id_is_rejected()
    {
        var (b, r) = Context();
        Assert.Contains(ProposalErrorCodes.AssetMismatch, Codes(KnowledgeActionValidator.Validate(Proposal(asset: "RTR-01"), r, b, Registry)));
    }

    [Theory]
    [InlineData("POST /rest/system/reboot")]
    [InlineData("https://10.0.0.1/rest/interface")]
    [InlineData("UNBOUND-PROBE")]
    [InlineData("MK-REST-GET-INTERFACE")]
    public void Diagnostic_outside_fixture_allowlist_and_registry_is_rejected_without_network(string diag)
    {
        var (b, r) = Context();
        var result = KnowledgeActionValidator.Validate(
            Proposal(action: VendorGroundedNextAction.RunApprovedDiagnostic, view: null, diag: diag,
                prediction: new Prediction(diag, "Reachable", ["LAB-PORT-MAP-01"], null)), r, b, Registry);
        Assert.Contains(ProposalErrorCodes.ProbeNotAllowlisted, Codes(result));
    }

    [Fact]
    public void Run_diagnostic_without_prediction_is_prediction_missing()
    {
        var (b, r) = Context();
        var result = KnowledgeActionValidator.Validate(Proposal(action: VendorGroundedNextAction.RunApprovedDiagnostic, view: null, diag: "NODE-A-HEALTH"), r, b, Registry);
        Assert.Equal([ProposalErrorCodes.PredictionMissing], Codes(result));
    }

    [Fact]
    public void Prediction_outside_closed_set_or_wrong_diagnostic_is_rejected()
    {
        var (b, r) = Context();
        var wrongValue = KnowledgeActionValidator.Validate(Proposal(action: VendorGroundedNextAction.RunApprovedDiagnostic, view: null, diag: "NODE-A-HEALTH",
            prediction: new Prediction("NODE-A-HEALTH", "Probably fine", ["LAB-PORT-MAP-01"], null)), r, b, Registry);
        Assert.Contains(ProposalErrorCodes.PredictionValueInvalid, Codes(wrongValue));

        var wrongDiag = KnowledgeActionValidator.Validate(Proposal(action: VendorGroundedNextAction.RunApprovedDiagnostic, view: null, diag: "NODE-A-HEALTH",
            prediction: new Prediction("OTHER", "Reachable", ["LAB-PORT-MAP-01"], null)), r, b, Registry);
        Assert.Contains(ProposalErrorCodes.PredictionValueInvalid, Codes(wrongDiag));
    }

    [Fact]
    public void Prediction_basis_must_be_subset_of_used_facts()
    {
        var (b, r) = Context();
        var result = KnowledgeActionValidator.Validate(Proposal(action: VendorGroundedNextAction.RunApprovedDiagnostic, view: null, diag: "NODE-A-HEALTH",
            prediction: new Prediction("NODE-A-HEALTH", "Reachable", ["LAB-HEALTH-CHECK-01"], null)), r, b, Registry);
        Assert.Contains(ProposalErrorCodes.InvalidFactReference, Codes(result));
    }

    [Fact]
    public void Valid_run_diagnostic_with_prediction_is_accepted()
    {
        var (b, r) = Context();
        var result = KnowledgeActionValidator.Validate(Proposal(facts: ["LAB-PORT-MAP-01", "LAB-HEALTH-CHECK-01"],
            action: VendorGroundedNextAction.RunApprovedDiagnostic, view: null, diag: "NODE-A-HEALTH",
            prediction: new Prediction("NODE-A-HEALTH", "Reachable", ["LAB-HEALTH-CHECK-01"], "cable now in P2")), r, b, Registry);
        Assert.True(result.IsValid, string.Join("; ", result.Rejections.Select(x => x.Message)));
    }

    [Fact]
    public void Human_action_requires_a_human_action_fact_and_request_view_requires_evidence_text()
    {
        var (b, r) = Context();
        Assert.Contains(ProposalErrorCodes.ActionNotAllowed, Codes(KnowledgeActionValidator.Validate(Proposal(action: VendorGroundedNextAction.RequestHumanAction), r, b, Registry)));
        Assert.Contains(ProposalErrorCodes.ActionNotAllowed, Codes(KnowledgeActionValidator.Validate(Proposal(view: "  "), r, b, Registry)));
        Assert.True(KnowledgeActionValidator.Validate(Proposal(action: VendorGroundedNextAction.Abstain, view: null, facts: []), r, b, Registry).IsValid);
    }
}
