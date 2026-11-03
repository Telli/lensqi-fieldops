using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using LensQi.FieldOps.Api.Infrastructure.Evidence;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Meta.Segmentation;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace LensQi.FieldOps.Tests;

/// <summary>Image headers for tests: enough bytes for the server to read a pixel size, nothing more.</summary>
public static class TestImages
{
    public static byte[] Png(int width, int height, byte salt = 0) =>
    [
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, (byte)'I', (byte)'H', (byte)'D', (byte)'R',
        .. BigEndian(width), .. BigEndian(height), 8, 2, 0, 0, 0, 0, 0, 0, 0, salt,
    ];

    /// <summary>SOI, an APP0 segment, then a baseline SOF0 frame header.</summary>
    public static byte[] Jpeg(int width, int height) =>
    [
        0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, (byte)'J', (byte)'F', (byte)'I', (byte)'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0,
        0xFF, 0xC0, 0x00, 0x11, 0x08, (byte)(height >> 8), (byte)height, (byte)(width >> 8), (byte)width, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1,
    ];

    /// <summary>A JPEG whose APP1 Exif segment carries the given IFD0 orientation, then the frame header (big-endian TIFF).</summary>
    public static byte[] JpegWithOrientation(int width, int height, int orientation) =>
    [
        0xFF, 0xD8,
        0xFF, 0xE1, 0x00, 0x22, (byte)'E', (byte)'x', (byte)'i', (byte)'f', 0, 0,
        (byte)'M', (byte)'M', 0, 0x2A, 0, 0, 0, 8,                 // TIFF header: big-endian, IFD0 at offset 8
        0, 1,                                                      // one entry
        0x01, 0x12, 0, 3, 0, 0, 0, 1, (byte)(orientation >> 8), (byte)orientation, 0, 0,   // Orientation, SHORT, count 1
        0, 0, 0, 0,                                                // no next IFD
        0xFF, 0xC0, 0x00, 0x11, 0x08, (byte)(height >> 8), (byte)height, (byte)(width >> 8), (byte)width, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1,
    ];

    private static byte[] BigEndian(int v) => [(byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v];
}

/// <summary>Stands in for the Pillow sidecar: a turned JPEG is a plain JPEG header with the sides swapped for a quarter turn.</summary>
public sealed class FakeOrienter : IImageOrienter
{
    public int Calls { get; private set; }
    public Task<byte[]?> OrientAsync(byte[] jpeg, int orientation, CancellationToken ct)
    {
        Calls++;
        if (!EvidenceFileStore.TryReadDimensions(jpeg, out var w, out var h)) return Task.FromResult<byte[]?>(null);
        var quarter = orientation is 5 or 6 or 7 or 8;
        return Task.FromResult<byte[]?>(TestImages.Jpeg(quarter ? h : w, quarter ? w : h));
    }
}

public sealed class FakeQualityAnalyzer : IImageAnalyzer
{
    public ImageQualitySignals? Signals { get; set; } = new(812.5, 117.25, "image-quality/fake");
    /// <summary>dHash per uploaded file; by default derived from the bytes, so different files are far apart.</summary>
    public Func<byte[], string?> Hash { get; set; } = bytes => Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(bytes))[..16];
    public async Task<ImageMeasurement?> MeasureAsync(string imagePath, CancellationToken ct) =>
        Signals is null ? null : new ImageMeasurement(Signals, Hash(await File.ReadAllBytesAsync(imagePath, ct)));
}

/// <summary>Records every log message at every level so a test can assert what never reaches a logger.</summary>
public sealed class CapturingLoggerProvider : ILoggerProvider
{
    public ConcurrentQueue<string> Messages { get; } = new();
    public ILogger CreateLogger(string categoryName) => new Logger(this, categoryName);
    public void Dispose() { }

    private sealed class Logger(CapturingLoggerProvider owner, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull { owner.Messages.Enqueue(category + " scope " + state); return null; }
        public bool IsEnabled(LogLevel logLevel) => true;
        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter) =>
            owner.Messages.Enqueue($"{category} {logLevel} {formatter(state, exception)} {exception}");
    }
}

/// <summary>A provider that fails and echoes the whole request back, the worst case for leaking the photo.</summary>
public sealed class EchoingProviderHandler : HttpMessageHandler
{
    public int Calls;
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        Interlocked.Increment(ref Calls);
        var body = request.Content is null ? "" : await request.Content.ReadAsStringAsync(ct);
        return new HttpResponseMessage(HttpStatusCode.BadGateway) { Content = new StringContent("upstream rejected: " + body + " auth=" + request.Headers.Authorization) };
    }
}

public class HardeningTests
{
    private static readonly byte[] Photo = TestImages.Png(640, 480);

    private static WebApplicationFactory<Program> Factory(FieldOpsApiFactory baseFactory, params (string Key, string Value)[] settings) =>
        baseFactory.WithWebHostBuilder(b => { foreach (var (k, v) in settings) b.UseSetting(k, v); });

    private static async Task<JsonElement> Json(HttpResponseMessage r) => JsonDocument.Parse(await r.Content.ReadAsStringAsync()).RootElement;
    private static async Task<string> Code(HttpResponseMessage r) => (await Json(r)).GetProperty("code").GetString()!;

    private static async Task<string> NewInspection(HttpClient c)
    {
        var r = await c.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        Assert.Equal(HttpStatusCode.Created, r.StatusCode);
        return (await Json(r)).GetProperty("inspectionId").GetString()!;
    }

    private static async Task<HttpResponseMessage> Upload(HttpClient c, string id, byte[] bytes, string name = "f.png", params (string Key, string Value)[] fields)
    {
        using var form = new MultipartFormDataContent { { new ByteArrayContent(bytes), "image", name } };
        foreach (var (k, v) in fields) form.Add(new StringContent(v), k);
        return await c.PostAsync($"/v1/inspections/{id}/observations", form);
    }

    private static async Task<JsonElement[]> Events(HttpClient c, string id) =>
        (await Json(await c.GetAsync($"/v1/inspections/{id}/events"))).EnumerateArray().ToArray();

    [Theory]
    [InlineData("png", 640, 480)]
    [InlineData("jpeg", 1600, 1000)]
    [InlineData("png", 240, 320)]
    public void Pixel_size_is_read_from_png_and_jpeg_headers(string kind, int width, int height)
    {
        var bytes = kind == "png" ? TestImages.Png(width, height) : TestImages.Jpeg(width, height);
        Assert.True(EvidenceFileStore.TryReadDimensions(bytes, out var w, out var h));
        Assert.Equal((width, height), (w, h));
    }

    [Fact]
    public void Truncated_or_frameless_headers_have_no_size()
    {
        Assert.False(EvidenceFileStore.TryReadDimensions([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2], out _, out _));
        Assert.False(EvidenceFileStore.TryReadDimensions([0xFF, 0xD8, 0xFF, 0xDA, 0x00, 0x08, 1, 2, 3, 4, 5, 6], out _, out _));
        Assert.False(EvidenceFileStore.TryReadDimensions(TestImages.Jpeg(640, 480)[..24], out _, out _));
        Assert.False(EvidenceFileStore.TryReadDimensions([1, 2, 3, 4], out _, out _));
    }

    [Fact]
    public async Task Upload_records_the_measured_size_and_quality_signals_not_the_declared_size()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await NewInspection(c);
        var r = await Upload(c, id, TestImages.Jpeg(1600, 1000), "f.jpg", ("width", "1"), ("height", "1"));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);

        var photo = (await Events(c, id)).Single(e => e.GetProperty("kind").GetString() == "PhotoRecorded");
        Assert.Equal(1600, photo.GetProperty("width").GetInt32());
        Assert.Equal(1000, photo.GetProperty("height").GetInt32());
        var q = photo.GetProperty("quality");
        Assert.Equal(812.5, q.GetProperty("blurLaplacianVariance").GetDouble());
        Assert.Equal(117.25, q.GetProperty("meanLuminance").GetDouble());
        Assert.Equal("image-quality/fake", (await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("photos")[0].GetProperty("quality").GetProperty("analyzerVersion").GetString());
    }

    [Fact]
    public async Task Missing_quality_signals_never_block_an_upload()
    {
        using var f = new FieldOpsApiFactory();
        f.FakeQuality.Signals = null;
        var c = f.CreateClient();
        var id = await NewInspection(c);
        Assert.Equal(HttpStatusCode.OK, (await Upload(c, id, Photo)).StatusCode);
        var photo = (await Events(c, id)).Single(e => e.GetProperty("kind").GetString() == "PhotoRecorded");
        Assert.Equal(JsonValueKind.Null, photo.GetProperty("quality").ValueKind);
    }

    [Theory]
    [InlineData("MockWearables", "MockWearables")]
    [InlineData("glasses", "Glasses")]
    [InlineData(null, "Phone")]
    public async Task Upload_records_the_claimed_capture_origin(string? source, string expected)
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await NewInspection(c);
        var r = source is null ? await Upload(c, id, Photo) : await Upload(c, id, Photo, "f.png", ("source", source));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal(expected, (await Json(r)).GetProperty("origin").GetString());
    }

    [Theory]
    [InlineData("Muse")]
    [InlineData("SimulatedProbe")]
    [InlineData("4")]
    [InlineData("hololens")]
    public async Task Upload_refuses_a_source_that_is_not_a_capture_origin(string source)
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await NewInspection(c);
        var r = await Upload(c, id, Photo, "f.png", ("source", source));
        Assert.Equal(HttpStatusCode.BadRequest, r.StatusCode);
        Assert.Equal("invalid_source", await Code(r));
        Assert.Empty((await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("photos").EnumerateArray());
    }

    [Fact]
    public async Task Too_small_too_large_and_sizeless_images_are_refused_before_storage()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await NewInspection(c);

        var small = await Upload(c, id, TestImages.Png(300, 200));
        Assert.Equal(HttpStatusCode.BadRequest, small.StatusCode);
        Assert.Equal("image_too_small", await Code(small));

        var large = await Upload(c, id, TestImages.Png(10_000, 6_000));
        Assert.Equal(HttpStatusCode.BadRequest, large.StatusCode);
        Assert.Equal("image_too_large", await Code(large));

        var sizeless = await Upload(c, id, [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 9, 9, 9]);
        Assert.Equal(HttpStatusCode.BadRequest, sizeless.StatusCode);
        Assert.Equal("image_unreadable", await Code(sizeless));

        Assert.Empty((await Json(await c.GetAsync($"/v1/inspections/{id}/artifacts"))).EnumerateArray());
        Assert.Equal("Created", (await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("stage").GetString());
    }

    [Fact]
    public async Task Provider_down_on_every_model_endpoint_is_a_typed_conflict_that_records_nothing()
    {
        using var f = new FieldOpsApiFactory();
        f.FakeChat.Throw = new HttpRequestException("Meta Model API returned 503: overloaded");
        f.FakeSam.Throw = new HttpRequestException("Meta Model API (SAM) returned 503: overloaded");
        var c = f.CreateClient();
        var id = await NewInspection(c);
        Assert.Equal(HttpStatusCode.OK, (await Upload(c, id, Photo)).StatusCode);
        var before = (await Events(c, id)).Length;

        var investigate = await c.PostAsync($"/v1/inspections/{id}/investigate", null);
        Assert.Equal(HttpStatusCode.Conflict, investigate.StatusCode);
        Assert.Equal("provider_unavailable", await Code(investigate));

        var segment = await c.PostAsJsonAsync($"/v1/inspections/{id}/segment", new { concept = "yellow ethernet cable" });
        Assert.Equal(HttpStatusCode.Conflict, segment.StatusCode);
        Assert.Equal("provider_unavailable", await Code(segment));

        var reason = await c.PostAsJsonAsync($"/v1/inspections/{id}/knowledge/reason", new { observation = "link light is off on port 2" });
        Assert.Equal(HttpStatusCode.Conflict, reason.StatusCode);
        Assert.Equal("provider_unavailable", await Code(reason));

        Assert.Equal(before, (await Events(c, id)).Length);
        Assert.Equal("InitialScan", (await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("stage").GetString());
    }

    [Fact]
    public async Task A_model_call_that_outlives_the_request_timeout_is_cancelled_with_model_timeout()
    {
        using var baseFactory = new FieldOpsApiFactory();
        baseFactory.FakeChat.Delay = TimeSpan.FromSeconds(30);
        using var f = Factory(baseFactory, ("Hardening:ModelRequestTimeoutSeconds", "1"));
        var c = f.CreateClient();
        var id = await NewInspection(c);
        Assert.Equal(HttpStatusCode.OK, (await Upload(c, id, Photo)).StatusCode);

        var started = DateTimeOffset.UtcNow;
        var r = await c.PostAsync($"/v1/inspections/{id}/investigate", null);
        Assert.Equal(HttpStatusCode.GatewayTimeout, r.StatusCode);
        Assert.Equal("model_timeout", await Code(r));
        Assert.True(DateTimeOffset.UtcNow - started < TimeSpan.FromSeconds(15));
        Assert.DoesNotContain(await Events(c, id), e => e.GetProperty("kind").GetString() == "AssessmentRecorded");
    }

    [Fact]
    public async Task Model_calls_are_capped_per_inspection_without_affecting_other_inspections()
    {
        using var baseFactory = new FieldOpsApiFactory();
        baseFactory.FakeChat.Throw = new HttpRequestException("down");   // cheap, records nothing, still counts as a model call
        using var f = Factory(baseFactory, ("Hardening:ModelCallsPerInspection", "2"));
        var c = f.CreateClient();
        var a = await NewInspection(c);
        await Upload(c, a, Photo);

        Assert.Equal(HttpStatusCode.Conflict, (await c.PostAsync($"/v1/inspections/{a}/investigate", null)).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await c.PostAsync($"/v1/inspections/{a}/investigate", null)).StatusCode);
        var third = await c.PostAsync($"/v1/inspections/{a}/investigate", null);
        Assert.Equal(HttpStatusCode.TooManyRequests, third.StatusCode);
        Assert.Equal("rate_limited", await Code(third));
        Assert.True(third.Headers.Contains("Retry-After"));

        var b = await NewInspection(c);
        await Upload(c, b, Photo);
        Assert.Equal(HttpStatusCode.Conflict, (await c.PostAsync($"/v1/inspections/{b}/investigate", null)).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await c.GetAsync($"/v1/inspections/{a}")).StatusCode);
    }

    [Fact]
    public async Task Model_calls_are_capped_per_client_address_across_inspections_but_other_endpoints_are_not()
    {
        using var baseFactory = new FieldOpsApiFactory();
        baseFactory.FakeChat.Throw = new HttpRequestException("down");
        using var f = Factory(baseFactory, ("Hardening:ModelCallsPerHourPerIp", "3"));
        var c = f.CreateClient();
        var ids = new List<string>();
        for (var i = 0; i < 4; i++) { var id = await NewInspection(c); await Upload(c, id, Photo); ids.Add(id); }

        for (var i = 0; i < 3; i++)
            Assert.Equal(HttpStatusCode.Conflict, (await c.PostAsync($"/v1/inspections/{ids[i]}/investigate", null)).StatusCode);
        var limited = await c.PostAsJsonAsync($"/v1/inspections/{ids[3]}/segment", new { concept = "yellow ethernet cable" });
        Assert.Equal(HttpStatusCode.TooManyRequests, limited.StatusCode);
        Assert.Equal("rate_limited", await Code(limited));

        Assert.Equal(HttpStatusCode.OK, (await Upload(c, ids[3], TestImages.Png(640, 480, salt: 1))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await c.GetAsync("/health")).StatusCode);
    }

    [Fact]
    public async Task No_image_data_url_or_bearer_token_reaches_a_logger_or_a_response_even_when_the_provider_echoes_the_request()
    {
        var logs = new CapturingLoggerProvider();
        var echo = new EchoingProviderHandler();
        using var baseFactory = new FieldOpsApiFactory();
        using var f = baseFactory.WithWebHostBuilder(b =>
        {
            b.UseSetting("Muse:ApiKey", "sk-test-secret-123");
            b.ConfigureLogging(l => l.AddProvider(logs).SetMinimumLevel(LogLevel.Trace));
            b.ConfigureServices(s =>
            {
                // Back to the real provider clients, over a handler that echoes everything it receives.
                s.AddSingleton<IChatClient>(sp => new LensQi.FieldOps.Meta.Muse.MuseSparkChatClient(sp.GetRequiredService<IHttpClientFactory>().CreateClient("muse"), sp.GetRequiredService<LensQi.FieldOps.Meta.Muse.MuseOptions>()));
                s.AddSingleton<ISegmenter>(sp => new SamSegmentationClient(sp.GetRequiredService<IHttpClientFactory>().CreateClient("sam"), sp.GetRequiredService<SamOptions>(), sp.GetRequiredService<LensQi.FieldOps.Meta.Muse.MuseOptions>(), sp.GetRequiredService<ISamOutputParser>()));
                s.AddHttpClient("muse").ConfigurePrimaryHttpMessageHandler(() => echo);
                s.AddHttpClient("sam").ConfigurePrimaryHttpMessageHandler(() => echo);
            });
        });
        var c = f.CreateClient();
        var id = await NewInspection(c);
        Assert.Equal(HttpStatusCode.OK, (await Upload(c, id, Photo)).StatusCode);

        var investigate = await c.PostAsync($"/v1/inspections/{id}/investigate", null);
        var segment = await c.PostAsJsonAsync($"/v1/inspections/{id}/segment", new { concept = "yellow ethernet cable" });
        Assert.Equal(2, echo.Calls);

        foreach (var r in new[] { investigate, segment })
        {
            Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
            var text = await r.Content.ReadAsStringAsync();
            Assert.Contains("upstream rejected", text);
            Assert.DoesNotContain("base64,", text);
            Assert.DoesNotContain("iVBORw0KGgo", text);   // base64 of the PNG signature
            Assert.DoesNotContain("sk-test-secret-123", text);
        }
        Assert.NotEmpty(logs.Messages);
        Assert.DoesNotContain(logs.Messages, m => m.Contains("base64,") || m.Contains("iVBORw0KGgo") || m.Contains("sk-test-secret-123"));
    }
}
