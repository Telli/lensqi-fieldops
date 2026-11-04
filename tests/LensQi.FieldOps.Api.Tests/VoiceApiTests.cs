using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using LensQi.FieldOps.Meta.Voice;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace LensQi.FieldOps.Tests;

public class VoiceApiTests
{
    private sealed class FakeVoice : IVoiceTranscriber
    {
        public int Calls;
        public bool Fail;
        public bool Silent;
        public TimeSpan? Delay;
        public async Task<VoiceTranscript> TranscribeAsync(byte[] wav, CancellationToken ct)
        {
            Calls++;
            if (Delay is { } delay) await Task.Delay(delay, ct);
            if (Fail) throw new HttpRequestException("provider echoed secret");
            if (Silent) throw new NoSpeechException();
            return new VoiceTranscript("Reseated cable", "fake-voice");
        }
    }
    private static byte[] Wav(int rate = 24000, short channels = 1, short bits = 16, int samples = 1000)
    {
        using var ms = new MemoryStream();
        using var w = new BinaryWriter(ms);
        w.Write(Encoding.ASCII.GetBytes("RIFF")); w.Write(36 + samples * 2); w.Write(Encoding.ASCII.GetBytes("WAVEfmt "));
        w.Write(16); w.Write((short)1); w.Write(channels); w.Write(rate); w.Write(rate * 2); w.Write((short)2); w.Write(bits);
        w.Write(Encoding.ASCII.GetBytes("data")); w.Write(samples * 2); w.Write(new byte[samples * 2]);
        return ms.ToArray();
    }
    private static async Task<string> Create(HttpClient c) =>
        (await (await c.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" })).Content.ReadFromJsonAsync<JsonElement>()).GetProperty("inspectionId").GetString()!;
    private static async Task<HttpResponseMessage> Upload(HttpClient c, string id, byte[] bytes, string field = "audio")
    {
        using var form = new MultipartFormDataContent { { new ByteArrayContent(bytes), field, "claim.wav" } };
        return await c.PostAsync($"/v1/inspections/{id}/voice/transcribe", form);
    }
    private static WebApplicationFactory<Program> WithVoice(FieldOpsApiFactory f, FakeVoice fake) => f.WithWebHostBuilder(b => b.ConfigureServices(s =>
    {
        s.RemoveAll<IVoiceTranscriber>(); s.AddSingleton<IVoiceTranscriber>(fake);
    }));

    [Fact]
    public async Task Transcription_returns_a_draft_without_mutating_state_events_or_storing_audio()
    {
        using var f = new FieldOpsApiFactory(); var fake = new FakeVoice(); using var app = WithVoice(f, fake); var c = app.CreateClient();
        var id = await Create(c);
        var before = await c.GetStringAsync($"/v1/inspections/{id}/events");
        var r = await Upload(c, id, Wav());
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal("Reseated cable", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("transcript").GetString());
        Assert.Equal(before, await c.GetStringAsync($"/v1/inspections/{id}/events"));
        Assert.DoesNotContain(Directory.EnumerateFiles(f.DataDir, "*", SearchOption.AllDirectories), path => path.EndsWith(".wav"));
        Assert.Equal(1, fake.Calls);
    }

    [Fact]
    public async Task Invalid_audio_missing_file_unknown_inspection_and_provider_down_are_safe_errors()
    {
        using var f = new FieldOpsApiFactory(); var fake = new FakeVoice(); using var app = WithVoice(f, fake); var c = app.CreateClient();
        var id = await Create(c);
        foreach (var bad in new[] { new byte[] { 1, 2, 3 }, Wav(rate: 48000), Wav(channels: 2), Wav(bits: 8), Wav(samples: 0), Wav()[..45], new byte[ClaimAudio.MaxBytes + 1] })
            Assert.Equal(HttpStatusCode.BadRequest, (await Upload(c, id, bad)).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await Upload(c, id, Wav(), "wrong")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await Upload(c, "missing", Wav())).StatusCode);
        Assert.Equal(0, fake.Calls);
        fake.Fail = true;
        var error = await Upload(c, id, Wav());
        Assert.Equal(HttpStatusCode.Conflict, error.StatusCode);
        var body = await error.Content.ReadAsStringAsync();
        Assert.Contains("provider_unavailable", body); Assert.DoesNotContain("secret", body);
    }

    [Fact]
    public async Task Silence_is_its_own_answer_and_changes_nothing()
    {
        using var f = new FieldOpsApiFactory(); var fake = new FakeVoice { Silent = true }; using var app = WithVoice(f, fake); var c = app.CreateClient();
        var id = await Create(c);
        var before = await c.GetStringAsync($"/v1/inspections/{id}/events");
        var r = await Upload(c, id, Wav());
        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal("no_speech", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
        Assert.Equal(before, await c.GetStringAsync($"/v1/inspections/{id}/events"));
    }

    [Fact]
    public async Task A_closed_inspection_refuses_transcription_before_any_model_call()
    {
        using var f = new FieldOpsApiFactory(); var fake = new FakeVoice(); using var app = WithVoice(f, fake); var c = app.CreateClient();
        var id = await Create(c);
        using (var photo = new MultipartFormDataContent { { new ByteArrayContent(File.ReadAllBytes(Path.Combine(RepoPaths.FixturesDir, "..", "telecom-lab", "images", "lab-sw-01-initial-synthetic.jpg"))), "image", "p.jpg" }, { new StringContent("RecordedFixture"), "source" } })
            Assert.Equal(HttpStatusCode.OK, (await c.PostAsync($"/v1/inspections/{id}/observations", photo)).StatusCode);
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new
        {
            assetId = "LAB-SW-01", hypotheses = Array.Empty<object>(), nextAction = "RequestView",
            requestedView = "Show the connector from behind the rack", requestedSegmentationConcept = (string?)null,
        });
        Assert.Equal(HttpStatusCode.OK, (await c.PostAsync($"/v1/inspections/{id}/investigate", null)).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await c.PostAsync($"/v1/inspections/{id}/decline-view", null)).StatusCode);
        var r = await Upload(c, id, Wav());
        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal("invalid_state", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
        Assert.Equal(0, fake.Calls);
    }

    [Fact]
    public async Task Voice_calls_share_the_inspection_rate_limit_and_request_timeout()
    {
        using var f = new FieldOpsApiFactory(); var fake = new FakeVoice();
        using var app = WithVoice(f, fake).WithWebHostBuilder(b => b.UseSetting("Hardening:ModelCallsPerInspection", "1").UseSetting("Hardening:ModelRequestTimeoutSeconds", "1"));
        var c = app.CreateClient(); var id = await Create(c);
        Assert.Equal(HttpStatusCode.OK, (await Upload(c, id, Wav())).StatusCode);
        var limited = await Upload(c, id, Wav());
        Assert.Equal(HttpStatusCode.TooManyRequests, limited.StatusCode);
        Assert.Contains("rate_limited", await limited.Content.ReadAsStringAsync());
        Assert.Equal(1, fake.Calls);
        var slowId = await Create(c);
        fake.Delay = TimeSpan.FromSeconds(30);
        var timedOut = await Upload(c, slowId, Wav());
        Assert.Equal(HttpStatusCode.GatewayTimeout, timedOut.StatusCode);
        Assert.Contains("model_timeout", await timedOut.Content.ReadAsStringAsync());
    }

    [Theory]
    [InlineData(16000)]
    [InlineData(24000)]
    public void Supported_wav_is_bounded_and_checked_from_bytes(int rate)
    {
        Assert.True(ClaimAudio.IsSupported(Wav(rate: rate)));
        Assert.False(ClaimAudio.IsSupported(Wav(rate: rate, samples: rate * 61)));
        var forged = Wav(rate: rate); forged[4] = 0;
        Assert.False(ClaimAudio.IsSupported(forged));
    }
}
