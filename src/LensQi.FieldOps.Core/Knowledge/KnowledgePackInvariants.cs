namespace LensQi.FieldOps.Core.Knowledge;

/// <summary>
/// Cross-item rules the JSON Schema cannot express (requirements VK-R02.1). The loader runs these after
/// schema validation; a pack failing any rule is rejected at load, never partially used.
/// Mirrors scripts/validate_vendor_packs.py so the Python and .NET validators agree.
/// </summary>
public static class KnowledgePackInvariants
{
    public static IReadOnlyList<string> Check(KnowledgePack pack, DateOnly today)
    {
        var errors = new List<string>();

        if (pack.SchemaVersion != KnowledgePack.SupportedSchemaVersion)
            errors.Add($"Unsupported schemaVersion '{pack.SchemaVersion}' (expected {KnowledgePack.SupportedSchemaVersion})");

        AddDuplicates(errors, pack.Documents.Select(d => d.DocumentId), "documentId");
        AddDuplicates(errors, pack.Facts.Select(f => f.FactId), "factId");
        AddDuplicates(errors, pack.Capabilities.Select(c => c.CapabilityId), "capabilityId");

        foreach (var doc in pack.Documents)
        {
            if (doc.AccessedOn > today)
                errors.Add($"accessedOn in the future for {doc.DocumentId}");
            if (pack.OriginKind == PackOriginKind.VendorLinked)
            {
                if (doc.Url is null || !doc.Url.StartsWith("https://", StringComparison.Ordinal))
                    errors.Add($"VendorLinked document {doc.DocumentId} must have an https URL");
                if (doc.RightsMode != DocumentRightsMode.LinkOnly)
                    errors.Add($"VendorLinked document {doc.DocumentId} must use rightsMode LinkOnly");
            }
            else if (doc.RightsMode != DocumentRightsMode.OriginalSynthetic)
            {
                errors.Add($"Synthetic document {doc.DocumentId} must use rightsMode OriginalSynthetic");
            }
        }

        var documentIds = pack.Documents.Select(d => d.DocumentId).ToHashSet(StringComparer.Ordinal);
        foreach (var fact in pack.Facts)
        {
            if (!documentIds.Contains(fact.SourceDocumentId))
                errors.Add($"Unknown document ID '{fact.SourceDocumentId}' for {fact.FactId}");

            if (pack.OriginKind == PackOriginKind.VendorLinked && fact.ClaimKind == KnowledgeClaimKind.SiteConfigured)
                errors.Add($"Manufacturer pack cannot authorize site-specific configuration facts ({fact.FactId})");

            if (fact.RequiresFirmwareMajor is not null &&
                !string.Equals(fact.RequiresFirmwareMajor, pack.Identity.FirmwareMajor, StringComparison.Ordinal))
            {
                errors.Add(
                    $"{fact.FactId} requires firmware major '{fact.RequiresFirmwareMajor}' but the pack identity declares '{pack.Identity.FirmwareMajor ?? "null"}'");
            }
        }

        foreach (var cap in pack.Capabilities.Where(c => !c.ReadOnly))
            errors.Add($"Capability {cap.CapabilityId} must be readOnly");

        // Concrete contradiction: same factKey and firmware scope, different asserted value.
        foreach (var group in pack.Facts.GroupBy(f => (f.FactKey, f.RequiresFirmwareMajor)))
        {
            var values = group.Select(f => f.AssertedValue).Distinct(StringComparer.Ordinal).ToList();
            if (values.Count > 1)
                errors.Add($"Contradictory factKey {group.Key.FactKey}: {string.Join(" vs ", values)}");
        }

        return errors;
    }

    private static void AddDuplicates(List<string> errors, IEnumerable<string> ids, string label)
    {
        foreach (var dup in ids.GroupBy(i => i, StringComparer.Ordinal).Where(g => g.Count() > 1))
            errors.Add($"Duplicate {label} '{dup.Key}'");
    }
}
