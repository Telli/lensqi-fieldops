using System.Net;
using System.Text.Json.Nodes;
using LensQi.FieldOps.Meta.Muse;
using LensQi.FieldOps.Meta.Segmentation;

namespace LensQi.FieldOps.Tests;

public class SamSegmentationClientTests
{
    private sealed class Stub(Func<HttpRequestMessage, string, HttpResponseMessage> f) : HttpMessageHandler
    {
        public JsonNode? LastBody;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage r, CancellationToken ct)
        { var body = await r.Content!.ReadAsStringAsync(ct); LastBody = JsonNode.Parse(body); return f(r, body); }
    }

    private sealed class FakeParser : ISamOutputParser
    {
        public string? Received;
        public Task<SidecarParse> ParseAsync(string raw, CancellationToken ct)
        {
            Received = raw;
            return Task.FromResult(new SidecarParse(
                [new BoxRecord("0", 304, 585, 630, 968, 0.73)],
                [new MaskRecord("0", 326, 383, 304, 585, 630, 968, new RleMask([383, 326], "abc"), 0.73)],
                "meta-sam-parser test", "sha"));
        }
    }

    private static string Payload(string text) =>
        "{\"id\":\"resp_1\",\"model\":\"sam-3.1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":"
        + System.Text.Json.JsonSerializer.Serialize(text) + "}]}],\"usage\":{\"images_processed\":1}}";

    [Fact]
    public async Task Posts_responses_request_with_one_concept_data_url_and_metadata_then_parses_via_sidecar()
    {
        var stub = new Stub((_, _) => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(Payload("<0f>0<|box;x1=304;y1=585;x2=629;y2=967;w=1600;h=1000|><|mask;x=0;y=0;data=!...|>")) });
        var parser = new FakeParser();
        var client = new SamSegmentationClient(new HttpClient(stub), new SamOptions(), new MuseOptions { ApiKey = "k" }, parser);
        var out_ = await client.SegmentAsync([1, 2, 3], "image/jpeg", "yellow ethernet cable", CancellationToken.None);

        var body = stub.LastBody!;
        Assert.Equal("sam-3.1", body["model"]!.GetValue<string>());
        Assert.False(body["stream"]!.GetValue<bool>());
        Assert.Equal("one_bit", body["metadata"]!["mask_encoding"]!.GetValue<string>());
        Assert.Equal("true", body["metadata"]!["include_confidence"]!.GetValue<string>());
        var content = body["input"]![0]!["content"]!.AsArray();
        Assert.Equal("yellow ethernet cable", content[0]!["text"]!.GetValue<string>());
        Assert.StartsWith("data:image/jpeg;base64,", content[1]!["image_url"]!.GetValue<string>());

        Assert.True(out_.Matched);
        Assert.StartsWith("<0f>0<|box", parser.Received);
        Assert.Equal(0.73, out_.Best!.Confidence);
        Assert.Equal(326, out_.Masks[0].Width);
        Assert.Equal("sam-3.1", out_.ModelId);
        Assert.Equal(64, out_.RawSha256.Length);
    }

    [Fact]
    public async Task Empty_output_is_an_honest_no_match_and_errors_fail_closed()
    {
        var stub = new Stub((_, _) => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(Payload("")) });
        var parser = new FakeParser();
        var client = new SamSegmentationClient(new HttpClient(stub), new SamOptions(), new MuseOptions { ApiKey = "k" }, parser);
        var out_ = await client.SegmentAsync([1], "image/png", "network switch", CancellationToken.None);
        Assert.False(out_.Matched); Assert.Empty(out_.Masks); Assert.Null(parser.Received);

        var bad = new SamSegmentationClient(new HttpClient(new Stub((_, _) => new HttpResponseMessage(HttpStatusCode.BadRequest) { Content = new StringContent("{}") })), new SamOptions(), new MuseOptions { ApiKey = "k" }, parser);
        await Assert.ThrowsAsync<HttpRequestException>(() => bad.SegmentAsync([1], "image/png", "x y", CancellationToken.None));
        var noKey = new SamSegmentationClient(new HttpClient(stub), new SamOptions(), new MuseOptions { ApiKey = null }, parser);
        await Assert.ThrowsAsync<InvalidOperationException>(() => noKey.SegmentAsync([1], "image/png", "x y", CancellationToken.None));
        await Assert.ThrowsAsync<ArgumentException>(() => client.SegmentAsync([1], "image/png", new string('a', 61), CancellationToken.None));
    }
}
