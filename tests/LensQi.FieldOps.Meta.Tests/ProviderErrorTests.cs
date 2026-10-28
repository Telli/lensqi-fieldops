using System.Net;
using LensQi.FieldOps.Meta.Muse;
using LensQi.FieldOps.Meta.Segmentation;
using Microsoft.Extensions.AI;

namespace LensQi.FieldOps.Tests;

/// <summary>T6.1: provider error text never carries the photo or the key back out of the client.</summary>
public class ProviderErrorTests
{
    private sealed class Echo(HttpStatusCode status) : HttpMessageHandler
    {
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) =>
            new(status) { Content = new StringContent("bad request: " + await request.Content!.ReadAsStringAsync(ct) + " " + request.Headers.Authorization) };
    }

    private sealed class Never : HttpMessageHandler
    {
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            await Task.Delay(Timeout.Infinite, ct);
            throw new InvalidOperationException("unreachable");
        }
    }

    private sealed class NoParser : ISamOutputParser
    {
        public Task<SidecarParse> ParseAsync(string rawOutputText, CancellationToken ct) => throw new InvalidOperationException("not called");
    }

    private static readonly byte[] Image = [0xFF, 0xD8, 0xFF, 0xE0, 0xFB, 0xFF, 0xFE, 0x01, 0x02, 0x03, 0x3E, 0x3F];

    [Theory]
    [InlineData("see data:image/jpeg;base64,/9j/4AAQSkZJRg== end", "see data:[redacted] end")]
    [InlineData("""{"url":"data:image/png;base64,iVBOR+w0\/KGgo="}""", """{"url":"data:[redacted]"}""")]
    [InlineData("Authorization: Bearer sk-live.abc_123/xyz=", "Authorization: Bearer [redacted]")]
    public void Redact_removes_data_urls_including_json_escaped_ones_and_bearer_tokens(string input, string expected) =>
        Assert.Equal(expected, ProviderErrors.Redact(input, 1000));

    [Fact]
    public void Redact_truncates_after_redacting_so_a_cut_never_exposes_a_prefix()
    {
        var text = "x data:image/png;base64," + new string('A', 5000);
        Assert.Equal("x data:[redacted]", ProviderErrors.Redact(text, 600));
        Assert.EndsWith("…", ProviderErrors.Redact(new string('b', 700), 600));
    }

    [Fact]
    public async Task Muse_error_echoing_the_request_carries_neither_image_nor_key()
    {
        var client = new MuseSparkChatClient(new HttpClient(new Echo(HttpStatusCode.ServiceUnavailable)), new MuseOptions { ApiKey = "sk-secret-muse" });
        var ex = await Assert.ThrowsAsync<HttpRequestException>(() => client.GetResponseAsync([new ChatMessage(ChatRole.User, [new TextContent("look"), new DataContent(Image, "image/jpeg")])]));
        Assert.Equal(HttpStatusCode.ServiceUnavailable, ex.StatusCode);
        Assert.Contains("data:[redacted]", ex.Message);
        Assert.DoesNotContain("base64,", ex.Message);
        Assert.DoesNotContain("sk-secret-muse", ex.Message);
    }

    [Fact]
    public async Task Sam_error_echoing_the_request_carries_neither_image_nor_key()
    {
        var client = new SamSegmentationClient(new HttpClient(new Echo(HttpStatusCode.BadGateway)), new SamOptions(), new MuseOptions { ApiKey = "sk-secret-sam" }, new NoParser());
        var ex = await Assert.ThrowsAsync<HttpRequestException>(() => client.SegmentAsync(Image, "image/jpeg", "yellow ethernet cable", CancellationToken.None));
        Assert.Contains("data:[redacted]", ex.Message);
        Assert.DoesNotContain("base64,", ex.Message);
        Assert.DoesNotContain("sk-secret-sam", ex.Message);
    }

    [Fact]
    public async Task Provider_timeout_and_caller_cancellation_are_distinguishable()
    {
        var timesOut = new MuseSparkChatClient(new HttpClient(new Never()), new MuseOptions { ApiKey = "k", TimeoutSeconds = 1 });
        var timeout = await Assert.ThrowsAsync<TaskCanceledException>(() => timesOut.GetResponseAsync([new ChatMessage(ChatRole.User, "hi")]));
        Assert.IsType<TimeoutException>(timeout.InnerException);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(100));
        var patient = new MuseSparkChatClient(new HttpClient(new Never()), new MuseOptions { ApiKey = "k", TimeoutSeconds = 60 });
        var cancelled = await Assert.ThrowsAnyAsync<OperationCanceledException>(() => patient.GetResponseAsync([new ChatMessage(ChatRole.User, "hi")], cancellationToken: cts.Token));
        Assert.True(cts.IsCancellationRequested);
        Assert.IsNotType<TimeoutException>(cancelled.InnerException);
    }
}
