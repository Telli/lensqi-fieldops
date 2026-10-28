using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Diagnostics;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace LensQi.FieldOps.Tests;

/// <summary>Drives the real API over HTTP with the simulated probe and a fake Muse: the PRD hero demo without hardware or paid calls.</summary>
public sealed class FieldOpsApiFactory : WebApplicationFactory<Program>
{
    public string DataDir { get; } = Directory.CreateTempSubdirectory("fieldops-api-").FullName;
    public FakeVisionChat FakeChat { get; } = new();

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Testing");
        builder.UseSetting("Storage:DataDirectory", DataDir);
        builder.UseSetting("Diagnostics:Mode", "Simulated");
        builder.ConfigureServices(services =>
        {
            services.RemoveAll<IChatClient>();
            services.AddSingleton<IChatClient>(FakeChat);
        });
    }
}

public sealed class FakeVisionChat : IChatClient
{
    public string CannedResponse { get; set; } = "{}";
    public int Images { get; private set; }
    public Task<ChatResponse> GetResponseAsync(IEnumerable<ChatMessage> messages, ChatOptions? options = null, CancellationToken cancellationToken = default)
    {
        Images += messages.SelectMany(m => m.Contents).OfType<DataContent>().Count();
        return Task.FromResult(new ChatResponse(new ChatMessage(ChatRole.Assistant, CannedResponse)) { ModelId = "fake-muse" });
    }
    public IAsyncEnumerable<ChatResponseUpdate> GetStreamingResponseAsync(IEnumerable<ChatMessage> messages, ChatOptions? options = null, CancellationToken cancellationToken = default) => throw new NotSupportedException();
    public object? GetService(Type serviceType, object? serviceKey = null) => null;
    public void Dispose() { }
}

public class InspectionApiTests : IClassFixture<FieldOpsApiFactory>
{
    private readonly FieldOpsApiFactory _factory;
    private readonly HttpClient _client;
    private static readonly byte[] Png = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5, 6, 7, 8, 9];

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

        // fake fix: post-repair photo, checklist, ack, but the endpoint is still down
        var p2 = await Upload(id);
        foreach (var req in new[] { "switch_chassis_visible", "yellow_cable_near_end_visible", "post_repair_view_captured" })
            await Post($"/v1/inspections/{id}/checklist", new { requirementId = req, status = "Satisfied", photoEvidenceId = p2 });
        await Post($"/v1/inspections/{id}/acknowledge");
        var v1 = await Post($"/v1/inspections/{id}/verify");
        Assert.Equal("Denied", v1.GetProperty("decision").GetProperty("result").GetString());
        Assert.Equal("VerificationFailed", v1.GetProperty("state").GetProperty("stage").GetString());

        // real fix
        await SetSimulation(true);
        await Post($"/v1/inspections/{id}/repair-claims", new { description = "Moved cable to P2" });
        var p3 = await Upload(id);
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
        var p1 = await Upload(id);
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
}
