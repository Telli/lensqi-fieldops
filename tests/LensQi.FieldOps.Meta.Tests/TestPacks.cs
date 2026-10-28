using System.Text.Json;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Tests;

/// <summary>Deserializes the shipped sample packs without the schema validator (Core has no schema dependency).</summary>
public static class TestPacks
{
    public const string SyntheticId = "lab-switch-01.synthetic";
    public const string MikroTikId = "mikrotik-hex-s-rb760igs.routeros7";

    public static KnowledgePack Synthetic => Read("lab-switch-01.synthetic.json");
    public static KnowledgePack MikroTik => Read("mikrotik-hex-s-rb760igs.routeros7.json");
    public static FixtureKnowledgeBinding SyntheticBinding =>
        JsonSerializer.Deserialize<FixtureKnowledgeBinding>(File.ReadAllBytes(RepoPaths.Fixture("fixture-binding-example.json")), KnowledgeJson.Options)!;

    public static AssetIdentity SyntheticAsset => SyntheticBinding.ToAssetIdentity();
    public static AssetIdentity MikroTikAsset(string? firmware = "7", string? hardwareRevision = null) =>
        new("RTR-01", "MikroTik", "RB760iGS", hardwareRevision, firmware);

    public static FixtureKnowledgeBinding MikroTikBinding(string? firmware = "7", params string[] required) =>
        new("LAB-RACK-02", "RTR-01", new FixtureVendorKnowledge(MikroTikId, "MikroTik", "RB760iGS", firmware, required), ["RTR-01-INTERFACE"]);

    public static InMemoryPackStore Store(params KnowledgePack[] packs) => new(packs);

    private static KnowledgePack Read(string name) =>
        JsonSerializer.Deserialize<KnowledgePack>(File.ReadAllBytes(RepoPaths.Fixture(name)), KnowledgeJson.Options)!;
}

public sealed class InMemoryPackStore : IKnowledgePackStore
{
    public InMemoryPackStore(IEnumerable<KnowledgePack> packs)
    {
        Packs = packs.Select((p, i) => new LoadedKnowledgePack(p, $"sha-{i}-{p.PackId}", $"{p.PackId}.json", DateTimeOffset.UnixEpoch)).ToList();
    }

    public IReadOnlyList<LoadedKnowledgePack> Packs { get; }
    public LoadedKnowledgePack? Find(string packId) => Packs.FirstOrDefault(p => p.Pack.PackId == packId);
}

public static class PackBuilder
{
    public static PackFact Fact(string id, string key, string value, string? requiresFirmware = null, KnowledgeClaimKind kind = KnowledgeClaimKind.VendorDocumented, params string[] tags) =>
        new(id, key, value, $"Statement for {id} long enough.", kind, "DOC-1", "Section 1", tags.Length == 0 ? ["general"] : tags, KnowledgeSafetyClass.ObserveOnly, requiresFirmware);

    public static KnowledgePack Pack(string? firmwareMajor, IReadOnlyList<string> hardwareRevisions, params PackFact[] facts) =>
        new("1.0", "test.pack", 1, PackOriginKind.VendorLinked,
            new PackIdentity("Acme", "SW-100", firmwareMajor, hardwareRevisions),
            [new PackDocument("DOC-1", "Acme", "Acme SW-100 manual", "https://example.com/sw100", null, new DateOnly(2026, 10, 1), DocumentRightsMode.LinkOnly)],
            facts, []);
}
