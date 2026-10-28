using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using LensQi.FieldOps.Api.Infrastructure.Knowledge;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Tests;

public class KnowledgePackValidatorTests
{
    private static readonly DateOnly Today = new(2026, 10, 27);
    private static readonly KnowledgeOptions Options = new();

    private static KnowledgePackValidator Validator() =>
        new(RepoPaths.Fixture(Options.PackSchemaFile), RepoPaths.Fixture(Options.BindingSchemaFile), () => Today);

    private static JsonNode MikroTik() => JsonNode.Parse(File.ReadAllBytes(RepoPaths.Fixture("mikrotik-hex-s-rb760igs.routeros7.json")))!;
    private static JsonNode Binding() => JsonNode.Parse(File.ReadAllBytes(RepoPaths.Fixture("fixture-binding-example.json")))!;
    private static byte[] Bytes(JsonNode node) => Encoding.UTF8.GetBytes(node.ToJsonString());

    private static FileKnowledgePackStore LoadStore() => FileKnowledgePackStore.Load(RepoPaths.FixturesDir, Options, Validator());

    [Fact]
    public void Both_sample_packs_load_with_byte_exact_digests()
    {
        var store = LoadStore();
        Assert.Equal(2, store.Packs.Count);
        foreach (var loaded in store.Packs)
        {
            var expected = FileKnowledgePackStore.ComputeSha256(File.ReadAllBytes(RepoPaths.Fixture(loaded.SourceName)));
            Assert.Equal(expected, loaded.Sha256);
            Assert.Equal(64, loaded.Sha256.Length);
        }
        // Pinned prefixes from the shipped fixtures: a changed fixture must change these on purpose (VK-T02).
        Assert.StartsWith("f61d928056ad", store.Find("lab-switch-01.synthetic")!.Sha256);
        Assert.StartsWith("fa82b1077df0", store.Find("mikrotik-hex-s-rb760igs.routeros7")!.Sha256);
    }

    [Fact]
    public void One_byte_of_whitespace_changes_the_digest_but_not_validity()
    {
        var original = File.ReadAllBytes(RepoPaths.Fixture("lab-switch-01.synthetic.json"));
        var modified = original.Concat("\n"u8.ToArray()).ToArray();
        Assert.True(Validator().ValidatePack(modified).IsValid);
        Assert.NotEqual(FileKnowledgePackStore.ComputeSha256(original), FileKnowledgePackStore.ComputeSha256(modified));
    }

    [Fact]
    public void Sample_binding_validates_against_installed_packs()
    {
        var store = LoadStore();
        var result = Validator().ValidateBinding(Bytes(Binding()), store);
        Assert.True(result.IsValid, string.Join("; ", result.Errors));
        Assert.Equal("LAB-SW-01", result.Binding!.AssetId);
        Assert.Equal(["NODE-A-HEALTH"], result.Binding.ApprovedDiagnostics);
    }

    public static TheoryData<string, Action<JsonNode>> PackRejections => new()
    {
        { "unsupported schema version", n => n["schemaVersion"] = "0.9" },
        { "missing locator", n => n["facts"]![0]!.AsObject().Remove("sourceLocator") },
        { "non-HTTPS vendor URL", n => n["documents"]![0]!["url"] = "http://unsafe.example" },
        { "undeclared command injection field", n => n["arbitraryCommands"] = new JsonArray("POST /rest/reboot") },
        { "unsafe safety classification", n => n["facts"]![0]!["safetyClass"] = "RunShellCommand" },
        { "SiteConfigured fact inside VendorLinked pack", n => n["facts"]![0]!["claimKind"] = "SiteConfigured" },
        { "factId with whitespace and quotes", n => n["facts"]![0]!["factId"] = "fact \"one\" two" },
        { "capability declared writable", n => n["capabilities"]![0]!["readOnly"] = false },
        { "statement long enough to be copied manual text", n => n["facts"]![0]!["statement"] = new string('x', 481) },
        { "duplicate factId", n => n["facts"]!.AsArray().Add(n["facts"]![0]!.DeepClone()) },
        { "unresolved document reference", n => n["facts"]![0]!["sourceDocumentId"] = "NOT-A-DOCUMENT" },
        { "fact firmware scope differs from pack identity", n => n["facts"]![1]!["requiresFirmwareMajor"] = "6" },
        { "accessedOn in the future", n => n["documents"]![0]!["accessedOn"] = "2099-01-01" },
        { "contradictory assertedValue for same factKey", n =>
            {
                var clone = n["facts"]![0]!.DeepClone();
                clone["factId"] = "MK-PRODUCT-PORTS-ALT";
                clone["assertedValue"] = "4";
                n["facts"]!.AsArray().Add(clone);
            }
        },
        { "unknown field inside identity", n => n["identity"]!["targetHost"] = "10.0.0.1" },
    };

    [Theory]
    [MemberData(nameof(PackRejections))]
    public void Pack_rejections_match_the_python_validator(string label, Action<JsonNode> mutate)
    {
        var node = MikroTik();
        mutate(node);
        var result = Validator().ValidatePack(Bytes(node));
        Assert.False(result.IsValid, $"{label} unexpectedly passed");
        Assert.NotEmpty(result.Errors);
        Assert.Null(result.Pack);
    }

    public static TheoryData<string, Action<JsonNode>> BindingRejections => new()
    {
        { "binding synthetic asset to the MikroTik pack", n => n["vendorKnowledge"]!["packId"] = "mikrotik-hex-s-rb760igs.routeros7" },
        { "binding requires fact from another pack", n => n["vendorKnowledge"]!["requiredKnowledgeFactIds"]!.AsArray().Add("MK-REST-GET-INTERFACE") },
        { "binding product code differs from pack", n => n["vendorKnowledge"]!["expectedProductCode"] = "LAB-8P-SYNTH-V2" },
        { "binding diagnostic entry is a URL", n => n["approvedDiagnostics"] = new JsonArray("https://10.0.0.1/rest/interface") },
        { "binding carries a network target", n => n["targetHost"] = "10.0.0.1" },
        { "binding names an uninstalled pack", n => n["vendorKnowledge"]!["packId"] = "not-installed.pack" },
    };

    [Theory]
    [MemberData(nameof(BindingRejections))]
    public void Binding_rejections_are_caught_at_load(string label, Action<JsonNode> mutate)
    {
        var store = LoadStore();
        var node = Binding();
        mutate(node);
        var result = Validator().ValidateBinding(Bytes(node), store);
        Assert.False(result.IsValid, $"{label} unexpectedly passed");
        Assert.Null(result.Binding);
    }

    [Fact]
    public void Store_refuses_to_start_with_any_invalid_pack()
    {
        var dir = Directory.CreateTempSubdirectory("fieldops-packs-");
        try
        {
            foreach (var name in new[] { Options.PackSchemaFile, Options.BindingSchemaFile, "lab-switch-01.synthetic.json" })
                File.Copy(RepoPaths.Fixture(name), Path.Combine(dir.FullName, name));
            var broken = MikroTik();
            broken["facts"]![0]!["sourceDocumentId"] = "NOPE";
            File.WriteAllBytes(Path.Combine(dir.FullName, "broken.json"), Bytes(broken));

            var ex = Assert.Throws<KnowledgePackLoadException>(() => FileKnowledgePackStore.Load(dir.FullName, Options, Validator()));
            Assert.Contains(ex.Errors, e => e.StartsWith("broken.json:") && e.Contains("NOPE"));
        }
        finally { dir.Delete(recursive: true); }
    }

    [Fact]
    public void Malformed_json_and_duplicate_pack_ids_are_rejected()
    {
        Assert.False(Validator().ValidatePack("{ not json"u8).IsValid);

        var dir = Directory.CreateTempSubdirectory("fieldops-packs-");
        try
        {
            foreach (var name in new[] { Options.PackSchemaFile, Options.BindingSchemaFile, "lab-switch-01.synthetic.json" })
                File.Copy(RepoPaths.Fixture(name), Path.Combine(dir.FullName, name));
            File.Copy(RepoPaths.Fixture("lab-switch-01.synthetic.json"), Path.Combine(dir.FullName, "copy.json"));
            var ex = Assert.Throws<KnowledgePackLoadException>(() => FileKnowledgePackStore.Load(dir.FullName, Options, Validator()));
            Assert.Contains(ex.Errors, e => e.Contains("is also declared by"));
        }
        finally { dir.Delete(recursive: true); }
    }

    [Fact]
    public void Deserialized_pack_round_trips_through_typed_model()
    {
        var pack = Validator().ValidatePack(File.ReadAllBytes(RepoPaths.Fixture("mikrotik-hex-s-rb760igs.routeros7.json"))).Pack!;
        Assert.Equal(PackOriginKind.VendorLinked, pack.OriginKind);
        Assert.Equal("7", pack.Identity.FirmwareMajor);
        Assert.Equal(new DateOnly(2026, 10, 27), pack.Documents[0].AccessedOn);
        Assert.Equal(KnowledgeSafetyClass.ReadOnlyObservation, pack.FindFact("MK-REST-GET-INTERFACE")!.SafetyClass);
        Assert.Equal(ObservationKind.InterfaceLinkState, pack.Capabilities[0].ObservationKind);
        _ = JsonSerializer.Serialize(pack, KnowledgeJson.Options);
    }
}
