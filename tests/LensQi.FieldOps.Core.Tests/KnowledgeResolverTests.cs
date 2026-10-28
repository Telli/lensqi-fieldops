using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Tests;

public class KnowledgeResolverTests
{
    private static KnowledgeResolver Resolver(int maxFacts = 10) =>
        new(TestPacks.Store(TestPacks.Synthetic, TestPacks.MikroTik), new KnowledgeResolverOptions { MaxFacts = maxFacts });

    [Fact]
    public void Synthetic_fixture_resolves_only_synthetic_facts_with_server_composed_citations()
    {
        var binding = TestPacks.SyntheticBinding;
        var result = Resolver().Resolve(binding, binding.ToAssetIdentity(), []);

        Assert.Equal(KnowledgeResolutionStatus.Applicable, result.Status);
        Assert.Equal(TestPacks.SyntheticId, result.PackId);
        Assert.Equal(1, result.PackRevision);
        Assert.False(string.IsNullOrEmpty(result.PackSha256));
        Assert.Equal(3, result.Facts.Count);
        Assert.Empty(result.Excluded);
        Assert.All(result.Facts, f =>
        {
            Assert.StartsWith("LAB-", f.FactId);
            Assert.Equal(TestPacks.SyntheticId, f.Citation.PackId);
            Assert.Equal(result.PackSha256, f.Citation.PackSha256);
            Assert.Null(f.Citation.SourceUrl);
            Assert.Equal("Original synthetic lab wiring and inspection plan", f.Citation.DocumentTitle);
            Assert.NotEqual(KnowledgeClaimKind.VendorDocumented, f.ClaimKind);
        });
        Assert.DoesNotContain(result.Facts, f => f.FactId.StartsWith("MK-"));
        Assert.Equal(ProvenanceLabel.SiteConfigured, result.Facts.Single(f => f.FactId == "LAB-PORT-MAP-01").Provenance);
    }

    [Fact]
    public void Mikrotik_asset_against_synthetic_binding_is_a_manufacturer_mismatch()
    {
        var result = Resolver().Resolve(TestPacks.SyntheticBinding, TestPacks.MikroTikAsset() with { AssetId = "LAB-SW-01" }, []);
        Assert.Equal(KnowledgeResolutionStatus.ManufacturerMismatch, result.Status);
        Assert.Empty(result.Facts);
        Assert.Equal(3, result.Excluded.Count);
    }

    [Fact]
    public void Vendor_pack_with_firmware_7_resolves_vendor_facts_with_https_citations()
    {
        var result = Resolver().Resolve(TestPacks.MikroTikBinding("7"), TestPacks.MikroTikAsset("7"), []);
        Assert.Equal(KnowledgeResolutionStatus.Applicable, result.Status);
        Assert.Equal(4, result.Facts.Count);
        Assert.All(result.Facts.Where(f => f.ClaimKind == KnowledgeClaimKind.VendorDocumented), f =>
        {
            Assert.NotNull(f.Citation.SourceUrl);
            Assert.Equal("https", f.Citation.SourceUrl!.Scheme);
            Assert.Equal("MikroTik", f.Citation.Publisher);
            Assert.False(string.IsNullOrWhiteSpace(f.Citation.SourceLocator));
        });
    }

    [Fact]
    public void Unknown_firmware_lists_withheld_vendor_facts_with_reason()
    {
        var result = Resolver().Resolve(TestPacks.MikroTikBinding(null), TestPacks.MikroTikAsset(null), []);
        Assert.Equal(KnowledgeResolutionStatus.Applicable, result.Status);
        Assert.Equal(["MK-PRODUCT-PORTS"], result.Facts.Select(f => f.FactId));
        Assert.Equal(3, result.Excluded.Count);
        Assert.All(result.Excluded, e =>
        {
            Assert.Equal(FactApplicability.Unverified, e.Verdict);
            Assert.Equal(KnowledgeReasons.FirmwareUnknown, e.Reason);
        });
    }

    [Fact]
    public void Issue_tags_filter_but_required_facts_always_come_first()
    {
        var binding = TestPacks.MikroTikBinding("7", "MK-INTERFACE-SCOPE-NOTE");
        var result = Resolver().Resolve(binding, TestPacks.MikroTikAsset("7"), ["link-state"]);
        Assert.Equal("MK-INTERFACE-SCOPE-NOTE", result.Facts[0].FactId);
        Assert.Contains(result.Facts, f => f.FactId == "MK-REST-GET-INTERFACE");
        Assert.DoesNotContain(result.Facts, f => f.FactId == "MK-PRODUCT-PORTS");
    }

    [Fact]
    public void Result_count_is_bounded_and_the_omission_is_declared()
    {
        var result = Resolver(maxFacts: 2).Resolve(TestPacks.MikroTikBinding("7"), TestPacks.MikroTikAsset("7"), []);
        Assert.Equal(2, result.Facts.Count);
        Assert.Contains(result.Warnings, w => w.Contains("omitted by the 2-fact bound"));
    }

    [Fact]
    public void Required_fact_that_is_withheld_produces_a_warning()
    {
        var binding = TestPacks.MikroTikBinding(null, "MK-REST-GET-INTERFACE");
        var result = Resolver().Resolve(binding, TestPacks.MikroTikAsset(null), []);
        Assert.Contains(result.Warnings, w => w.Contains("Required fact 'MK-REST-GET-INTERFACE'"));
    }

    [Fact]
    public void Asset_not_bound_by_fixture_or_pack_not_installed_is_no_pack()
    {
        var binding = TestPacks.SyntheticBinding;
        var wrongAsset = Resolver().Resolve(binding, binding.ToAssetIdentity() with { AssetId = "OTHER" }, []);
        Assert.Equal(KnowledgeResolutionStatus.NoPack, wrongAsset.Status);

        var missingPack = new KnowledgeResolver(TestPacks.Store(TestPacks.MikroTik)).Resolve(binding, binding.ToAssetIdentity(), []);
        Assert.Equal(KnowledgeResolutionStatus.NoPack, missingPack.Status);
        Assert.Contains(missingPack.Warnings, w => w.Contains("not installed"));
    }
}
