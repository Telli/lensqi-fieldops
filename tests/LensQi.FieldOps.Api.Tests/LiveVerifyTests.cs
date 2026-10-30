using System.Net;
using System.Net.Http.Json;
using System.Text.Json;

namespace LensQi.FieldOps.Tests;

/// <summary>
/// The verify flow over the API: readiness tells the client the next step, every verify measures again, a retried key
/// returns the recorded measurement and decision, a new claim needs its own evidence, and a restart loses nothing.
/// </summary>
public class LiveVerifyTests
{
    private static readonly string[] Requirements = ["switch_chassis_visible", "yellow_cable_near_end_visible", "post_repair_view_captured"];

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

    private static async Task<JsonElement> State(HttpClient c, string id) => await Json(await c.GetAsync($"/v1/inspections/{id}"));
    private static async Task<JsonElement> Readiness(HttpClient c, string id) => (await State(c, id)).GetProperty("readiness");
    private static async Task<int> Probes(HttpClient c, string id) => (await State(c, id)).GetProperty("diagnostics").GetArrayLength();

    private static async Task<string> Photo(HttpClient c, string id, byte salt)
    {
        using var form = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(640, 480, salt)), "image", "p.png" }, { new StringContent("Phone"), "source" } };
        var r = await c.PostAsync($"/v1/inspections/{id}/observations", form);
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        return (await Json(r)).GetProperty("evidenceId").GetString()!;
    }

    private static async Task Review(HttpClient c, string id, string photo, string status = "Satisfied", string? only = null)
    {
        foreach (var requirement in only is null ? Requirements : [only])
            await Post(c, $"/v1/inspections/{id}/checklist", new { requirementId = requirement, status, photoEvidenceId = photo });
    }

    private static async Task SetEndpoint(HttpClient c, bool passed) => await Post(c, "/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed });

    /// <summary>A failed probe confirms the fault, then a work order and a repair claim.</summary>
    private static async Task<string> Claimed(HttpClient c)
    {
        var r = await c.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        var id = (await Json(r)).GetProperty("inspectionId").GetString()!;
        await Photo(c, id, 1);
        await SetEndpoint(c, false);
        await Post(c, $"/v1/inspections/{id}/diagnostics/NODE-A-HEALTH:run");
        await Post(c, $"/v1/inspections/{id}/work-order", new { summary = "Reseat the cable" });
        await Post(c, $"/v1/inspections/{id}/repair-claims", new { description = "Reseated" });
        return id;
    }

    [Fact]
    public async Task Readiness_leads_from_photo_to_review_to_acknowledgement_to_verify()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await Claimed(c);

        var r = await Readiness(c, id);
        Assert.False(r.GetProperty("ready").GetBoolean());
        Assert.Equal("CapturePhoto", r.GetProperty("next").GetString());
        var check = r.GetProperty("checks")[0];
        Assert.Equal("NODE-A-HEALTH", check.GetProperty("diagnosticId").GetString());
        Assert.False(check.GetProperty("afterClaim").GetBoolean());             // the fault probe came before the claim
        Assert.Equal("Simulated", check.GetProperty("source").GetString());

        // A premature verify records the same first reason readiness lists.
        var early = await Post(c, $"/v1/inspections/{id}/verify");
        Assert.Contains(early.GetProperty("decision").GetProperty("reasons")[0].GetString()!, r.GetProperty("blocking").EnumerateArray().Select(b => b.GetString()));
        Assert.Equal("CapturePhoto", (await Readiness(c, id)).GetProperty("next").GetString());

        var photo = await Photo(c, id, 9);
        Assert.Equal("ReviewChecklist", (await Readiness(c, id)).GetProperty("next").GetString());
        await Review(c, id, photo);
        Assert.Equal("Acknowledge", (await Readiness(c, id)).GetProperty("next").GetString());
        await Post(c, $"/v1/inspections/{id}/acknowledge");
        r = await Readiness(c, id);
        Assert.True(r.GetProperty("ready").GetBoolean());
        Assert.Equal("Verify", r.GetProperty("next").GetString());
        Assert.Equal(0, r.GetProperty("blocking").GetArrayLength());
    }

    [Fact]
    public async Task Every_verify_measures_again_and_a_retried_key_returns_the_recorded_measurement()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await Claimed(c);

        // A passing check taken after the claim is not reused: the endpoint is down again when verify runs.
        await SetEndpoint(c, true);
        await Post(c, $"/v1/inspections/{id}/diagnostics/NODE-A-HEALTH:run");
        var photo = await Photo(c, id, 9);
        await Review(c, id, photo);
        await Post(c, $"/v1/inspections/{id}/acknowledge");
        await SetEndpoint(c, false);
        var before = await Probes(c, id);
        var failed = await Post(c, $"/v1/inspections/{id}/verify", key: "verify-1");
        Assert.Equal("Denied", failed.GetProperty("decision").GetProperty("result").GetString());
        Assert.Equal("VerificationFailed", failed.GetProperty("state").GetProperty("stage").GetString());
        Assert.Equal(before + 1, await Probes(c, id));
        var ids = (await State(c, id)).GetProperty("diagnostics").EnumerateArray().Select(d => d.GetProperty("evidenceId").GetString()!).ToList();
        Assert.StartsWith("verify-diag-", ids[^1]);                            // measured by verify itself
        Assert.StartsWith("diag-", ids[^2]);                                   // the manual run before it

        // The retry returns the recorded decision and measures nothing, even though the endpoint now answers.
        await SetEndpoint(c, true);
        var retry = await Post(c, $"/v1/inspections/{id}/verify", key: "verify-1");
        Assert.True(retry.GetProperty("replayed").GetBoolean());
        Assert.Equal(failed.GetProperty("decision").ToString(), retry.GetProperty("decision").ToString());
        Assert.Equal(before + 1, await Probes(c, id));
        Assert.Equal("ClaimRepair", (await Readiness(c, id)).GetProperty("next").GetString());

        // The real fix, on a new claim with its own evidence, verifies on its own fresh measurement, labelled simulated.
        await Post(c, $"/v1/inspections/{id}/repair-claims", new { description = "Moved the cable to port 2" });
        var after = await Photo(c, id, 21);
        await Review(c, id, after);
        await Post(c, $"/v1/inspections/{id}/acknowledge");
        var verified = await Post(c, $"/v1/inspections/{id}/verify", key: "verify-2");
        Assert.Equal("Verified", verified.GetProperty("decision").GetProperty("result").GetString());
        Assert.Equal("VerifiedCompleteSimulated", verified.GetProperty("decision").GetProperty("outcomeKind").GetString());
        Assert.Equal(before + 2, await Probes(c, id));
        var measured = (await Readiness(c, id)).GetProperty("checks")[0];
        Assert.True(measured.GetProperty("afterClaim").GetBoolean());
        Assert.True(measured.GetProperty("passed").GetBoolean());
    }

    [Fact]
    public async Task A_new_claim_cannot_be_verified_with_the_previous_claims_photo_or_acknowledgement()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await Claimed(c);
        var old = await Photo(c, id, 9);
        await Review(c, id, old);
        await Post(c, $"/v1/inspections/{id}/acknowledge", key: "ack-1");
        Assert.Equal("Denied", (await Post(c, $"/v1/inspections/{id}/verify")).GetProperty("decision").GetProperty("result").GetString());

        await Post(c, $"/v1/inspections/{id}/repair-claims", new { description = "Tried again" });
        await Review(c, id, old);                                              // the earlier photo, cited for the new claim
        var replayedAck = await Post(c, $"/v1/inspections/{id}/acknowledge", key: "ack-1");
        Assert.False(replayedAck.GetProperty("technicianAcknowledged").GetBoolean());   // an old key acknowledges nothing new
        await Post(c, $"/v1/inspections/{id}/acknowledge");
        var r = await Readiness(c, id);
        Assert.Equal("CapturePhoto", r.GetProperty("next").GetString());

        await SetEndpoint(c, true);
        var before = await Probes(c, id);
        var stale = await Post(c, $"/v1/inspections/{id}/verify");
        Assert.Equal("Denied", stale.GetProperty("decision").GetProperty("result").GetString());
        Assert.Contains("new post-repair image", stale.GetProperty("decision").GetProperty("reasons")[0].GetString());
        Assert.Equal(before, await Probes(c, id));
    }

    [Fact]
    public async Task An_unknown_review_is_held_without_a_probe_and_new_evidence_completes_it()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await Claimed(c);
        await SetEndpoint(c, true);
        var photo = await Photo(c, id, 9);
        await Review(c, id, photo);
        await Review(c, id, photo, "Unknown", only: Requirements[1]);
        await Post(c, $"/v1/inspections/{id}/acknowledge");
        var before = await Probes(c, id);
        var held = await Post(c, $"/v1/inspections/{id}/verify");
        Assert.Equal("NeedsHumanReview", held.GetProperty("decision").GetProperty("result").GetString());
        Assert.Equal(before, await Probes(c, id));
        Assert.Equal("CapturePhoto", (await Readiness(c, id)).GetProperty("next").GetString());

        var clearer = await Photo(c, id, 33);
        await Review(c, id, clearer);
        await Post(c, $"/v1/inspections/{id}/acknowledge");
        Assert.True((await Readiness(c, id)).GetProperty("ready").GetBoolean());
        Assert.Equal("Verified", (await Post(c, $"/v1/inspections/{id}/verify")).GetProperty("decision").GetProperty("result").GetString());
        Assert.Equal(before + 1, await Probes(c, id));
    }

    [Fact]
    public async Task A_restart_mid_attempt_keeps_readiness_and_a_retried_verify_replays_without_measuring()
    {
        string dir, id;
        using (var first = new FieldOpsApiFactory())
        {
            dir = first.DataDir;
            var c = first.CreateClient();
            id = await Claimed(c);
            var photo = await Photo(c, id, 9);
            await Review(c, id, photo);
            await Post(c, $"/v1/inspections/{id}/acknowledge");
            Assert.True((await Readiness(c, id)).GetProperty("ready").GetBoolean());
        }

        string decision;
        int probes;
        using (var second = FieldOpsApiFactory.Over(dir))
        {
            var c = second.CreateClient();
            Assert.True((await Readiness(c, id)).GetProperty("ready").GetBoolean());
            await SetEndpoint(c, true);
            var verified = await Post(c, $"/v1/inspections/{id}/verify", key: "verify-after-restart");
            Assert.Equal("Verified", verified.GetProperty("decision").GetProperty("result").GetString());
            decision = verified.GetProperty("decision").ToString();
            probes = await Probes(c, id);
        }

        using var third = FieldOpsApiFactory.Over(dir);
        var c3 = third.CreateClient();
        var retry = await Post(c3, $"/v1/inspections/{id}/verify", key: "verify-after-restart");
        Assert.True(retry.GetProperty("replayed").GetBoolean());
        Assert.Equal(decision, retry.GetProperty("decision").ToString());
        Assert.Equal(probes, await Probes(c3, id));
        Assert.Equal("None", (await Readiness(c3, id)).GetProperty("next").GetString());
    }
}
