using System.Diagnostics;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using LensQi.FieldOps.Meta.Muse;

namespace LensQi.FieldOps.Meta.Segmentation;

/// <summary>
/// sam-3.1 over POST {base}/responses (Responses API, non-streaming) with one noun phrase and the image as a data URL.
/// The special-token output_text goes to the sidecar parser untouched. An empty output is an honest no-match.
/// </summary>
public sealed class SamSegmentationClient : ISegmenter
{
    private static readonly JsonSerializerOptions Wire = new(JsonSerializerDefaults.Web) { DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull };
    private readonly HttpClient _http;
    private readonly SamOptions _options;
    private readonly string? _apiKey;
    private readonly ISamOutputParser _parser;

    public SamSegmentationClient(HttpClient http, SamOptions options, MuseOptions keySource, ISamOutputParser parser)
    {
        _http = http; _options = options; _apiKey = keySource.ApiKey; _parser = parser;
        _http.BaseAddress ??= new Uri(options.BaseUrl.EndsWith('/') ? options.BaseUrl : options.BaseUrl + "/");
        _http.Timeout = TimeSpan.FromSeconds(options.TimeoutSeconds);
    }

    public async Task<SegmentationOutcome> SegmentAsync(byte[] image, string mediaType, string concept, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(_apiKey))
            throw new InvalidOperationException($"Model API key is not configured. Set {MuseOptions.ApiKeyEnvironmentVariable}.");
        if (string.IsNullOrWhiteSpace(concept) || concept.Length > 60)
            throw new ArgumentException("concept must be one short noun phrase", nameof(concept));

        var body = new
        {
            model = _options.Model,
            input = new object[]
            {
                new { type = "message", role = "user", content = new object[]
                {
                    new { type = "input_text", text = concept },
                    new { type = "input_image", image_url = $"data:{mediaType};base64,{Convert.ToBase64String(image)}" },
                } },
            },
            stream = false,
            metadata = new { mask_encoding = _options.MaskEncoding, include_confidence = _options.IncludeConfidence ? "true" : "false" },
        };
        using var req = new HttpRequestMessage(HttpMethod.Post, "responses") { Content = JsonContent.Create(body, options: Wire) };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _apiKey);
        var sw = Stopwatch.StartNew();
        using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
        var payload = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode)
            throw new HttpRequestException($"Meta Model API (SAM) returned {(int)resp.StatusCode}: {ProviderErrors.Redact(payload, 400)}", null, resp.StatusCode);
        sw.Stop();

        using var doc = JsonDocument.Parse(payload);
        var root = doc.RootElement;
        var sb = new StringBuilder();
        if (root.TryGetProperty("output", out var output))
            foreach (var item in output.EnumerateArray())
                if (item.TryGetProperty("content", out var content))
                    foreach (var c in content.EnumerateArray())
                        if (c.TryGetProperty("type", out var t) && t.GetString() == "output_text")
                            sb.Append(c.GetProperty("text").GetString());
        var raw = sb.ToString();
        var modelId = root.TryGetProperty("model", out var m) ? m.GetString() ?? _options.Model : _options.Model;
        var responseId = root.TryGetProperty("id", out var idEl) ? idEl.GetString() : null;

        if (string.IsNullOrWhiteSpace(raw))
            return new SegmentationOutcome(concept, modelId, responseId, raw, Sha(raw), [], [], "n/a (empty output)", sw.Elapsed.TotalSeconds);

        var parsed = await _parser.ParseAsync(raw, ct).ConfigureAwait(false);
        return new SegmentationOutcome(concept, modelId, responseId, raw, Sha(raw), parsed.Boxes, parsed.Masks, parsed.ParserVersion, sw.Elapsed.TotalSeconds);
    }

    private static string Sha(string s) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(s)));
}
