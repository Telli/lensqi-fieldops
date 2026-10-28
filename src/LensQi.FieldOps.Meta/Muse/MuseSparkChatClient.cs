using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using Microsoft.Extensions.AI;

namespace LensQi.FieldOps.Meta.Muse;

/// <summary>
/// <see cref="IChatClient"/> over the Meta Model API Chat Completions endpoint (<c>POST {base}/chat/completions</c>).
/// Hand-rolled on HttpClient so the request shape, the structured-output contract and cost accounting are explicit
/// and auditable. Supports text and image (DataContent → data URL) input and JSON-schema structured output via
/// <see cref="ChatResponseFormatJson"/>. No tools, no streaming beyond a single-chunk shim.
/// </summary>
public sealed class MuseSparkChatClient : IChatClient
{
    private static readonly JsonSerializerOptions Wire = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly HttpClient _http;
    private readonly MuseOptions _options;

    public MuseSparkChatClient(HttpClient http, MuseOptions options)
    {
        _http = http;
        _options = options;
        if (_http.BaseAddress is null)
            _http.BaseAddress = new Uri(_options.BaseUrl.EndsWith('/') ? _options.BaseUrl : _options.BaseUrl + "/");
        _http.Timeout = TimeSpan.FromSeconds(_options.TimeoutSeconds);
    }

    public async Task<ChatResponse> GetResponseAsync(IEnumerable<ChatMessage> messages, ChatOptions? options = null, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(_options.ApiKey))
            throw new InvalidOperationException($"Muse API key is not configured. Set {MuseOptions.ApiKeyEnvironmentVariable}.");

        var body = BuildRequest(messages, options);
        using var request = new HttpRequestMessage(HttpMethod.Post, "chat/completions")
        {
            Content = JsonContent.Create(body, options: Wire),
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _options.ApiKey);

        using var response = await _http.SendAsync(request, cancellationToken).ConfigureAwait(false);
        var payload = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
            throw new HttpRequestException($"Meta Model API returned {(int)response.StatusCode}: {ProviderErrors.Redact(payload, 600)}", null, response.StatusCode);

        var completion = JsonSerializer.Deserialize<ChatCompletionResponse>(payload, Wire)
                         ?? throw new InvalidOperationException("Meta Model API returned an empty body.");
        var text = completion.Choices?.FirstOrDefault()?.Message?.Content ?? string.Empty;

        return new ChatResponse(new ChatMessage(ChatRole.Assistant, text))
        {
            ModelId = completion.Model ?? _options.Model,
            ResponseId = completion.Id,
            FinishReason = completion.Choices?.FirstOrDefault()?.FinishReason switch
            {
                "length" => ChatFinishReason.Length,
                "content_filter" => ChatFinishReason.ContentFilter,
                _ => ChatFinishReason.Stop,
            },
            Usage = completion.Usage is null ? null : new UsageDetails
            {
                InputTokenCount = completion.Usage.PromptTokens,
                OutputTokenCount = completion.Usage.CompletionTokens,
                TotalTokenCount = completion.Usage.TotalTokens,
                AdditionalCounts = completion.Usage.CompletionTokensDetails?.ReasoningTokens is { } rt
                    ? new AdditionalPropertiesDictionary<long> { ["reasoning_tokens"] = rt } : null,
            },
        };
    }

    public async IAsyncEnumerable<ChatResponseUpdate> GetStreamingResponseAsync(IEnumerable<ChatMessage> messages, ChatOptions? options = null,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        var response = await GetResponseAsync(messages, options, cancellationToken).ConfigureAwait(false);
        foreach (var update in response.ToChatResponseUpdates())
            yield return update;
    }

    public object? GetService(Type serviceType, object? serviceKey = null) =>
        serviceKey is null && serviceType.IsInstanceOfType(this) ? this : null;

    public void Dispose() { }

    /// <summary>Exposed for tests: the exact wire request for a given message set and options.</summary>
    public JsonNode BuildRequestForInspection(IEnumerable<ChatMessage> messages, ChatOptions? options) =>
        JsonSerializer.SerializeToNode(BuildRequest(messages, options), Wire)!;

    private ChatCompletionRequest BuildRequest(IEnumerable<ChatMessage> messages, ChatOptions? options)
    {
        var wireMessages = messages.Select(ToWireMessage).ToList();
        ResponseFormat? format = null;
        if (options?.ResponseFormat is ChatResponseFormatJson json)
        {
            format = json.Schema is { } schema
                ? new ResponseFormat("json_schema", new JsonSchemaFormat(json.SchemaName ?? "response", schema, true))
                : new ResponseFormat("json_object", null);
        }

        return new ChatCompletionRequest(
            Model: options?.ModelId ?? _options.Model,
            Messages: wireMessages,
            Temperature: options?.Temperature ?? (float)_options.Temperature,
            MaxTokens: options?.MaxOutputTokens ?? _options.MaxOutputTokens,
            ResponseFormat: format,
            ReasoningEffort: _options.ReasoningEffort);
    }

    private static WireMessage ToWireMessage(ChatMessage message)
    {
        var role = message.Role == ChatRole.System ? "system" : message.Role == ChatRole.Assistant ? "assistant" : "user";
        var parts = new List<object>();
        foreach (var content in message.Contents)
        {
            switch (content)
            {
                case TextContent text:
                    parts.Add(new { type = "text", text = text.Text });
                    break;
                case DataContent data when data.HasTopLevelMediaType("image"):
                    parts.Add(new { type = "image_url", image_url = new { url = data.Uri } });
                    break;
                case UriContent uri when uri.HasTopLevelMediaType("image"):
                    parts.Add(new { type = "image_url", image_url = new { url = uri.Uri.ToString() } });
                    break;
            }
        }
        // System messages are plain strings; user/assistant carry content parts so images can ride along.
        return role == "system"
            ? new WireMessage(role, string.Concat(message.Contents.OfType<TextContent>().Select(t => t.Text)))
            : new WireMessage(role, parts);
    }

    private sealed record ChatCompletionRequest(
        string Model,
        IReadOnlyList<WireMessage> Messages,
        float? Temperature,
        [property: JsonPropertyName("max_tokens")] int? MaxTokens,
        [property: JsonPropertyName("response_format")] ResponseFormat? ResponseFormat,
        [property: JsonPropertyName("reasoning_effort")] string? ReasoningEffort);

    private sealed record WireMessage(string Role, object Content);

    private sealed record ResponseFormat(string Type, [property: JsonPropertyName("json_schema")] JsonSchemaFormat? JsonSchema);

    private sealed record JsonSchemaFormat(string Name, JsonElement Schema, bool Strict);

    private sealed record ChatCompletionResponse(string? Id, string? Model, IReadOnlyList<Choice>? Choices, Usage? Usage);
    private sealed record Choice(int Index, ChoiceMessage? Message, [property: JsonPropertyName("finish_reason")] string? FinishReason);
    private sealed record ChoiceMessage(string? Role, string? Content);
    private sealed record Usage(
        [property: JsonPropertyName("prompt_tokens")] long? PromptTokens,
        [property: JsonPropertyName("completion_tokens")] long? CompletionTokens,
        [property: JsonPropertyName("total_tokens")] long? TotalTokens,
        [property: JsonPropertyName("completion_tokens_details")] CompletionDetails? CompletionTokensDetails);
    private sealed record CompletionDetails([property: JsonPropertyName("reasoning_tokens")] long? ReasoningTokens);
}
