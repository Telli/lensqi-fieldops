using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace LensQi.FieldOps.Api.Features.Voice;

public sealed class SpeechOptions
{
    public const string SectionName = "Speech";
    public const string ApiKeyEnvironmentVariable = "ELEVENLABS_API_KEY";
    /// <summary>From server configuration or the environment only; a client never sees it or sends it.</summary>
    public string? ApiKey { get; set; }
    public string BaseUrl { get; set; } = "https://api.elevenlabs.io/";
    public string VoiceId { get; set; } = "hGVeHdJEcfHv6D6RUSiY";
    public string Model { get; set; } = "eleven_flash_v2_5";
    public string OutputFormat { get; set; } = "mp3_44100_64";
    public int TimeoutSeconds { get; set; } = 8;
    public int MaxCharacters { get; set; } = 1500;
}

/// <summary>No key is configured: the client uses the device's own voice, and nothing is wrong.</summary>
public sealed class SpeechNotConfiguredException() : Exception("Speech output is not configured");

public interface ISpeechSynthesizer
{
    Task<byte[]> SynthesizeAsync(string text, CancellationToken ct);
}

/// <summary>Reads text aloud with ElevenLabs. Stores nothing, and never reads a provider error body into a message.</summary>
public sealed class ElevenLabsSynthesizer(HttpClient http, SpeechOptions options) : ISpeechSynthesizer
{
    public async Task<byte[]> SynthesizeAsync(string text, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(options.ApiKey)) throw new SpeechNotConfiguredException();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(options.TimeoutSeconds));
        var root = options.BaseUrl.EndsWith('/') ? options.BaseUrl : options.BaseUrl + "/";
        var path = $"v1/text-to-speech/{Uri.EscapeDataString(options.VoiceId)}?output_format={Uri.EscapeDataString(options.OutputFormat)}";
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(new Uri(root), path));
        request.Headers.Add("xi-api-key", options.ApiKey);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("audio/mpeg"));
        request.Content = new StringContent(JsonSerializer.Serialize(new Dictionary<string, string> { ["text"] = text, ["model_id"] = options.Model }), Encoding.UTF8, "application/json");
        using var response = await http.SendAsync(request, timeout.Token);
        if (!response.IsSuccessStatusCode) throw new HttpRequestException($"Speech provider returned HTTP {(int)response.StatusCode}");
        var audio = await response.Content.ReadAsByteArrayAsync(timeout.Token);
        return audio.Length > 0 ? audio : throw new InvalidOperationException("Speech provider returned no audio");
    }
}
