using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using LensQi.FieldOps.Api.Features.Voice;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace LensQi.FieldOps.Tests;

public class SpeechApiTests
{
    private sealed class FakeSynthesizer : ISpeechSynthesizer
    {
        public readonly List<string> Spoken = [];
        public Exception? Failure;
        public Task<byte[]> SynthesizeAsync(string text, CancellationToken ct)
        {
            Spoken.Add(text);
            return Failure is { } failure ? Task.FromException<byte[]>(failure) : Task.FromResult(new byte[] { 1, 2, 3 });
        }
    }

    private sealed class StubHandler(Func<HttpRequestMessage, HttpResponseMessage> answer) : HttpMessageHandler
    {
        public HttpRequestMessage? Request;
        public string? Body;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Request = request;
            Body = request.Content is null ? null : await request.Content.ReadAsStringAsync(ct);
            return answer(request);
        }
    }

    private static WebApplicationFactory<Program> With(FieldOpsApiFactory f, FakeSynthesizer fake) => f.WithWebHostBuilder(b => b.ConfigureServices(s =>
    {
        s.RemoveAll<ISpeechSynthesizer>(); s.AddSingleton<ISpeechSynthesizer>(fake);
    }));

    private static async Task<string> Code(HttpResponseMessage r) =>
        (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString()!;

    [Fact]
    public async Task Speak_returns_audio_and_stores_nothing()
    {
        using var f = new FieldOpsApiFactory(); var fake = new FakeSynthesizer(); using var app = With(f, fake); var c = app.CreateClient();
        var r = await c.PostAsJsonAsync("/v1/voice/speak", new { text = "Point the camera closer." });
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal("audio/mpeg", r.Content.Headers.ContentType?.MediaType);
        Assert.Equal(new byte[] { 1, 2, 3 }, await r.Content.ReadAsByteArrayAsync());
        Assert.Equal(["Point the camera closer."], fake.Spoken);
        Assert.DoesNotContain(Directory.EnumerateFiles(f.DataDir, "*", SearchOption.AllDirectories), path => path.EndsWith(".mp3"));
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    public async Task Blank_text_is_refused_without_a_paid_call(string text)
    {
        using var f = new FieldOpsApiFactory(); var fake = new FakeSynthesizer(); using var app = With(f, fake); var c = app.CreateClient();
        var r = await c.PostAsJsonAsync("/v1/voice/speak", new { text });
        Assert.Equal(HttpStatusCode.BadRequest, r.StatusCode);
        Assert.Equal("text_invalid", await Code(r));
        Assert.Empty(fake.Spoken);
    }

    [Fact]
    public async Task Text_past_the_limit_is_refused_without_a_paid_call()
    {
        using var f = new FieldOpsApiFactory(); var fake = new FakeSynthesizer(); using var app = With(f, fake); var c = app.CreateClient();
        var r = await c.PostAsJsonAsync("/v1/voice/speak", new { text = new string('a', new SpeechOptions().MaxCharacters + 1) });
        Assert.Equal(HttpStatusCode.BadRequest, r.StatusCode);
        Assert.Equal("text_invalid", await Code(r));
        Assert.Empty(fake.Spoken);
    }

    [Fact]
    public async Task No_key_and_a_provider_failure_are_answers_the_client_can_fall_back_from()
    {
        using var f = new FieldOpsApiFactory(); var fake = new FakeSynthesizer(); using var app = With(f, fake); var c = app.CreateClient();
        fake.Failure = new SpeechNotConfiguredException();
        var none = await c.PostAsJsonAsync("/v1/voice/speak", new { text = "Hello" });
        Assert.Equal(HttpStatusCode.Conflict, none.StatusCode);
        Assert.Equal("speech_not_configured", await Code(none));

        fake.Failure = new HttpRequestException("provider echoed secret");
        var down = await c.PostAsJsonAsync("/v1/voice/speak", new { text = "Hello" });
        Assert.Equal(HttpStatusCode.Conflict, down.StatusCode);
        var body = await down.Content.ReadAsStringAsync();
        Assert.Contains("provider_unavailable", body);
        Assert.DoesNotContain("secret", body);
    }

    [Fact]
    public async Task Speech_has_its_own_limit_and_leaves_speech_input_alone()
    {
        using var f = new FieldOpsApiFactory(); var fake = new FakeSynthesizer();
        using var app = With(f, fake).WithWebHostBuilder(b => b.UseSetting("Hardening:SpeechCallsPerHourPerIp", "1").UseSetting("Hardening:VoiceInputCallsPerHourPerIp", "1"));
        var c = app.CreateClient();
        Assert.Equal(HttpStatusCode.OK, (await c.PostAsJsonAsync("/v1/voice/speak", new { text = "One" })).StatusCode);
        Assert.Equal(HttpStatusCode.TooManyRequests, (await c.PostAsJsonAsync("/v1/voice/speak", new { text = "Two" })).StatusCode);
        Assert.Single(fake.Spoken);
        // Input's budget is untouched: this reaches validation (400), not the limiter (429).
        using var empty = new MultipartFormDataContent();
        Assert.Equal(HttpStatusCode.BadRequest, (await c.PostAsync("/v1/voice/transcribe", empty)).StatusCode);
    }

    [Fact]
    public async Task Synthesizer_sends_the_server_key_voice_and_model()
    {
        var stub = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent([9, 9]) });
        var options = new SpeechOptions { ApiKey = "server-key", VoiceId = "voice-1", Model = "model-1", OutputFormat = "mp3_x" };
        var audio = await new ElevenLabsSynthesizer(new HttpClient(stub), options).SynthesizeAsync("Hello", default);
        Assert.Equal(new byte[] { 9, 9 }, audio);
        Assert.Equal("https://api.elevenlabs.io/v1/text-to-speech/voice-1?output_format=mp3_x", stub.Request!.RequestUri!.ToString());
        Assert.Equal("server-key", stub.Request.Headers.GetValues("xi-api-key").Single());
        using var body = JsonDocument.Parse(stub.Body!);
        Assert.Equal("Hello", body.RootElement.GetProperty("text").GetString());
        Assert.Equal("model-1", body.RootElement.GetProperty("model_id").GetString());
    }

    [Fact]
    public async Task Synthesizer_without_a_key_makes_no_call()
    {
        var stub = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.OK));
        await Assert.ThrowsAsync<SpeechNotConfiguredException>(() => new ElevenLabsSynthesizer(new HttpClient(stub), new SpeechOptions()).SynthesizeAsync("Hello", default));
        Assert.Null(stub.Request);
    }

    [Fact]
    public async Task Synthesizer_keeps_a_provider_error_body_out_of_its_message()
    {
        var stub = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.Unauthorized) { Content = new StringContent("key server-key is wrong") });
        var failure = await Assert.ThrowsAsync<HttpRequestException>(() => new ElevenLabsSynthesizer(new HttpClient(stub), new SpeechOptions { ApiKey = "server-key" }).SynthesizeAsync("Hello", default));
        Assert.Contains("401", failure.Message);
        Assert.DoesNotContain("server-key", failure.Message);
    }

    [Fact]
    public async Task Synthesizer_refuses_an_empty_answer()
    {
        var stub = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent([]) });
        await Assert.ThrowsAsync<InvalidOperationException>(() => new ElevenLabsSynthesizer(new HttpClient(stub), new SpeechOptions { ApiKey = "k" }).SynthesizeAsync("Hello", default));
    }
}
