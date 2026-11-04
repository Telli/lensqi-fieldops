using System.Net;
using System.Text.Json;
using LensQi.FieldOps.Meta.Muse;
using LensQi.FieldOps.Meta.Voice;

namespace LensQi.FieldOps.Tests;

public class VoiceTranscriberTests
{
    private sealed class Handler(Func<HttpRequestMessage, Task<HttpResponseMessage>> send) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) => send(request);
    }

    [Fact]
    public async Task Posts_the_documented_push_to_talk_contract_and_returns_only_draft_text()
    {
        var handler = new Handler(async request =>
        {
            Assert.Equal("https://api.meta.ai/v1/asr/transcribe", request.RequestUri!.ToString());
            Assert.Equal("Bearer test", request.Headers.Authorization!.ToString());
            Assert.Equal("application/json", request.Headers.Accept.Single().MediaType);
            var parts = Assert.IsType<MultipartFormDataContent>(request.Content).ToArray();
            var settings = JsonDocument.Parse(await parts[0].ReadAsStringAsync()).RootElement;
            Assert.Equal("muse-voice-transcribe-1.0", settings.GetProperty("model").GetString());
            Assert.Equal("WAV", settings.GetProperty("audioEncoding").GetString());
            Assert.Equal("PUSH_TO_TALK", settings.GetProperty("mode").GetString());
            Assert.Equal("audio", parts[1].Headers.ContentDisposition!.Name!.Trim('"'));
            Assert.Equal("audio/wav", parts[1].Headers.ContentType!.MediaType);
            Assert.Equal(new byte[] { 1, 2, 3 }, await parts[1].ReadAsByteArrayAsync());
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("""{"transcript":" Reseated cable. ","audioDurationMs":1000,"turns":[]}""") };
        });
        var client = new VoiceTranscriber(new HttpClient(handler), new MuseOptions { ApiKey = "test" }, new VoiceOptions());
        Assert.Equal(new VoiceTranscript("Reseated cable.", "muse-voice-transcribe-1.0"), await client.TranscribeAsync([1, 2, 3], default));
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("{\"transcript\":123}")]
    public async Task A_response_without_a_transcript_is_rejected(string body)
    {
        var handler = new Handler(_ => Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body) }));
        var client = new VoiceTranscriber(new HttpClient(handler), new MuseOptions { ApiKey = "test" }, new VoiceOptions());
        await Assert.ThrowsAsync<InvalidOperationException>(() => client.TranscribeAsync([1], default));
    }

    [Fact]
    public async Task An_empty_transcript_means_no_speech()
    {
        var handler = new Handler(_ => Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{\"transcript\":\"   \"}") }));
        var client = new VoiceTranscriber(new HttpClient(handler), new MuseOptions { ApiKey = "test" }, new VoiceOptions());
        await Assert.ThrowsAsync<NoSpeechException>(() => client.TranscribeAsync([1], default));
    }

    [Fact]
    public async Task A_base_url_without_a_trailing_slash_keeps_its_version_path()
    {
        Uri? seen = null;
        var handler = new Handler(request => { seen = request.RequestUri; return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{\"transcript\":\"Done\"}") }); });
        var client = new VoiceTranscriber(new HttpClient(handler), new MuseOptions { ApiKey = "test", BaseUrl = "https://api.meta.ai/v1" }, new VoiceOptions());
        await client.TranscribeAsync([1], default);
        Assert.Equal("https://api.meta.ai/v1/asr/transcribe", seen!.ToString());
    }

    [Fact]
    public async Task Provider_errors_never_echo_sensitive_bodies_and_missing_key_makes_no_call()
    {
        var calls = 0;
        var handler = new Handler(_ => { calls++; return Task.FromResult(new HttpResponseMessage(HttpStatusCode.BadGateway) { Content = new StringContent("secret echoed audio") }); });
        var client = new VoiceTranscriber(new HttpClient(handler), new MuseOptions { ApiKey = "test" }, new VoiceOptions());
        var error = await Assert.ThrowsAsync<HttpRequestException>(() => client.TranscribeAsync([1], default));
        Assert.DoesNotContain("secret", error.Message);
        var unconfigured = new VoiceTranscriber(new HttpClient(handler), new MuseOptions(), new VoiceOptions());
        await Assert.ThrowsAsync<InvalidOperationException>(() => unconfigured.TranscribeAsync([1], default));
        Assert.Equal(1, calls);
    }
}
