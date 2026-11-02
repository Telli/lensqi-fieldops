using System.Net;
using System.Net.Http.Json;
using System.Text.Json;

using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Diagnostics;
using LensQi.FieldOps.Meta.Segmentation;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace LensQi.FieldOps.Tests;

/// <summary>Drives the real API over HTTP with the simulated probe and a fake Muse: the PRD hero demo without hardware or paid calls.</summary>
public sealed class FieldOpsApiFactory : WebApplicationFactory<Program>
{
    public string DataDir { get; }

    // xUnit class fixtures need exactly one public constructor.
    public FieldOpsApiFactory() : this(null) { }

    private FieldOpsApiFactory(string? dataDir) => DataDir = dataDir ?? Directory.CreateTempSubdirectory("fieldops-api-").FullName;

    /// <summary>A second API over an existing data directory (restart tests).</summary>
    public static FieldOpsApiFactory Over(string dataDir) => new(dataDir);
    public FakeVisionChat FakeChat { get; } = new();
    public FakeSegmenter FakeSam { get; } = new();
    public FakeQualityAnalyzer FakeQuality { get; } = new();

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Testing");
        builder.UseSetting("Storage:DataDirectory", DataDir);
        builder.UseSetting("Diagnostics:Mode", "Simulated");
        builder.ConfigureServices(services =>
        {
            services.RemoveAll<IChatClient>();
            services.AddSingleton<IChatClient>(FakeChat);
            services.RemoveAll<ISegmenter>();
            services.AddSingleton<ISegmenter>(FakeSam);
            services.RemoveAll<IRoiCropper>();
            services.AddSingleton<IRoiCropper>(new FakeCropper());
            services.RemoveAll<IImageAnalyzer>();
            services.AddSingleton<IImageAnalyzer>(FakeQuality);
        });
    }
}

public sealed class FakeSegmenter : ISegmenter
{
    public bool Match { get; set; } = true;
    public Exception? Throw { get; set; }
    public int Calls { get; private set; }
    public List<string> Concepts { get; } = [];
    /// <summary>When non-empty, each call returns the next queued mask set (empty = honest no-match).</summary>
    public Queue<MaskRecord[]> Queued { get; } = new();
    public Task<SegmentationOutcome> SegmentAsync(byte[] image, string mediaType, string concept, CancellationToken ct)
    {
        Calls++;
        lock (Concepts) Concepts.Add(concept);
        if (Queued.TryDequeue(out var masks))
            return Task.FromResult(new SegmentationOutcome(concept, "sam-3.1", "rq", masks.Length > 0 ? "<queued>" : "", "raw",
                masks.Select(m => new BoxRecord(m.ObjectId, m.Left, m.Top, m.Right, m.Bottom, m.Confidence)).ToList(), masks, "fake", 0.1));
        return Throw is { } ex ? Task.FromException<SegmentationOutcome>(ex) : Task.FromResult(Match
            ? new SegmentationOutcome(concept, "sam-3.1", "r1", "<0f>0<|box;...|>", "raw", [new BoxRecord("0", 10, 10, 30, 30, 0.9)], [new MaskRecord("0", 20, 20, 10, 10, 30, 30, new RleMask([20, 20], "0"), 0.9)], "fake", 0.1)
            : new SegmentationOutcome(concept, "sam-3.1", "r2", "", "raw", [], [], "n/a", 0.1));
    }
}

public sealed class FakeCropper : IRoiCropper
{
    public Task<CropResult> CropAsync(string sourcePath, string sourceEvidenceId, string sourceSha256, PixelBox box, string outputPath, CancellationToken ct)
    {
        File.WriteAllBytes(outputPath, [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1]);
        return Task.FromResult(new CropResult(outputPath, new CropTransform(sourceEvidenceId, sourceSha256, 100, 100, box, 1.0)));
    }
}

public sealed class FakeVisionChat : IChatClient
{
    public string CannedResponse { get; set; } = "{}";
    /// <summary>When non-empty, each call answers with the next queued response before falling back to <see cref="CannedResponse"/>.</summary>
    public Queue<string> Responses { get; } = new();
    public int Images { get; private set; }
    public int Calls { get; private set; }
    /// <summary>Every text part of the last request: system prompt, context and ask.</summary>
    public string LastText { get; private set; } = "";
    /// <summary>The first image of the last request.</summary>
    public byte[]? LastImage { get; private set; }
    public Exception? Throw { get; set; }
    /// <summary>When set, the call waits this long (honouring cancellation) before answering.</summary>
    public TimeSpan? Delay { get; set; }
    public async Task<ChatResponse> GetResponseAsync(IEnumerable<ChatMessage> messages, ChatOptions? options = null, CancellationToken cancellationToken = default)
    {
        Images += messages.SelectMany(m => m.Contents).OfType<DataContent>().Count();
        Calls++;
        LastText = string.Join("\n", messages.SelectMany(m => m.Contents).OfType<TextContent>().Select(t => t.Text));
        LastImage = messages.SelectMany(m => m.Contents).OfType<DataContent>().FirstOrDefault()?.Data.ToArray();
        if (Delay is { } d) await Task.Delay(d, cancellationToken);
        if (Throw is { } ex) throw ex;
        return new ChatResponse(new ChatMessage(ChatRole.Assistant, Responses.TryDequeue(out var next) ? next : CannedResponse)) { ModelId = "fake-muse" };
    }
    public IAsyncEnumerable<ChatResponseUpdate> GetStreamingResponseAsync(IEnumerable<ChatMessage> messages, ChatOptions? options = null, CancellationToken cancellationToken = default) => throw new NotSupportedException();
    public object? GetService(Type serviceType, object? serviceKey = null) => null;
    public void Dispose() { }
}

public class InspectionApiTests : IClassFixture<FieldOpsApiFactory>
{
    private readonly FieldOpsApiFactory _factory;
    private readonly HttpClient _client;
    private static readonly byte[] Png = TestImages.Png(640, 480);

    public InspectionApiTests(FieldOpsApiFactory factory) { _factory = factory; _client = factory.CreateClient(); }

    private static async Task<JsonElement> Json(HttpResponseMessage r)
    {
        var text = await r.Content.ReadAsStringAsync();
        if (string.IsNullOrWhiteSpace(text)) throw new Xunit.Sdk.XunitException($"{r.RequestMessage?.Method} {r.RequestMessage?.RequestUri} -> {(int)r.StatusCode} with empty body");
        return JsonDocument.Parse(text).RootElement;
    }

    private async Task<string> CreateInspection()
    {
        var r = await _client.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        Assert.Equal(HttpStatusCode.Created, r.StatusCode);
        return (await Json(r)).GetProperty("inspectionId").GetString()!;
    }

    private async Task<string> Upload(string id, byte[]? bytes = null, string source = "Phone")
    {
        using var form = new MultipartFormDataContent();
        form.Add(new ByteArrayContent(bytes ?? Png), "image", "frame.png");
        form.Add(new StringContent(source), "source");
        var r = await _client.PostAsync($"/v1/inspections/{id}/observations", form);
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        return (await Json(r)).GetProperty("evidenceId").GetString()!;
    }

    private async Task SetSimulation(bool passed) =>
        Assert.Equal(HttpStatusCode.OK, (await _client.PostAsJsonAsync("/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed })).StatusCode);

    private async Task<JsonElement> Post(string url, object? body = null)
    {
        var r = body is null ? await _client.PostAsync(url, null) : await _client.PostAsJsonAsync(url, body);
        var j = await Json(r);
        Assert.True(r.IsSuccessStatusCode, $"{url} -> {r.StatusCode}: {j}");
        return j;
    }

    [Fact]
    public async Task Health_and_fixtures_report_the_installed_twin()
    {
        var h = await Json(await _client.GetAsync("/health"));
        Assert.Equal("ok", h.GetProperty("status").GetString());
        Assert.Equal(1, h.GetProperty("fixtures").GetInt32());
        Assert.Equal("Simulated", h.GetProperty("diagnosticsMode").GetString());
        var f = await Json(await _client.GetAsync("/v1/fixtures"));
        Assert.Equal("LAB-RACK-01", f[0].GetProperty("fixtureId").GetString());
        Assert.Equal(64, f[0].GetProperty("sha256").GetString()!.Length);
    }

    [Fact]
    public async Task Unknown_asset_bad_image_and_foreign_probe_are_refused()
    {
        var bad = await _client.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-99" });
        Assert.Equal(HttpStatusCode.Conflict, bad.StatusCode);
        Assert.Equal(InspectionErrorCodes.UnknownAsset, (await Json(bad)).GetProperty("code").GetString());

        var id = await CreateInspection();
        using var form = new MultipartFormDataContent { { new ByteArrayContent([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]), "image", "x.png" } };
        Assert.Equal(HttpStatusCode.BadRequest, (await _client.PostAsync($"/v1/inspections/{id}/observations", form)).StatusCode);

        var probe = await _client.PostAsync($"/v1/inspections/{id}/diagnostics/{Uri.EscapeDataString("http://10.0.0.1/health")}:run", null);
        Assert.Equal(HttpStatusCode.Conflict, probe.StatusCode);
        Assert.Equal(InspectionErrorCodes.ProbeNotAllowlisted, (await Json(probe)).GetProperty("code").GetString());
    }

    [Fact]
    public async Task Hero_flow_fake_fix_rejected_then_real_fix_simulated_verified_over_http()
    {
        await SetSimulation(false);
        var id = await CreateInspection();
        var p0 = await Upload(id);

        // Muse (fake) says the connector is unknown and asks for a view
        _factory.FakeChat.CannedResponse = JsonSerializer.Serialize(new
        {
            assetId = "LAB-SW-01",
            hypotheses = new[] { new { featureId = "yellow_cable_near_end_visible", status = "Unknown", reason = "occluded", evidenceIds = new[] { p0 } } },
            nextAction = "RequestView", requestedView = "Move closer and show the switch faceplate and the yellow cable connector", requestedSegmentationConcept = "yellow ethernet cable",
        });
        var inv = await Post($"/v1/inspections/{id}/investigate");
        Assert.Equal("NeedsEvidence", inv.GetProperty("state").GetProperty("stage").GetString());
        Assert.Equal("fake-muse", inv.GetProperty("modelId").GetString());

        var p1 = await Upload(id);
        _factory.FakeChat.CannedResponse = JsonSerializer.Serialize(new
        {
            assetId = "LAB-SW-01",
            hypotheses = new[] { new { featureId = "yellow_cable_near_end_visible", status = "Suspected", reason = "connector may be unseated", evidenceIds = new[] { p1 } } },
            nextAction = "RunApprovedProbe", requestedView = (string?)null, requestedSegmentationConcept = (string?)null,
        });
        Assert.Equal("FaultSuspected", (await Post($"/v1/inspections/{id}/investigate")).GetProperty("state").GetProperty("stage").GetString());

        var diag = await Post($"/v1/inspections/{id}/diagnostics/NODE-A-HEALTH:run");
        Assert.Equal("Simulated", diag.GetProperty("source").GetString());
        Assert.Equal("FaultConfirmed", diag.GetProperty("state").GetProperty("stage").GetString());

        await Post($"/v1/inspections/{id}/work-order", new { summary = "Reseat yellow cable at P2" });
        var claim = await Post($"/v1/inspections/{id}/repair-claims", new { description = "Reseated" });
        Assert.Equal("ReadyToVerify", claim.GetProperty("state").GetProperty("stage").GetString());

        // fake fix: a new post-repair photo, checklist, ack, but the endpoint is still down
        var p2 = await Upload(id, TestImages.Png(640, 480, salt: 2));   // a new post-repair view, not the earlier photo
        foreach (var req in new[] { "switch_chassis_visible", "yellow_cable_near_end_visible", "post_repair_view_captured" })
            await Post($"/v1/inspections/{id}/checklist", new { requirementId = req, status = "Satisfied", photoEvidenceId = p2 });
        await Post($"/v1/inspections/{id}/acknowledge");
        var v1 = await Post($"/v1/inspections/{id}/verify");
        Assert.Equal("Denied", v1.GetProperty("decision").GetProperty("result").GetString());
        Assert.Equal("VerificationFailed", v1.GetProperty("state").GetProperty("stage").GetString());

        // real fix
        await SetSimulation(true);
        await Post($"/v1/inspections/{id}/repair-claims", new { description = "Moved cable to P2" });
        var p3 = await Upload(id, TestImages.Png(640, 480, salt: 3));   // a new post-repair view, not the earlier photo
        foreach (var req in new[] { "switch_chassis_visible", "yellow_cable_near_end_visible", "post_repair_view_captured" })
            await Post($"/v1/inspections/{id}/checklist", new { requirementId = req, status = "Satisfied", photoEvidenceId = p3 });
        await Post($"/v1/inspections/{id}/acknowledge");
        var v2 = await Post($"/v1/inspections/{id}/verify");
        Assert.Equal("Verified", v2.GetProperty("decision").GetProperty("result").GetString());
        Assert.Equal("VerifiedCompleteSimulated", v2.GetProperty("decision").GetProperty("outcomeKind").GetString());
        Assert.Equal("VerifiedComplete", v2.GetProperty("state").GetProperty("stage").GetString());

        // append-only log on disk, one JSON object per line with a kind discriminator
        var log = File.ReadAllLines(Path.Combine(_factory.DataDir, "inspections", id + ".jsonl"));
        Assert.Contains(log, l => l.Contains("\"kind\":\"VerificationEvaluated\""));
        Assert.Equal(log.Length, (await Json(await _client.GetAsync($"/v1/inspections/{id}/events"))).GetArrayLength());
    }

    [Fact]
    public async Task Idempotency_key_replays_verify_and_invalid_model_output_does_not_advance()
    {
        await SetSimulation(true);
        var id = await CreateInspection();
        var p0 = await Upload(id);
        _factory.FakeChat.CannedResponse = JsonSerializer.Serialize(new { assetId = "LAB-SW-99", hypotheses = Array.Empty<object>(), nextAction = "RequestView", requestedView = "x", requestedSegmentationConcept = (string?)null });
        var bad = await _client.PostAsync($"/v1/inspections/{id}/investigate", null);
        Assert.Equal(HttpStatusCode.Conflict, bad.StatusCode);
        Assert.Equal(InspectionErrorCodes.InvalidModelOutput, (await Json(bad)).GetProperty("code").GetString());
        Assert.Equal("InitialScan", (await Json(await _client.GetAsync($"/v1/inspections/{id}"))).GetProperty("stage").GetString());

        _factory.FakeChat.CannedResponse = JsonSerializer.Serialize(new { assetId = "LAB-SW-01", hypotheses = new[] { new { featureId = "yellow_cable_near_end_visible", status = "Suspected", reason = "r", evidenceIds = new[] { p0 } } }, nextAction = "RunApprovedProbe", requestedView = (string?)null, requestedSegmentationConcept = (string?)null });
        await Post($"/v1/inspections/{id}/investigate");
        await SetSimulation(false);
        await Post($"/v1/inspections/{id}/diagnostics/NODE-A-HEALTH:run");
        await Post($"/v1/inspections/{id}/repair-claims", new { description = "fix" });
        await SetSimulation(true);
        var p1 = await Upload(id, TestImages.Png(640, 480, salt: 4));   // a new post-repair view, not the earlier photo
        foreach (var req in new[] { "switch_chassis_visible", "yellow_cable_near_end_visible", "post_repair_view_captured" })
            await Post($"/v1/inspections/{id}/checklist", new { requirementId = req, status = "Satisfied", photoEvidenceId = p1 });
        await Post($"/v1/inspections/{id}/acknowledge");

        var req1 = new HttpRequestMessage(HttpMethod.Post, $"/v1/inspections/{id}/verify") { Headers = { { "Idempotency-Key", "verify-once" } } };
        var first = await Json(await _client.SendAsync(req1));
        var req2 = new HttpRequestMessage(HttpMethod.Post, $"/v1/inspections/{id}/verify") { Headers = { { "Idempotency-Key", "verify-once" } } };
        var second = await Json(await _client.SendAsync(req2));
        Assert.Equal("Verified", first.GetProperty("decision").GetProperty("result").GetString());
        Assert.True(second.GetProperty("replayed").GetBoolean());
        Assert.Equal(first.GetProperty("decision").ToString(), second.GetProperty("decision").ToString());
    }

    [Fact]
    public async Task Segment_records_match_with_crop_and_no_match_without_mask()
    {
        var id = await CreateInspection();
        var p0 = await Upload(id);
        _factory.FakeSam.Match = true;
        var r = await Post($"/v1/inspections/{id}/segment", new { concept = "yellow ethernet cable" });
        var seg = r.GetProperty("segmentation");
        Assert.True(seg.GetProperty("matched").GetBoolean());
        Assert.Equal(1, seg.GetProperty("boxes").GetArrayLength());
        Assert.Equal(64, seg.GetProperty("maskSha256").GetString()!.Length);
        var cropFile = seg.GetProperty("crop").GetProperty("file").GetString()!;
        Assert.Equal(p0, seg.GetProperty("crop").GetProperty("transform").GetProperty("sourceEvidenceId").GetString());
        var artifact = await _client.GetAsync($"/v1/inspections/{id}/artifacts/{cropFile}");
        Assert.Equal("image/png", artifact.Content.Headers.ContentType!.MediaType);
        Assert.Equal(HttpStatusCode.BadRequest, (await _client.GetAsync($"/v1/inspections/{id}/artifacts/..%2Fsecret")).StatusCode);

        _factory.FakeSam.Match = false;
        var n = await Post($"/v1/inspections/{id}/segment", new { concept = "network switch" });
        Assert.False(n.GetProperty("segmentation").GetProperty("matched").GetBoolean());
        Assert.Equal(JsonValueKind.Null, n.GetProperty("segmentation").GetProperty("maskSha256").ValueKind);
        Assert.Equal("NeedsEvidence", n.GetProperty("state").GetProperty("stage").GetString());
    }

    [Fact]
    public async Task A_capture_review_never_investigates_a_different_latest_photo()
    {
        var id = await CreateInspection();
        var a = await Upload(id);
        await Upload(id, TestImages.Png(800, 600, 9));
        var calls = _factory.FakeChat.Calls;
        var response = await _client.PostAsync($"/v1/inspections/{id}/investigate?photoEvidenceId={a}", null);
        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
        Assert.Equal("photo_superseded", (await Json(response)).GetProperty("code").GetString());
        Assert.Equal(calls, _factory.FakeChat.Calls);
    }
}
