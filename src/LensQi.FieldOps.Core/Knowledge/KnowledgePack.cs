namespace LensQi.FieldOps.Core.Knowledge;

// In-memory shape of a knowledge pack file. Property names equal the JSON member names under
// camelCase naming; the loader validates the raw document against the JSON Schema before binding.

public sealed record KnowledgePack(
    string SchemaVersion,
    string PackId,
    int PackRevision,
    PackOriginKind OriginKind,
    PackIdentity Identity,
    IReadOnlyList<PackDocument> Documents,
    IReadOnlyList<PackFact> Facts,
    IReadOnlyList<PackCapability> Capabilities)
{
    public const string SupportedSchemaVersion = "1.0";

    public PackDocument? FindDocument(string documentId) =>
        Documents.FirstOrDefault(d => string.Equals(d.DocumentId, documentId, StringComparison.Ordinal));

    public PackFact? FindFact(string factId) =>
        Facts.FirstOrDefault(f => string.Equals(f.FactId, factId, StringComparison.Ordinal));
}

public sealed record PackIdentity(
    string ManufacturerId,
    string ProductCode,
    string? FirmwareMajor,
    IReadOnlyList<string> HardwareRevisions);

public sealed record PackDocument(
    string DocumentId,
    string Publisher,
    string Title,
    string? Url,
    string? DocumentVersion,
    DateOnly AccessedOn,
    DocumentRightsMode RightsMode);

public sealed record PackFact(
    string FactId,
    string FactKey,
    string AssertedValue,
    string Statement,
    KnowledgeClaimKind ClaimKind,
    string SourceDocumentId,
    string SourceLocator,
    IReadOnlyList<string> Tags,
    KnowledgeSafetyClass SafetyClass,
    string? RequiresFirmwareMajor);

public sealed record PackCapability(
    string CapabilityId,
    string Description,
    ObservationKind ObservationKind,
    bool ReadOnly);

/// <summary>
/// A pack as installed on the server: the parsed document plus the SHA-256 of the exact file bytes.
/// Immutable after load; a changed byte is a different artifact with a different digest.
/// </summary>
public sealed record LoadedKnowledgePack(
    KnowledgePack Pack,
    string Sha256,
    string SourceName,
    DateTimeOffset LoadedAt);

public interface IKnowledgePackStore
{
    IReadOnlyList<LoadedKnowledgePack> Packs { get; }
    LoadedKnowledgePack? Find(string packId);
}
