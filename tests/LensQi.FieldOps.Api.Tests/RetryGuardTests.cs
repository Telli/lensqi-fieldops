using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Diagnostics;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace LensQi.FieldOps.Tests;

/// <summary>Retried and concurrent requests never repeat a probe or a paid model call whose result could not be recorded.</summary>
public class RetryGuardTests
{
    /// <summary>The simulated probe, slowed down so two requests overlap, counting every run.</summary>
    private sealed class SlowProbe(IDiagnosticProbe inner) : IDiagnosticProbe
    {
        public int Calls;
        public DiagnosticSource Source => inner.Source;
        public async Task<DiagnosticResult> RunAsync(ProbeRequest request, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref Calls);
            await Task.Delay(300, cancellationToken);
            return await inner.RunAsync(request, cancellationToken);
        }
    }

    private static async Task<JsonElement> Json(HttpResponseMessage r) => JsonDocument.Parse(await r.Content.ReadAsStringAsync()).RootElement;

    private static async Task<JsonElement> Post(HttpClient c, string url, object? body = null, string? key = null)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, url) { Content = body is null ? null : JsonContent.Create(body) };
        if (key is not null) request.Headers.Add("Idempotency-Key", key);
        var r = await c.SendAsync(request);
        var j = await Json(r);
        Assert.True(r.IsSuccessStatusCode, $"{url} -> {r.StatusCode}: {j}");
        return j;
    }

    private static async Task<string> NewInspection(HttpClient c)
    {
        var r = await c.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        Assert.Equal(HttpStatusCode.Created, r.StatusCode);
        var id = (await Json(r)).GetProperty("inspectionId").GetString()!;
        using var form = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(640, 480)), "image", "f.png" }, { new StringContent("Phone"), "source" } };
        Assert.Equal(HttpStatusCode.OK, (await c.PostAsync($"/v1/inspections/{id}/observations", form)).StatusCode);
        return id;
    }

    private static async Task<string[]> Kinds(HttpClient c, string id) =>
        (await Json(await c.GetAsync($"/v1/inspections/{id}/events"))).EnumerateArray().Select(e => e.GetProperty("kind").GetString()!).ToArray();

    /// <summary>A failed probe confirms the fault; a work order and a claim follow. With <paramref name="evidence"/>, a
    /// post-repair photo, a satisfied checklist and the acknowledgement complete everything but the fresh diagnostic.</summary>
    private static async Task ReadyToVerify(HttpClient c, string id, bool evidence = true)
    {
        await Post(c, "/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = false });
        Assert.Equal("FaultConfirmed", (await Post(c, $"/v1/inspections/{id}/diagnostics/NODE-A-HEALTH:run")).GetProperty("state").GetProperty("stage").GetString());
        await Post(c, $"/v1/inspections/{id}/work-order", new { summary = "Reseat the cable" });
        Assert.Equal("ReadyToVerify", (await Post(c, $"/v1/inspections/{id}/repair-claims", new { description = "Reseated" })).GetProperty("state").GetProperty("stage").GetString());
        await Post(c, "/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = true });
        if (!evidence) return;
        using var form = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(640, 480, salt: 9)), "image", "after.png" }, { new StringContent("Phone"), "source" } };
        var photo = (await Json(await c.PostAsync($"/v1/inspections/{id}/observations", form))).GetProperty("evidenceId").GetString();
        foreach (var requirement in new[] { "switch_chassis_visible", "yellow_cable_near_end_visible", "post_repair_view_captured" })
            await Post(c, $"/v1/inspections/{id}/checklist", new { requirementId = requirement, status = "Satisfied", photoEvidenceId = photo });
        await Post(c, $"/v1/inspections/{id}/acknowledge");
    }

    [Fact]
    public async Task Concurrent_verifies_run_the_probes_once_and_record_one_decision()
    {
        using var baseFactory = new FieldOpsApiFactory();
        SlowProbe? probe = null;
        using var f = baseFactory.WithWebHostBuilder(b => b.ConfigureServices(s =>
        {
            s.RemoveAll<IDiagnosticProbe>();
            s.AddSingleton<IDiagnosticProbe>(sp => probe = new SlowProbe(new SimulatedProbe(sp.GetRequiredService<ISimulationState>(), sp.GetRequiredService<TimeProvider>())));
        }));
        var c = f.CreateClient();

        // The same key twice at once: one decision, the other request replays it.
        var keyed = await NewInspection(c);
        await ReadyToVerify(c, keyed);
        var before = probe!.Calls;
        var both = await Task.WhenAll(Post(c, $"/v1/inspections/{keyed}/verify", key: "v-1"), Post(c, $"/v1/inspections/{keyed}/verify", key: "v-1"));
        Assert.Equal(1, probe.Calls - before);
        Assert.Single(both, r => r.GetProperty("replayed").GetBoolean());
        Assert.Equal(both[0].GetProperty("decision").ToString(), both[1].GetProperty("decision").ToString());
        var kinds = await Kinds(c, keyed);
        Assert.Single(kinds, k => k == "VerificationEvaluated");
        Assert.Equal(2, kinds.Count(k => k == "DiagnosticCompleted"));   // the fault probe and one verify probe

        // No key: the second request waits, finds the claim already decided, and is refused without probing.
        var plain = await NewInspection(c);
        await ReadyToVerify(c, plain);
        before = probe.Calls;
        var responses = await Task.WhenAll(c.PostAsync($"/v1/inspections/{plain}/verify", null), c.PostAsync($"/v1/inspections/{plain}/verify", null));
        Assert.Equal(1, probe.Calls - before);
        Assert.Single(responses, r => r.StatusCode == HttpStatusCode.OK);
        var refused = Assert.Single(responses, r => r.StatusCode == HttpStatusCode.Conflict);
        Assert.Equal(InspectionErrorCodes.InvalidState, (await Json(refused)).GetProperty("code").GetString());
        Assert.Single(await Kinds(c, plain), k => k == "VerificationEvaluated");

        // Missing photo, review and acknowledgement decide the outcome on their own: no probe is spent on it.
        var early = await NewInspection(c);
        await ReadyToVerify(c, early, evidence: false);
        before = probe.Calls;
        var premature = await Post(c, $"/v1/inspections/{early}/verify");
        Assert.Equal("Denied", premature.GetProperty("decision").GetProperty("result").GetString());
        Assert.Equal(before, probe.Calls);
    }

    [Fact]
    public async Task A_retried_diagnostic_or_claim_returns_what_was_recorded()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await NewInspection(c);
        await Post(c, "/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = false });
        var first = await Post(c, $"/v1/inspections/{id}/diagnostics/NODE-A-HEALTH:run", key: "probe-1");
        await Post(c, "/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = true });
        var retry = await Post(c, $"/v1/inspections/{id}/diagnostics/NODE-A-HEALTH:run", key: "probe-1");
        Assert.True(retry.GetProperty("replayed").GetBoolean());
        Assert.Equal(first.GetProperty("evidenceId").GetString(), retry.GetProperty("evidenceId").GetString());
        Assert.False(retry.GetProperty("result").GetProperty("passed").GetBoolean());   // the recorded failure, not a new pass
        Assert.Single(await Kinds(c, id), k => k == "DiagnosticCompleted");

        await Post(c, $"/v1/inspections/{id}/work-order", new { summary = "Reseat the cable" });
        var claim = await Post(c, $"/v1/inspections/{id}/repair-claims", new { description = "Reseated" }, "claim-1");
        var again = await Post(c, $"/v1/inspections/{id}/repair-claims", new { description = "Reseated" }, "claim-1");
        Assert.Equal(claim.GetProperty("claimId").GetString(), again.GetProperty("claimId").GetString());
        Assert.Equal(claim.GetProperty("claimId").GetString(), again.GetProperty("state").GetProperty("currentClaimId").GetString());
    }

    [Fact]
    public async Task Overlapping_requests_with_one_key_make_one_model_call()
    {
        using var f = new FieldOpsApiFactory();
        f.FakeChat.Delay = TimeSpan.FromMilliseconds(300);
        var c = f.CreateClient();
        var id = await NewInspection(c);
        var photo = (await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("photos")[0].GetProperty("evidenceId").GetString();
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new
        {
            assetId = "LAB-SW-01",
            hypotheses = new[] { new { featureId = "yellow_cable_near_end_visible", status = "Unknown", reason = "occluded", evidenceIds = new[] { photo } } },
            nextAction = "RequestView", requestedView = "Show the yellow cable connector", requestedSegmentationConcept = (string?)null,
        });
        var both = await Task.WhenAll(Post(c, $"/v1/inspections/{id}/investigate", key: "inv-x"), Post(c, $"/v1/inspections/{id}/investigate", key: "inv-x"));
        Assert.Equal(1, f.FakeChat.Calls);
        Assert.Single(both, r => r.GetProperty("replayed").GetBoolean());
        Assert.Equal(both[0].GetProperty("evidenceId").GetString(), both[1].GetProperty("evidenceId").GetString());

        var segments = await Task.WhenAll(Post(c, $"/v1/inspections/{id}/segment", new { concept = "cable" }, "seg-x"), Post(c, $"/v1/inspections/{id}/segment", new { concept = "cable" }, "seg-x"));
        Assert.Equal(1, f.FakeSam.Calls);
        Assert.Equal(segments[0].GetProperty("segmentation").GetProperty("segmentationId").GetString(), segments[1].GetProperty("segmentation").GetProperty("segmentationId").GetString());
    }

    [Fact]
    public async Task Investigate_refuses_a_closed_stage_before_calling_the_model_and_replays_a_retried_key()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();

        var confirmed = await NewInspection(c);
        await Post(c, "/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = false });
        await Post(c, $"/v1/inspections/{confirmed}/diagnostics/NODE-A-HEALTH:run");
        var refused = await c.PostAsync($"/v1/inspections/{confirmed}/investigate", null);
        Assert.Equal(HttpStatusCode.Conflict, refused.StatusCode);
        Assert.Equal(InspectionErrorCodes.InvalidState, (await Json(refused)).GetProperty("code").GetString());
        Assert.Equal(0, f.FakeChat.Calls);

        var id = await NewInspection(c);
        var photo = (await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("photos")[0].GetProperty("evidenceId").GetString();
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new
        {
            assetId = "LAB-SW-01",
            hypotheses = new[] { new { featureId = "yellow_cable_near_end_visible", status = "Unknown", reason = "occluded", evidenceIds = new[] { photo } } },
            nextAction = "RequestView", requestedView = "Show the yellow cable connector", requestedSegmentationConcept = (string?)null,
        });
        var first = await Post(c, $"/v1/inspections/{id}/investigate", key: "inv-1");
        var retry = await Post(c, $"/v1/inspections/{id}/investigate", key: "inv-1");
        Assert.Equal(1, f.FakeChat.Calls);
        Assert.False(first.GetProperty("replayed").GetBoolean());
        Assert.True(retry.GetProperty("replayed").GetBoolean());
        foreach (var field in new[] { "evidenceId", "modelId", "promptDigest", "responseDigest" })
            Assert.Equal(first.GetProperty(field).GetString(), retry.GetProperty(field).GetString());
        Assert.Equal("NeedsEvidence", retry.GetProperty("state").GetProperty("stage").GetString());
        Assert.Single(await Kinds(c, id), k => k == "AssessmentRecorded");
    }

    [Fact]
    public async Task A_retried_segment_key_returns_the_recorded_segmentation_without_a_second_sam_call()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await NewInspection(c);

        var first = await Post(c, $"/v1/inspections/{id}/segment", new { concept = "yellow ethernet cable" }, "seg-1");
        var retry = await Post(c, $"/v1/inspections/{id}/segment", new { concept = "yellow ethernet cable" }, "seg-1");
        Assert.Equal(1, f.FakeSam.Calls);
        Assert.True(retry.GetProperty("replayed").GetBoolean());
        Assert.Equal(first.GetProperty("segmentation").GetProperty("segmentationId").GetString(), retry.GetProperty("segmentation").GetProperty("segmentationId").GetString());
        Assert.Single(await Kinds(c, id), k => k == "SegmentationRecorded");
        Assert.Single(Directory.GetFiles(Path.Combine(f.DataDir, "evidence", id), "seg-*.json"));

        // A key already spent on another command is refused, not mistaken for a segmentation.
        using var upload = new HttpRequestMessage(HttpMethod.Post, $"/v1/inspections/{id}/observations")
        {
            Content = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(640, 480, salt: 5)), "image", "g.png" }, { new StringContent("Phone"), "source" } },
            Headers = { { "Idempotency-Key", "photo-1" } },
        };
        Assert.Equal(HttpStatusCode.OK, (await c.SendAsync(upload)).StatusCode);
        using var reused = new HttpRequestMessage(HttpMethod.Post, $"/v1/inspections/{id}/segment") { Content = JsonContent.Create(new { concept = "cable" }), Headers = { { "Idempotency-Key", "photo-1" } } };
        var other = await c.SendAsync(reused);
        Assert.Equal(HttpStatusCode.Conflict, other.StatusCode);
        Assert.Equal(1, f.FakeSam.Calls);
    }
}
