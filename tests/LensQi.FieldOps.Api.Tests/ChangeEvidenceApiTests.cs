using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Meta.Segmentation;

namespace LensQi.FieldOps.Tests;

/// <summary>Child VK-T26 over HTTP: change evidence runs on every post-claim photo before review or verification.</summary>
public class ChangeEvidenceApiTests
{
    private static readonly string[] Mandatory = ["switch_chassis_visible", "yellow_cable_near_end_visible", "post_repair_view_captured"];

    /// <summary>A SAM mask record whose object is the filled sub-rectangle of an 80×120 box at (left, top).</summary>
    private static MaskRecord Cable(int left, int top)
    {
        int w = 80, h = 120;
        var runs = new List<int>(); var current = false; var run = 0;
        for (var x = 0; x < w; x++)
            for (var y = 0; y < h; y++)
            {
                var on = x is >= 10 and < 60 && y is >= 10 and < 110;
                if (on != current) { runs.Add(run); run = 0; current = on; }
                run++;
            }
        runs.Add(run);
        return new MaskRecord("0", w, h, left, top, left + w, top + h, new RleMask([h, w], CocoRle.Encode(runs)), 0.9);
    }

    private sealed class Flow(FieldOpsApiFactory f)
    {
        public readonly HttpClient Http = f.CreateClient();
        public string Id = "";

        public static async Task<JsonElement> Json(HttpResponseMessage r) => JsonDocument.Parse(await r.Content.ReadAsStringAsync()).RootElement;

        public async Task<JsonElement> Post(string url, object? body = null)
        {
            var r = body is null ? await Http.PostAsync(url, null) : await Http.PostAsJsonAsync(url, body);
            Assert.True(r.IsSuccessStatusCode, $"{url} -> {(int)r.StatusCode} {await r.Content.ReadAsStringAsync()}");
            return await Json(r);
        }

        public async Task<JsonElement> Upload(byte[] bytes)
        {
            using var form = new MultipartFormDataContent { { new ByteArrayContent(bytes), "image", "f.png" } };
            form.Add(new StringContent(DateTimeOffset.UtcNow.ToString("o")), "observedAt");
            var r = await Http.PostAsync($"/v1/inspections/{Id}/observations", form);
            Assert.Equal(HttpStatusCode.OK, r.StatusCode);
            return await Json(r);
        }

        public async Task<JsonElement> State() => await Json(await Http.GetAsync($"/v1/inspections/{Id}"));

        /// <summary>Pre-claim photo, its segmentation (optional), failing simulated probe, work order and claim.</summary>
        public async Task<string> ToClaim(byte[] prePhoto, MaskRecord? preMask)
        {
            await Post("/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = false });
            Id = (await Post("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" })).GetProperty("inspectionId").GetString()!;
            var pre = (await Upload(prePhoto)).GetProperty("evidenceId").GetString()!;
            if (preMask is not null)
            {
                f.FakeSam.Queued.Enqueue([preMask]);
                Assert.True((await Post($"/v1/inspections/{Id}/segment", new { concept = "yellow ethernet cable" })).GetProperty("segmentation").GetProperty("matched").GetBoolean());
            }
            Assert.Equal("FaultConfirmed", (await Post($"/v1/inspections/{Id}/diagnostics/NODE-A-HEALTH:run")).GetProperty("state").GetProperty("stage").GetString());
            await Post($"/v1/inspections/{Id}/work-order", new { summary = "Seat the yellow cable in P2" });
            await Post($"/v1/inspections/{Id}/repair-claims", new { description = "Moved the cable to P2" });
            return pre;
        }
    }

    [Fact]
    public async Task Re_submitted_photo_is_a_duplicate_with_no_sam_call_and_verification_never_runs_the_probe()
    {
        using var f = new FieldOpsApiFactory();
        var flow = new Flow(f);
        var prePhoto = TestImages.Png(640, 480, salt: 1);
        var pre = await flow.ToClaim(prePhoto, Cable(300, 600));
        var samBefore = f.FakeSam.Calls;

        var up = await flow.Upload(prePhoto);
        var change = up.GetProperty("changeAnalysis");
        Assert.Equal("DuplicateImage", change.GetProperty("verdict").GetString());
        Assert.Equal(pre, change.GetProperty("duplicateOfEvidenceId").GetString());
        Assert.Equal(samBefore, f.FakeSam.Calls);
        Assert.Equal("NeedsEvidence", up.GetProperty("state").GetProperty("stage").GetString());

        var post = up.GetProperty("evidenceId").GetString();
        var review = await flow.Http.PostAsJsonAsync($"/v1/inspections/{flow.Id}/checklist", new { requirementId = Mandatory[0], status = "Satisfied", photoEvidenceId = post });
        Assert.Equal(HttpStatusCode.Conflict, review.StatusCode);
        Assert.Equal(InspectionErrorCodes.ChangeEvidenceRejected, (await Flow.Json(review)).GetProperty("code").GetString());

        var probesBefore = (await flow.State()).GetProperty("diagnostics").GetArrayLength();
        var verify = await flow.Http.PostAsync($"/v1/inspections/{flow.Id}/verify", null);
        Assert.Equal(HttpStatusCode.Conflict, verify.StatusCode);
        Assert.Equal(probesBefore, (await flow.State()).GetProperty("diagnostics").GetArrayLength());

        var byClaim = await flow.Http.GetFromJsonAsync<JsonElement>($"/v1/inspections/{flow.Id}/claims/{(await flow.State()).GetProperty("currentClaimId").GetString()}/change-analysis");
        Assert.Equal("DuplicateImage", byClaim.GetProperty("verdict").GetString());
    }

    [Fact]
    public async Task Unchanged_target_in_a_new_photo_is_no_visual_change()
    {
        using var f = new FieldOpsApiFactory();
        f.FakeQuality.Hash = bytes => bytes[^1] == 1 ? "00000000000000ff" : "000000000000ffff";   // distance 8: not a duplicate, below the hash-change threshold
        var flow = new Flow(f);
        await flow.ToClaim(TestImages.Png(640, 480, salt: 1), Cable(300, 600));
        f.FakeSam.Queued.Enqueue([Cable(300, 600)]);

        var up = await flow.Upload(TestImages.Png(640, 480, salt: 2));
        var change = up.GetProperty("changeAnalysis");
        Assert.Equal("NoVisualChange", change.GetProperty("verdict").GetString());
        Assert.Equal(1.0, change.GetProperty("targetRegionIoU").GetDouble());
        Assert.Equal(8, change.GetProperty("perceptualHashDistance").GetInt32());
        Assert.Equal("NeedsEvidence", up.GetProperty("state").GetProperty("stage").GetString());
    }

    [Fact]
    public async Task Moved_cable_is_changed_and_the_claim_can_then_be_verified_by_the_diagnostic()
    {
        using var f = new FieldOpsApiFactory();
        var flow = new Flow(f);
        await flow.ToClaim(TestImages.Png(640, 480, salt: 1), Cable(700, 600));
        await flow.Post("/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = true });
        f.FakeSam.Queued.Enqueue([Cable(300, 600)]);

        var up = await flow.Upload(TestImages.Png(640, 480, salt: 2));
        var change = up.GetProperty("changeAnalysis");
        Assert.Equal("Changed", change.GetProperty("verdict").GetString());
        Assert.Equal("visual-change/1", change.GetProperty("thresholdsVersion").GetString());
        var post = up.GetProperty("evidenceId").GetString();
        Assert.Equal("ReadyToVerify", up.GetProperty("state").GetProperty("stage").GetString());
        Assert.Equal(post, up.GetProperty("state").GetProperty("acceptedPostRepairPhotoId").GetString());
        // the post-repair SAM call is recorded like any other segmentation
        Assert.Equal(2, up.GetProperty("state").GetProperty("segmentations").GetArrayLength());

        foreach (var req in Mandatory)
            await flow.Post($"/v1/inspections/{flow.Id}/checklist", new { requirementId = req, status = "Satisfied", photoEvidenceId = post });
        await flow.Post($"/v1/inspections/{flow.Id}/acknowledge");
        var v = await flow.Post($"/v1/inspections/{flow.Id}/verify");
        Assert.Equal("Verified", v.GetProperty("decision").GetProperty("result").GetString());
        Assert.Equal(VerificationPolicyEvaluator.PolicyVersion, v.GetProperty("decision").GetProperty("policyVersion").GetString());

        var html = await flow.Http.GetStringAsync($"/v1/inspections/{flow.Id}/receipt.html");
        Assert.Contains("Change evidence", html);
        Assert.Contains("Changed", html);
    }

    [Fact]
    public async Task A_photo_awaiting_change_analysis_is_not_evidence_and_a_retry_finishes_the_analysis()
    {
        using var f = new FieldOpsApiFactory();
        var flow = new Flow(f);
        await flow.ToClaim(TestImages.Png(640, 480, salt: 1), Cable(700, 600));
        HttpRequestMessage Upload()
        {
            var form = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(640, 480, salt: 2)), "image", "f.png" } };
            form.Add(new StringContent(DateTimeOffset.UtcNow.ToString("o")), "observedAt");
            var m = new HttpRequestMessage(HttpMethod.Post, $"/v1/inspections/{flow.Id}/observations") { Content = form };
            m.Headers.Add("Idempotency-Key", "post-repair-1");
            return m;
        }

        // The photo is recorded, then the analysis is interrupted before its result is recorded.
        f.FakeSam.Throw = new NotSupportedException("interrupted");
        try { Assert.False((await flow.Http.SendAsync(Upload())).IsSuccessStatusCode); } catch (NotSupportedException) { }
        var state = await flow.State();
        var pending = state.GetProperty("photos").EnumerateArray().Last().GetProperty("evidenceId").GetString()!;
        Assert.Equal(JsonValueKind.Null, state.GetProperty("acceptedPostRepairPhotoId").ValueKind);
        var review = await flow.Http.PostAsJsonAsync($"/v1/inspections/{flow.Id}/checklist", new { requirementId = Mandatory[0], status = "Satisfied", photoEvidenceId = pending });
        Assert.Equal(HttpStatusCode.Conflict, review.StatusCode);
        Assert.Equal(InspectionErrorCodes.ChangeAnalysisPending, (await Flow.Json(review)).GetProperty("code").GetString());

        // The retry answers for the recorded photo, finishes its analysis and keeps no second copy.
        f.FakeSam.Throw = null;
        f.FakeSam.Queued.Enqueue([Cable(300, 600)]);
        var retry = await Flow.Json(await flow.Http.SendAsync(Upload()));
        Assert.Equal(pending, retry.GetProperty("evidenceId").GetString());
        Assert.Equal("Changed", retry.GetProperty("changeAnalysis").GetProperty("verdict").GetString());
        Assert.Equal(pending, retry.GetProperty("state").GetProperty("acceptedPostRepairPhotoId").GetString());
        Assert.Equal(2, Directory.GetFiles(Path.Combine(f.DataDir, "evidence", flow.Id)).Count(p => Path.GetFileName(p).StartsWith("photo-")));
    }

    [Fact]
    public async Task A_numeric_checklist_status_is_refused()
    {
        using var f = new FieldOpsApiFactory();
        var flow = new Flow(f);
        await flow.ToClaim(TestImages.Png(640, 480, salt: 1), preMask: null);
        var post = (await flow.Upload(TestImages.Png(640, 480, salt: 2))).GetProperty("evidenceId").GetString();
        var r = await flow.Http.PostAsync($"/v1/inspections/{flow.Id}/checklist",
            new StringContent($$"""{"requirementId":"{{Mandatory[0]}}","status":999,"photoEvidenceId":"{{post}}"}""", System.Text.Encoding.UTF8, "application/json"));
        Assert.Equal(HttpStatusCode.BadRequest, r.StatusCode);
        Assert.Empty((await flow.State()).GetProperty("checklist").EnumerateArray());
    }

    [Fact]
    public async Task Without_a_pre_claim_segmentation_the_change_is_unscorable_and_the_human_gate_applies()
    {
        using var f = new FieldOpsApiFactory();
        var flow = new Flow(f);
        await flow.ToClaim(TestImages.Png(640, 480, salt: 1), preMask: null);
        var samBefore = f.FakeSam.Calls;
        var up = await flow.Upload(TestImages.Png(640, 480, salt: 2));
        Assert.Equal("Unscorable", up.GetProperty("changeAnalysis").GetProperty("verdict").GetString());
        Assert.Equal(samBefore, f.FakeSam.Calls);
        Assert.Equal("ReadyToVerify", up.GetProperty("state").GetProperty("stage").GetString());
    }

    [Fact]
    public async Task Sam_down_during_change_evidence_is_unscorable_not_an_upload_failure()
    {
        using var f = new FieldOpsApiFactory();
        var flow = new Flow(f);
        await flow.ToClaim(TestImages.Png(640, 480, salt: 1), Cable(300, 600));
        f.FakeSam.Throw = new HttpRequestException("Meta Model API (SAM) returned 503");
        var up = await flow.Upload(TestImages.Png(640, 480, salt: 2));
        var change = up.GetProperty("changeAnalysis");
        Assert.Equal("Unscorable", change.GetProperty("verdict").GetString());
        Assert.Contains("SAM unavailable", change.GetProperty("reason").GetString());
        Assert.Equal("ReadyToVerify", up.GetProperty("state").GetProperty("stage").GetString());
    }
}
