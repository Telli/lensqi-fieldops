using System.Net;
using System.Net.Http.Json;
using System.Text.Json;

namespace LensQi.FieldOps.Tests;

public class LiveVisionTests
{
    private static async Task<string> Create(HttpClient client) => (await (await client.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" })).Content.ReadFromJsonAsync<JsonElement>()).GetProperty("inspectionId").GetString()!;
    private static MultipartFormDataContent Frame(string id = "frame-1", int w = 640, int h = 480) {
        var form = new MultipartFormDataContent(); form.Add(new ByteArrayContent(TestImages.Png(w, h)), "image", "preview.png"); form.Add(new StringContent(id), "frameId"); return form;
    }
    [Fact] public async Task Detection_is_normalized_and_does_not_save_photos_or_change_stage() {
        using var f = new FieldOpsApiFactory(); using var c = f.CreateClient(); var id = await Create(c);
        var before = await c.GetStringAsync($"/v1/inspections/{id}/events");
        using var response = await c.PostAsync($"/v1/inspections/{id}/live/detect", Frame());
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var result = await response.Content.ReadFromJsonAsync<JsonElement>(); Assert.Equal("frame-1", result.GetProperty("frameId").GetString());
        Assert.Equal(2, result.GetProperty("objects").GetArrayLength());
        Assert.Equal(10d / 640, result.GetProperty("objects")[0].GetProperty("box").GetProperty("left").GetDouble());
        Assert.Equal(before, await c.GetStringAsync($"/v1/inspections/{id}/events"));
        Assert.Equal("[]", await c.GetStringAsync($"/v1/inspections/{id}/artifacts"));
        Assert.Equal("no-store", response.Headers.CacheControl?.ToString());
    }
    [Theory] [InlineData("unknown", 640, 480, "frame-1")] [InlineData("detect", 2000, 480, "frame-1")] [InlineData("detect", 640, 80, "frame-1")] [InlineData("detect", 640, 480, "bad/frame")]
    public async Task Invalid_requests_are_rejected_before_provider(string kind, int w, int h, string frame) {
        using var f = new FieldOpsApiFactory(); using var c = f.CreateClient(); var id = await Create(c);
        var r = await c.PostAsync($"/v1/inspections/{id}/live/{kind}", Frame(frame, w, h));
        Assert.Equal(HttpStatusCode.BadRequest, r.StatusCode); Assert.Equal(0, f.FakeSam.Calls);
    }
    [Fact] public async Task Cooldown_rejects_duplicate_frame_work_without_touching_evidence_budget() {
        using var f = new FieldOpsApiFactory(); using var c = f.CreateClient(); var id = await Create(c);
        Assert.Equal(HttpStatusCode.OK, (await c.PostAsync($"/v1/inspections/{id}/live/detect", Frame())).StatusCode);
        var r = await c.PostAsync($"/v1/inspections/{id}/live/detect", Frame("frame-2"));
        Assert.Equal(HttpStatusCode.TooManyRequests, r.StatusCode); Assert.NotNull(r.Headers.RetryAfter); Assert.Equal(2, f.FakeSam.Calls);
    }
    private static string Guidance(string frame, string feature = "switch_chassis_visible") => JsonSerializer.Serialize(new {
        assetId = "LAB-SW-01", hypotheses = new[] { new { featureId = feature, status = "Observed", reason = "Equipment visible", evidenceIds = new[] { frame } } },
        nextAction = "RequestView", requestedView = "Move closer to the connector", requestedSegmentationConcept = (string?)null
    });
    [Fact] public async Task Guidance_cites_only_the_ephemeral_frame_and_preserves_the_event_log() {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = Guidance("frame-1"); using var c = f.CreateClient(); var id = await Create(c);
        var before = await c.GetStringAsync($"/v1/inspections/{id}/events");
        var r = await c.PostAsync($"/v1/inspections/{id}/live/guide", Frame()); Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal("Move closer to the connector", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("recommendation").GetString());
        Assert.Equal(before, await c.GetStringAsync($"/v1/inspections/{id}/events")); Assert.Equal(1, f.FakeChat.Images);
    }
    [Theory] [InlineData("old-frame", "switch_chassis_visible")] [InlineData("frame-1", "invented-feature")]
    public async Task Foreign_model_references_are_rejected(string frame, string feature) {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = Guidance(frame, feature); using var c = f.CreateClient(); var id = await Create(c);
        var r = await c.PostAsync($"/v1/inspections/{id}/live/guide", Frame()); Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal("invalid_model_output", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
    }
    [Fact] public async Task Provider_failure_is_recoverable_and_never_returns_a_fake_detection() {
        using var f = new FieldOpsApiFactory(); f.FakeSam.Throw = new HttpRequestException("provider down"); using var c = f.CreateClient(); var id = await Create(c);
        var r = await c.PostAsync($"/v1/inspections/{id}/live/detect", Frame()); Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal("provider_unavailable", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
    }
}
