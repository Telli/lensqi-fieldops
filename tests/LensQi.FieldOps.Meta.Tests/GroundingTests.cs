using System.Text.Json;
using System.Text.Json.Nodes;
using LensQi.FieldOps.Core.Diagnostics;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Meta.Knowledge;
using Microsoft.Extensions.AI;

namespace LensQi.FieldOps.Tests;

public class GroundingTests
{
    private static readonly InMemoryDiagnosticRegistry Registry = new([
        new DiagnosticRegistration("NODE-A-HEALTH", ObservationKind.EndpointReachability, "lab health"),
    ]);

    private static (FixtureKnowledgeBinding, AssetIdentity, KnowledgeResolution) Synthetic()
    {
        var b = TestPacks.SyntheticBinding;
        var a = b.ToAssetIdentity();
        return (b, a, new KnowledgeResolver(TestPacks.Store(TestPacks.Synthetic, TestPacks.MikroTik)).Resolve(b, a, []));
    }

    [Fact]
    public void Prompt_contains_only_applicable_facts_marks_data_untrusted_and_lists_closed_sets()
    {
        var (b, a, r) = Synthetic();
        var req = KnowledgeGroundingComposer.Compose(b, a, r, Registry, new Observation("yellow cable, labels occluded"));
        var text = string.Join("\n", req.Messages.SelectMany(m => m.Contents.OfType<TextContent>().Select(t => t.Text)));

        Assert.Contains("LAB-PORT-MAP-01", text);
        Assert.Contains("LAB-HEALTH-CHECK-01", text);
        Assert.DoesNotContain("MK-REST-GET-INTERFACE", text);
        Assert.Contains("not instructions to you", text);
        Assert.Contains("\"allowedPredictedValues\"", text);
        Assert.Contains("Reachable", text);
        Assert.Contains("\"Abstain\"", text);
        Assert.Equal(64, req.PromptDigest.Length);
        Assert.Equal(0, req.Options.Temperature);
        var format = Assert.IsType<ChatResponseFormatJson>(req.Options.ResponseFormat);
        Assert.Equal(KnowledgeGroundingComposer.SchemaName, format.SchemaName);
    }

    [Fact]
    public void Excluded_facts_are_listed_with_reasons_and_image_rides_along()
    {
        var binding = TestPacks.MikroTikBinding(null);
        var asset = TestPacks.MikroTikAsset(null);
        var r = new KnowledgeResolver(TestPacks.Store(TestPacks.MikroTik)).Resolve(binding, asset, []);
        var req = KnowledgeGroundingComposer.Compose(binding, asset, r, Registry, new Observation("router front", [1, 2, 3], "image/jpeg"));
        var text = string.Join("\n", req.Messages.SelectMany(m => m.Contents.OfType<TextContent>().Select(t => t.Text)));
        Assert.Contains("\"excludedFacts\"", text);
        Assert.Contains("firmware_unknown", text);
        Assert.Contains(req.Messages.SelectMany(m => m.Contents), c => c is DataContent d && d.MediaType == "image/jpeg");
    }

    [Fact]
    public void Proposal_schema_meets_strict_mode_rules()
    {
        var schema = JsonNode.Parse(KnowledgeGroundingComposer.ProposalSchema.Value.GetRawText())!.AsObject();
        AssertStrictObject(schema);
        var text = schema.ToJsonString();
        Assert.DoesNotContain("allOf", text);
        Assert.DoesNotContain("oneOf", text);
        Assert.DoesNotContain("$ref", text);
    }

    private static void AssertStrictObject(JsonObject obj)
    {
        if (obj["type"]?.GetValue<string>() == "object")
        {
            Assert.False(obj["additionalProperties"]!.GetValue<bool>());
            var props = obj["properties"]!.AsObject().Select(p => p.Key).OrderBy(k => k).ToList();
            var required = obj["required"]!.AsArray().Select(n => n!.GetValue<string>()).OrderBy(k => k).ToList();
            Assert.Equal(props, required);
            foreach (var p in obj["properties"]!.AsObject())
                Visit(p.Value!);
        }
    }

    private static void Visit(JsonNode node)
    {
        if (node is JsonObject o)
        {
            if (o["type"] is JsonValue v && v.TryGetValue<string>(out var t) && t == "object") AssertStrictObject(o);
            if (o["anyOf"] is JsonArray any) foreach (var n in any) Visit(n!);
            if (o["items"] is JsonObject items) Visit(items);
        }
    }

    [Fact]
    public async Task Service_accepts_valid_proposal_and_rejects_fabrication_without_acting()
    {
        var (b, a, r) = Synthetic();
        var fake = new FakeChatClient
        {
            CannedResponse = """{"assetId":"LAB-SW-01","usedFactIds":["LAB-PORT-MAP-01"],"hypothesis":"Port not yet confirmed.","requestedVisualEvidence":"Show P1-P3 labels and the yellow cable.","proposedDiagnosticId":null,"nextAction":"RequestView","prediction":null}""",
        };
        var ok = await new GroundedProposalService(fake, Registry).ProposeAsync(b, a, r, new Observation("occluded"));
        Assert.True(ok.Accepted);
        Assert.Equal(VendorGroundedNextAction.RequestView, ok.Proposal!.NextAction);
        Assert.Equal("fake-muse", ok.ModelId);
        Assert.Equal(64, ok.ResponseDigest.Length);

        fake.CannedResponse = """{"assetId":"LAB-SW-01","usedFactIds":["MK-REST-GET-INTERFACE"],"hypothesis":null,"requestedVisualEvidence":null,"proposedDiagnosticId":"POST /rest/system/reboot","nextAction":"RunApprovedDiagnostic","prediction":{"diagnosticId":"POST /rest/system/reboot","predictedValue":"LinkReportedUp","basedOnFactIds":[],"rationale":null}}""";
        var bad = await new GroundedProposalService(fake, Registry).ProposeAsync(b, a, r, new Observation("occluded"));
        Assert.False(bad.Accepted);
        Assert.Null(bad.Proposal);
        var codes = bad.Validation.Rejections.Select(x => x.Code).ToList();
        Assert.Contains(ProposalErrorCodes.InvalidFactReference, codes);
        Assert.Contains(ProposalErrorCodes.ProbeNotAllowlisted, codes);

        fake.CannedResponse = "not json";
        var malformed = await new GroundedProposalService(fake, Registry).ProposeAsync(b, a, r, new Observation("occluded"));
        Assert.False(malformed.Accepted);
        Assert.NotNull(malformed.ParseError);
    }

    [Fact]
    public async Task Unknown_members_in_model_output_are_rejected_strictly()
    {
        var (b, a, r) = Synthetic();
        var fake = new FakeChatClient
        {
            CannedResponse = """{"assetId":"LAB-SW-01","usedFactIds":[],"hypothesis":null,"requestedVisualEvidence":null,"proposedDiagnosticId":null,"nextAction":"Abstain","prediction":null,"shellCommand":"reboot"}""",
        };
        var result = await new GroundedProposalService(fake, Registry).ProposeAsync(b, a, r, new Observation("x"));
        Assert.False(result.Accepted);
        Assert.NotNull(result.ParseError);
    }
}
