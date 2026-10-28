namespace LensQi.FieldOps.Core.Knowledge;

/// <summary>
/// Site-owned overlay binding one fixture asset to exactly one knowledge pack
/// (fixtures/vendor-knowledge/fixture-binding.schema.json). Diagnostics are authorized here, never by a pack.
/// </summary>
public sealed record FixtureKnowledgeBinding(
    string FixtureId,
    string AssetId,
    FixtureVendorKnowledge VendorKnowledge,
    IReadOnlyList<string> ApprovedDiagnostics,
    string? Status = null,
    string? Note = null)
{
    /// <summary>The asset identity this binding asserts, used for applicability evaluation.</summary>
    public AssetIdentity ToAssetIdentity() => new(
        AssetId,
        VendorKnowledge.ExpectedManufacturerId,
        VendorKnowledge.ExpectedProductCode,
        VendorKnowledge.HardwareRevision,
        VendorKnowledge.FirmwareMajor);
}

public sealed record FixtureVendorKnowledge(
    string PackId,
    string ExpectedManufacturerId,
    string ExpectedProductCode,
    string? FirmwareMajor,
    IReadOnlyList<string> RequiredKnowledgeFactIds,
    string? HardwareRevision = null);

/// <summary>Cross-file checks between a binding and the installed pack it names (VK-R02.7).</summary>
public static class FixtureBindingInvariants
{
    public static IReadOnlyList<string> Check(FixtureKnowledgeBinding binding, IKnowledgePackStore store)
    {
        var errors = new List<string>();
        var vk = binding.VendorKnowledge;
        var loaded = store.Find(vk.PackId);
        if (loaded is null)
        {
            errors.Add($"Binding references unknown pack '{vk.PackId}'");
            return errors;
        }

        var identity = loaded.Pack.Identity;
        if (!string.Equals(vk.ExpectedManufacturerId, identity.ManufacturerId, StringComparison.Ordinal))
            errors.Add("Binding expectedManufacturerId does not equal the pack identity");
        if (!string.Equals(vk.ExpectedProductCode, identity.ProductCode, StringComparison.Ordinal))
            errors.Add("Binding expectedProductCode does not equal the pack identity");
        if (vk.FirmwareMajor is not null && identity.FirmwareMajor is not null &&
            !string.Equals(vk.FirmwareMajor, identity.FirmwareMajor, StringComparison.Ordinal))
            errors.Add("Binding firmwareMajor does not equal the pack identity");

        var missing = vk.RequiredKnowledgeFactIds.Where(id => loaded.Pack.FindFact(id) is null).ToList();
        if (missing.Count > 0)
            errors.Add($"Binding requires fact IDs absent from pack '{vk.PackId}': {string.Join(", ", missing)}");

        return errors;
    }
}
