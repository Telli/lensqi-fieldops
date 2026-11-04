using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using LensQi.FieldOps.Meta.Muse;

namespace LensQi.FieldOps.Meta.Voice;

public sealed class VoiceOptions
{
    public const string SectionName = "Voice";
    public string Model { get; set; } = "muse-voice-transcribe-1.0";
    public int TimeoutSeconds { get; set; } = 60;
}

public sealed record VoiceTranscript(string Transcript, string ModelId);

/// <summary>The provider answered and heard no words: the person can record again; nothing failed.</summary>
public sealed class NoSpeechException() : Exception("No speech was recognized");

public interface IVoiceTranscriber
{
    Task<VoiceTranscript> TranscribeAsync(byte[] wav, CancellationToken ct);
}

/// <summary>File transcription only. A transcript is an unconfirmed draft, never an inspection command.</summary>
public sealed class VoiceTranscriber(HttpClient http, MuseOptions muse, VoiceOptions options) : IVoiceTranscriber
{
    public async Task<VoiceTranscript> TranscribeAsync(byte[] wav, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(muse.ApiKey)) throw new InvalidOperationException("Voice transcription is not configured");
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(options.TimeoutSeconds));
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(new Uri(muse.BaseUrl.EndsWith('/') ? muse.BaseUrl : muse.BaseUrl + "/"), "asr/transcribe"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", muse.ApiKey);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        using var form = new MultipartFormDataContent();
        form.Add(new StringContent(JsonSerializer.Serialize(new { model = options.Model, audioEncoding = "WAV", mode = "PUSH_TO_TALK" }), Encoding.UTF8, "application/json"), "request");
        var audio = new ByteArrayContent(wav);
        audio.Headers.ContentType = new MediaTypeHeaderValue("audio/wav");
        form.Add(audio, "audio", "claim.wav");
        request.Content = form;
        using var response = await http.SendAsync(request, timeout.Token);
        // Do not expose provider bodies: they may echo audio, credentials, or transcript text.
        if (!response.IsSuccessStatusCode) throw new HttpRequestException($"Voice provider returned HTTP {(int)response.StatusCode}");
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(timeout.Token));
        if (!json.RootElement.TryGetProperty("transcript", out var value) || value.ValueKind != JsonValueKind.String)
            throw new InvalidOperationException("Voice provider returned no transcript");
        var transcript = value.GetString()?.Trim();
        if (string.IsNullOrEmpty(transcript)) throw new NoSpeechException();
        if (transcript.Length > 4000) throw new InvalidOperationException("Transcript exceeds 4000 characters; record a shorter claim");
        return new VoiceTranscript(transcript, options.Model);
    }
}
