using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Tests;

/// <summary>VK-T06/T24/T25 over HTTP with a fake Muse and the simulated probe.</summary>
public class KnowledgeProposalApiTests
{
    private const string Probe = """{"assetId":"LAB-SW-01","usedFactIds":["LAB-HEALTH-CHECK-01"],"hypothesis":"The endpoint answers once the cable is in P2.","requestedVisualEvidence":null,"proposedDiagnosticId":"NODE-A-HEALTH","nextAction":"RunApprovedProbe","prediction":{"diagnosticId":"NODE-A-HEALTH","predictedValue":"Reachable","basedOnFactIds":["LAB-HEALTH-CHECK-01"],"rationale":"cable reseated"}}""";

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

    private static async Task Upload(HttpClient c, string id, byte salt)
    {
        using var form = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(640, 480, salt)), "image", "f.png" }, { new StringContent("Phone"), "source" } };
        var r = await c.PostAsync($"/v1/inspections/{id}/observations", form);
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
    }

    private static async Task<string> NewInspection(HttpClient c)
    {
        var r = await c.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        var id = (await Json(r)).GetProperty("inspectionId").GetString()!;
        await Upload(c, id, 0);
        return id;
    }

    private static async Task<string[]> Kinds(HttpClient c, string id) =>
        (await Json(await c.GetAsync($"/v1/inspections/{id}/events"))).EnumerateArray().Select(e => e.GetProperty("kind").GetString()!).ToArray();

    private static async Task Claim(HttpClient c, string id, string description, byte salt)
    {
        await Post(c, $"/v1/inspections/{id}/repair-claims", new { description });
        await Upload(c, id, salt);
        var photo = (await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("photos").EnumerateArray().Last().GetProperty("evidenceId").GetString();
        foreach (var requirement in new[] { "switch_chassis_visible", "yellow_cable_near_end_visible", "post_repair_view_captured" })
            await Post(c, $"/v1/inspections/{id}/checklist", new { requirementId = requirement, status = "Satisfied", photoEvidenceId = photo });
        await Post(c, $"/v1/inspections/{id}/acknowledge");
    }

    [Fact]
    public async Task Predictions_are_registered_before_the_probe_and_the_fake_fix_is_falsified_then_the_real_fix_confirmed()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await NewInspection(c);
        await Post(c, "/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = false });
        await Post(c, $"/v1/inspections/{id}/diagnostics/NODE-A-HEALTH:run");
        await Post(c, $"/v1/inspections/{id}/work-order", new { summary = "Reseat the yellow cable" });

        // Fake fix: the model predicts the endpoint is back, the probe says otherwise.
        await Claim(c, id, "Reseated the cable", 1);
        f.FakeChat.CannedResponse = Probe;
        var first = await Post(c, $"/v1/inspections/{id}/knowledge/reason", new { observation = "cable reseated at P2" }, "reason-1");
        Assert.True(first.GetProperty("accepted").GetBoolean());
        var predictionId = first.GetProperty("prediction").GetProperty("predictionId").GetString();
        var kinds = await Kinds(c, id);
        Assert.Equal(["KnowledgeProposalAccepted", "PredictionRegistered"], kinds[^2..]);
        Assert.Equal("Denied", (await Post(c, $"/v1/inspections/{id}/verify")).GetProperty("decision").GetProperty("result").GetString());
        kinds = await Kinds(c, id);
        Assert.True(Array.LastIndexOf(kinds, "PredictionRegistered") < Array.LastIndexOf(kinds, "DiagnosticCompleted"));

        // Genuine repair: the same prediction is now confirmed, and the verdict still comes from the measurement.
        await Post(c, "/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = true });
        await Claim(c, id, "Moved the cable to P2", 2);
        await Post(c, $"/v1/inspections/{id}/knowledge/reason", new { observation = "cable moved to P2" }, "reason-2");
        Assert.Equal("Verified", (await Post(c, $"/v1/inspections/{id}/verify")).GetProperty("decision").GetProperty("result").GetString());

        var predictions = await Json(await c.GetAsync($"/v1/inspections/{id}/predictions"));
        Assert.Equal(["Falsified", "Confirmed"], predictions.GetProperty("predictions").EnumerateArray().Select(p => p.GetProperty("outcome").GetString()));
        Assert.Equal(predictionId, predictions.GetProperty("predictions")[0].GetProperty("predictionId").GetString());
        Assert.Equal("Unreachable", predictions.GetProperty("predictions")[0].GetProperty("measuredValue").GetString());
        Assert.Equal(1, predictions.GetProperty("tally").GetProperty("confirmed").GetInt32());

        // A retried key replays without a model call; a closed inspection is refused before one.
        var calls = f.FakeChat.Calls;
        var replay = await Post(c, $"/v1/inspections/{id}/knowledge/reason", new { observation = "cable moved to P2" }, "reason-2");
        Assert.True(replay.GetProperty("replayed").GetBoolean());
        var closed = await c.PostAsJsonAsync($"/v1/inspections/{id}/knowledge/reason", new { observation = "again" });
        Assert.Equal(HttpStatusCode.Conflict, closed.StatusCode);
        Assert.Equal(InspectionErrorCodes.InvalidState, (await Json(closed)).GetProperty("code").GetString());
        Assert.Equal(calls, f.FakeChat.Calls);
    }

    [Theory]
    [InlineData("""{"assetId":"LAB-SW-01","usedFactIds":["LAB-HEALTH-CHECK-01"],"hypothesis":null,"requestedVisualEvidence":null,"proposedDiagnosticId":"POST /rest/system/reboot","nextAction":"RunApprovedProbe","prediction":{"diagnosticId":"POST /rest/system/reboot","predictedValue":"Reachable","basedOnFactIds":[],"rationale":null}}""", "probe_not_allowlisted")]
    [InlineData("""{"assetId":"LAB-SW-01","usedFactIds":["MADE-UP-FACT"],"hypothesis":null,"requestedVisualEvidence":"Show P2","proposedDiagnosticId":null,"nextAction":"RequestView","prediction":null}""", "invalid_fact_reference")]
    [InlineData("""{"assetId":"LAB-SW-99","usedFactIds":[],"hypothesis":null,"requestedVisualEvidence":"Show P2","proposedDiagnosticId":null,"nextAction":"RequestView","prediction":null}""", "asset_mismatch")]
    [InlineData("""{"assetId":"LAB-SW-01","usedFactIds":["LAB-HEALTH-CHECK-01"],"hypothesis":null,"requestedVisualEvidence":null,"proposedDiagnosticId":null,"nextAction":"RunApprovedProbe","prediction":null}""", "action_not_allowed")]
    [InlineData("""{"assetId":"LAB-SW-01","usedFactIds":["LAB-HEALTH-CHECK-01"],"hypothesis":null,"requestedVisualEvidence":null,"proposedDiagnosticId":"NODE-A-HEALTH","nextAction":"RunApprovedProbe","prediction":null}""", "prediction_missing")]
    [InlineData("""{"assetId":"LAB-SW-01","usedFactIds":["LAB-HEALTH-CHECK-01"],"hypothesis":null,"requestedVisualEvidence":null,"proposedDiagnosticId":"NODE-A-HEALTH","nextAction":"RunApprovedProbe","prediction":{"diagnosticId":"NODE-A-HEALTH","predictedValue":"LinkReportedUp","basedOnFactIds":[],"rationale":null}}""", "prediction_value_invalid")]
    [InlineData("not json", "action_not_allowed")]
    public async Task A_rejected_proposal_is_on_the_event_log_and_nothing_is_run(string response, string code)
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await NewInspection(c);
        f.FakeChat.CannedResponse = response;
        var stage = (await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("stage").GetString();
        var before = (await Kinds(c, id)).Length;

        var r = await Post(c, $"/v1/inspections/{id}/knowledge/reason", new { observation = "link light is off on port 2" });
        Assert.False(r.GetProperty("accepted").GetBoolean());
        Assert.Contains(code, r.GetProperty("rejections").EnumerateArray().Select(x => x.GetProperty("code").GetString()));

        var events = (await Json(await c.GetAsync($"/v1/inspections/{id}/events"))).EnumerateArray().ToArray();
        Assert.Equal(before + 1, events.Length);                          // the rejection is the only thing the call appended
        var rejected = events[^1];
        Assert.Equal("KnowledgeProposalRejected", rejected.GetProperty("kind").GetString());
        Assert.Equal(r.GetProperty("proposalId").GetString(), rejected.GetProperty("proposalId").GetString());
        Assert.Contains(code, rejected.GetProperty("rejections").EnumerateArray().Select(x => x.GetProperty("code").GetString()));
        Assert.Equal(stage, (await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("stage").GetString());
    }
}
