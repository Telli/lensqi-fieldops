using LensQi.FieldOps.Core.Knowledge;
using static LensQi.FieldOps.Tests.PackBuilder;

namespace LensQi.FieldOps.Tests;

public class KnowledgeApplicabilityTests
{
    private static AssetIdentity Asset(string manufacturer = "Acme", string product = "SW-100", string? hw = null, string? fw = "3") =>
        new("A-1", manufacturer, product, hw, fw);

    [Fact]
    public void Manufacturer_mismatch_excludes_every_fact()
    {
        var pack = Pack("3", [], Fact("F1", "k.a", "1"));
        var result = KnowledgeApplicability.Evaluate(pack, Asset(manufacturer: "Acmee"));
        Assert.Equal(KnowledgeResolutionStatus.ManufacturerMismatch, result.Status);
        Assert.All(result.Verdicts, v => Assert.Equal(FactApplicability.Inapplicable, v.Verdict));
        Assert.Empty(result.ApplicableFacts);
    }

    [Theory]
    [InlineData("SW-1000")]
    [InlineData("SW-100A")]
    [InlineData("SW 100")]
    public void Similar_looking_model_is_a_mismatch_not_a_suggestion(string lookalike)
    {
        var pack = Pack("3", [], Fact("F1", "k.a", "1"));
        var result = KnowledgeApplicability.Evaluate(pack, Asset(product: lookalike));
        Assert.Equal(KnowledgeResolutionStatus.ModelMismatch, result.Status);
        Assert.Empty(result.ApplicableFacts);
    }

    [Fact]
    public void Case_and_whitespace_normalized_exact_match_is_applicable()
    {
        var pack = Pack("3", [], Fact("F1", "k.a", "1"));
        var result = KnowledgeApplicability.Evaluate(pack, Asset(manufacturer: " acme ", product: "sw-100"));
        Assert.Equal(KnowledgeResolutionStatus.Applicable, result.Status);
        Assert.Single(result.ApplicableFacts);
    }

    [Fact]
    public void Empty_hardware_revision_list_does_not_restrict()
    {
        var pack = Pack("3", [], Fact("F1", "k.a", "1"));
        Assert.Equal(KnowledgeResolutionStatus.Applicable, KnowledgeApplicability.Evaluate(pack, Asset(hw: null)).Status);
    }

    [Fact]
    public void Required_hardware_revision_unknown_is_unverified_not_applicable()
    {
        var pack = Pack("3", ["r2"], Fact("F1", "k.a", "1"));
        var result = KnowledgeApplicability.Evaluate(pack, Asset(hw: null));
        Assert.Equal(KnowledgeResolutionStatus.ApplicabilityUnverified, result.Status);
        Assert.All(result.Verdicts, v => Assert.Equal(KnowledgeReasons.HardwareRevisionUnknown, v.Reason));
    }

    [Fact]
    public void Unlisted_hardware_revision_is_a_mismatch()
    {
        var pack = Pack("3", ["r2"], Fact("F1", "k.a", "1"));
        Assert.Equal(KnowledgeResolutionStatus.HardwareRevisionMismatch, KnowledgeApplicability.Evaluate(pack, Asset(hw: "r3")).Status);
        Assert.Equal(KnowledgeResolutionStatus.Applicable, KnowledgeApplicability.Evaluate(pack, Asset(hw: "R2")).Status);
    }

    [Fact]
    public void Unknown_firmware_withholds_firmware_scoped_facts_and_keeps_the_rest()
    {
        var pack = Pack("3", [], Fact("F1", "k.a", "1"), Fact("F2", "k.b", "2", requiresFirmware: "3"));
        var result = KnowledgeApplicability.Evaluate(pack, Asset(fw: null));
        Assert.Equal(KnowledgeResolutionStatus.Applicable, result.Status);
        Assert.Equal(["F1"], result.ApplicableFacts.Select(f => f.FactId));
        var withheld = Assert.Single(result.Verdicts, v => v.Fact.FactId == "F2");
        Assert.Equal(FactApplicability.Unverified, withheld.Verdict);
        Assert.Equal(KnowledgeReasons.FirmwareUnknown, withheld.Reason);
        Assert.Contains(result.Warnings, w => w.Contains("firmware is unknown", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void Unknown_firmware_with_only_scoped_facts_is_unverified_not_no_knowledge()
    {
        var pack = Pack("3", [], Fact("F2", "k.b", "2", requiresFirmware: "3"));
        Assert.Equal(KnowledgeResolutionStatus.ApplicabilityUnverified, KnowledgeApplicability.Evaluate(pack, Asset(fw: null)).Status);
    }

    [Fact]
    public void Different_firmware_major_is_inapplicable()
    {
        var pack = Pack("3", [], Fact("F2", "k.b", "2", requiresFirmware: "3"));
        var result = KnowledgeApplicability.Evaluate(pack, Asset(fw: "2"));
        Assert.Equal(KnowledgeResolutionStatus.NoApplicableKnowledge, result.Status);
        Assert.Equal(KnowledgeReasons.FirmwareMismatch, Assert.Single(result.Verdicts).Reason);
    }

    [Fact]
    public void Conflicting_applicable_facts_surface_both_and_pick_no_winner()
    {
        var pack = Pack("3", [], Fact("F1", "ports.count", "5"), Fact("F2", "ports.count", "4"));
        var result = KnowledgeApplicability.Evaluate(pack, Asset());
        Assert.Equal(KnowledgeResolutionStatus.KnowledgeConflict, result.Status);
        var warning = Assert.Single(result.Warnings);
        Assert.Contains("F1", warning);
        Assert.Contains("F2", warning);
        Assert.StartsWith(KnowledgeReasons.KnowledgeConflict, warning);
    }

    [Fact]
    public void Invariants_reject_fact_whose_firmware_scope_differs_from_pack_identity()
    {
        var pack = Pack("7", [], Fact("F1", "k.a", "1", requiresFirmware: "6"));
        var errors = KnowledgePackInvariants.Check(pack, new DateOnly(2026, 10, 27));
        Assert.Contains(errors, e => e.Contains("requires firmware major '6'"));
    }
}
