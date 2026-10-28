namespace LensQi.FieldOps.Core.Knowledge;

// Enumerations mirror the normative JSON Schema (fixtures/vendor-knowledge/knowledge-pack.schema.json)
// and design.md §5. Member names are the exact wire strings.

public enum KnowledgeResolutionStatus
{
    Applicable,
    NoPack,
    NoApplicableKnowledge,
    ManufacturerMismatch,
    ModelMismatch,
    HardwareRevisionMismatch,
    ApplicabilityUnverified,
    KnowledgeConflict,
    InvalidPack,
}

/// <summary>Pack-author claim kinds. Mirrors the schema's <c>claimKind</c> exactly.</summary>
public enum KnowledgeClaimKind { VendorDocumented, SiteConfigured, EngineeringNote }

/// <summary>Runtime provenance shown in UI and receipts. Inferred and Measured are never pack claim kinds.</summary>
public enum ProvenanceLabel { Documented, SiteConfigured, Inferred, Measured }

public enum FactApplicability { Applicable, Unverified, Inapplicable }

public enum KnowledgeSafetyClass { ObserveOnly, ReadOnlyObservation, HumanActionRequired }

public enum PackOriginKind { VendorLinked, Synthetic }

public enum DocumentRightsMode { LinkOnly, OriginalSynthetic }

public enum ObservationKind { Visual, InterfaceLinkState, EndpointReachability, AssetInventory }

/// <summary>The asset as confirmed by the site fixture or a human, never by a model guess.</summary>
public sealed record AssetIdentity(
    string AssetId,
    string ManufacturerId,
    string ProductCode,
    string? HardwareRevision,
    string? FirmwareMajor);

/// <summary>Server-composed citation. Every field comes from the resolved pack, never from model prose.</summary>
public sealed record KnowledgeCitation(
    string FactId,
    string PackId,
    string PackSha256,
    string DocumentId,
    string DocumentTitle,
    string Publisher,
    Uri? SourceUrl,
    string SourceLocator,
    DateOnly AccessedOn);

public sealed record ApplicableFact(
    string FactId,
    string FactKey,
    string AssertedValue,
    string Statement,
    KnowledgeClaimKind ClaimKind,
    IReadOnlyList<string> Tags,
    KnowledgeSafetyClass Safety,
    KnowledgeCitation Citation)
{
    /// <summary>UI provenance label for a pack fact. Pack facts are never Inferred or Measured.</summary>
    public ProvenanceLabel Provenance => ClaimKind == KnowledgeClaimKind.SiteConfigured
        ? ProvenanceLabel.SiteConfigured
        : ProvenanceLabel.Documented;
}

/// <summary>A fact the resolver saw but did not return, with the machine-readable reason.</summary>
public sealed record ExcludedFact(string FactId, FactApplicability Verdict, string Reason);

public sealed record KnowledgeResolution(
    KnowledgeResolutionStatus Status,
    string? PackId,
    string? PackSha256,
    int? PackRevision,
    IReadOnlyList<ApplicableFact> Facts,
    IReadOnlyList<ExcludedFact> Excluded,
    IReadOnlyList<string> Warnings)
{
    public static KnowledgeResolution Empty(KnowledgeResolutionStatus status, params string[] warnings) =>
        new(status, null, null, null, [], [], warnings);
}

/// <summary>Stable reason codes used in <see cref="ExcludedFact.Reason"/> and API errors.</summary>
public static class KnowledgeReasons
{
    public const string FirmwareUnknown = "firmware_unknown";
    public const string FirmwareMismatch = "firmware_mismatch";
    public const string HardwareRevisionUnknown = "hardware_revision_unknown";
    public const string HardwareRevisionMismatch = "hardware_revision_mismatch";
    public const string ManufacturerMismatch = "manufacturer_mismatch";
    public const string ModelMismatch = "model_mismatch";
    public const string KnowledgeConflict = "knowledge_conflict";
}
