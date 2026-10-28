using System.Net;
using System.Text.Json.Nodes;
using LensQi.FieldOps.Meta.Muse;
using Microsoft.Extensions.AI;

namespace LensQi.FieldOps.Tests;

public class MuseSparkChatClientTests
{
    private sealed class StubHandler(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
    {
        public HttpRequestMessage? LastRequest { get; private set; }
        public string? LastBody { get; private set; }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            LastRequest = request;
            LastBody = request.Content is null ? null : await request.Content.ReadAsStringAsync(ct);
            return respond(request);
        }
    }

    private static MuseSparkChatClient Client(StubHandler handler, string? key = "test-key") =>
        new(new HttpClient(handler), new MuseOptions { ApiKey = key });

    [Fact]
    public async Task Posts_chat_completions_with_bearer_strict_json_schema_and_image_data_url()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent("""{"id":"r1","model":"muse-spark-1.3","choices":[{"index":0,"message":{"role":"assistant","content":"{\"ok\":true}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}"""),
        });
        var client = Client(handler);
        var schema = System.Text.Json.JsonSerializer.SerializeToElement(new { type = "object", properties = new { ok = new { type = "boolean" } }, required = new[] { "ok" }, additionalProperties = false });
        var messages = new[]
        {
            new ChatMessage(ChatRole.System, "rules"),
            new ChatMessage(ChatRole.User, [new TextContent("look"), new DataContent(new byte[] { 1, 2, 3 }, "image/png")]),
        };

        var response = await client.GetResponseAsync(messages, new ChatOptions { ResponseFormat = ChatResponseFormat.ForJsonSchema(schema, "Probe") });

        Assert.Equal("https://api.meta.ai/v1/chat/completions", handler.LastRequest!.RequestUri!.ToString());
        Assert.Equal("Bearer", handler.LastRequest.Headers.Authorization!.Scheme);
        Assert.Equal("test-key", handler.LastRequest.Headers.Authorization.Parameter);
        var body = JsonNode.Parse(handler.LastBody!)!;
        Assert.Equal("muse-spark-1.3", body["model"]!.GetValue<string>());
        Assert.Equal("system", body["messages"]![0]!["role"]!.GetValue<string>());
        Assert.Equal("rules", body["messages"]![0]!["content"]!.GetValue<string>());
        var parts = body["messages"]![1]!["content"]!.AsArray();
        Assert.Equal("text", parts[0]!["type"]!.GetValue<string>());
        Assert.Equal("image_url", parts[1]!["type"]!.GetValue<string>());
        Assert.StartsWith("data:image/png;base64,", parts[1]!["image_url"]!["url"]!.GetValue<string>());
        Assert.Equal("json_schema", body["response_format"]!["type"]!.GetValue<string>());
        Assert.Equal("Probe", body["response_format"]!["json_schema"]!["name"]!.GetValue<string>());
        Assert.True(body["response_format"]!["json_schema"]!["strict"]!.GetValue<bool>());
        Assert.Equal("object", body["response_format"]!["json_schema"]!["schema"]!["type"]!.GetValue<string>());

        Assert.Equal("{\"ok\":true}", response.Text);
        Assert.Equal("muse-spark-1.3", response.ModelId);
        Assert.Equal(12, response.Usage!.InputTokenCount);
        Assert.Equal(3, response.Usage.OutputTokenCount);
    }

    [Fact]
    public async Task Non_success_status_surfaces_as_http_error_and_missing_key_fails_before_any_call()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.Unauthorized) { Content = new StringContent("{\"error\":\"bad key\"}") });
        var ex = await Assert.ThrowsAsync<HttpRequestException>(() => Client(handler).GetResponseAsync([new ChatMessage(ChatRole.User, "hi")]));
        Assert.Contains("401", ex.Message);

        var untouched = new StubHandler(_ => throw new InvalidOperationException("must not be called"));
        await Assert.ThrowsAsync<InvalidOperationException>(() => Client(untouched, key: null).GetResponseAsync([new ChatMessage(ChatRole.User, "hi")]));
        Assert.Null(untouched.LastRequest);
    }
}
