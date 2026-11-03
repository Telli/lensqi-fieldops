using System.Net;
using System.Net.Http.Json;
using System.Text.Json;

namespace LensQi.FieldOps.Tests;

/// <summary>Operations asks the technician for more evidence; the request shows on the view and the list until a photo answers it.</summary>
public sealed class EvidenceRequestApiTests
{
    private static async Task<JsonElement> Json(HttpResponseMessage r) => JsonDocument.Parse(await r.Content.ReadAsStringAsync()).RootElement;

    private static async Task<string> NewInspection(HttpClient c)
    {
        var r = await c.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        return (await Json(r)).GetProperty("inspectionId").GetString()!;
    }

    private static Task<HttpResponseMessage> Upload(HttpClient c, string id) =>
        c.PostAsync($"/v1/inspections/{id}/observations", new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(640, 480)), "image", "f.png" } });

    private static async Task<JsonElement> Summary(HttpClient c, string id) =>
        (await Json(await c.GetAsync("/v1/inspections"))).EnumerateArray().First(s => s.GetProperty("inspectionId").GetString() == id);

    [Fact]
    public async Task Request_is_open_on_view_and_list_until_the_next_photo()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await NewInspection(c);
        Assert.Equal(HttpStatusCode.OK, (await Upload(c, id)).StatusCode);

        var r = await c.PostAsJsonAsync($"/v1/inspections/{id}/evidence-requests", new { note = "Show the label on port 2" });
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        var req = Assert.Single((await Json(r)).GetProperty("evidenceRequests").EnumerateArray());
        Assert.Equal("Operations desk", req.GetProperty("requestedBy").GetString());
        Assert.Equal("Show the label on port 2", (await Summary(c, id)).GetProperty("openEvidenceRequest").GetProperty("note").GetString());
        Assert.Equal(0, (await Summary(c, id)).GetProperty("verificationAttempts").GetInt32());

        var photo = await Json(await Upload(c, id));
        var answered = Assert.Single((await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("evidenceRequests").EnumerateArray());
        Assert.Equal(photo.GetProperty("evidenceId").GetString(), answered.GetProperty("answeredByPhotoId").GetString());
        Assert.Equal(JsonValueKind.Null, (await Summary(c, id)).GetProperty("openEvidenceRequest").ValueKind);
    }

    [Fact]
    public async Task Empty_or_oversized_note_is_refused_and_unknown_inspection_is_404()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await NewInspection(c);
        Assert.Equal(HttpStatusCode.BadRequest, (await c.PostAsJsonAsync($"/v1/inspections/{id}/evidence-requests", new { note = " " })).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await c.PostAsJsonAsync($"/v1/inspections/{id}/evidence-requests", new { note = new string('x', 501) })).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await c.PostAsJsonAsync("/v1/inspections/I-nope/evidence-requests", new { note = "x" })).StatusCode);
        Assert.Empty((await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("evidenceRequests").EnumerateArray());
    }

    /// <summary>"I can't safely get this view": only while a view is outstanding, closes as Inconclusive with the decline on record, and a retried key replays.</summary>
    [Fact]
    public async Task Declining_the_requested_view_closes_as_inconclusive_only_from_needs_evidence_and_replays_under_its_key()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await NewInspection(c);

        // Nothing has been asked for yet: refused with a stable code and the inspection untouched.
        var early = await c.PostAsync($"/v1/inspections/{id}/decline-view", null);
        Assert.Equal(HttpStatusCode.Conflict, early.StatusCode);
        Assert.Equal("invalid_state", (await Json(early)).GetProperty("code").GetString());
        Assert.Equal("Created", (await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("stage").GetString());

        var photo = (await Json(await Upload(c, id))).GetProperty("evidenceId").GetString();
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new
        {
            assetId = "LAB-SW-01",
            hypotheses = new[] { new { featureId = "yellow_cable_near_end_visible", status = "Unknown", reason = "occluded", evidenceIds = new[] { photo } } },
            nextAction = "RequestView", requestedView = "Show the connector from behind the rack", requestedSegmentationConcept = (string?)null,
        });
        Assert.Equal("NeedsEvidence", (await Json(await c.PostAsync($"/v1/inspections/{id}/investigate", null))).GetProperty("state").GetProperty("stage").GetString());

        using var first = new HttpRequestMessage(HttpMethod.Post, $"/v1/inspections/{id}/decline-view");
        first.Headers.Add("Idempotency-Key", "decline-1");
        var declined = await c.SendAsync(first);
        Assert.Equal(HttpStatusCode.OK, declined.StatusCode);
        var view = await Json(declined);
        Assert.Equal("Inconclusive", view.GetProperty("stage").GetString());
        Assert.Equal(JsonValueKind.Null, view.GetProperty("outcomeKind").ValueKind);
        var sequence = view.GetProperty("lastSequence").GetInt64();

        var events = (await Json(await c.GetAsync($"/v1/inspections/{id}/events"))).EnumerateArray().ToList();
        Assert.Contains(events, e => e.GetProperty("kind").GetString() == "ViewDeclined");
        var closing = events.Last(e => e.GetProperty("kind").GetString() == "StageChanged");
        Assert.Equal("Inconclusive", closing.GetProperty("to").GetString());
        Assert.Equal("technician declined the requested view", closing.GetProperty("reason").GetString());
        Assert.DoesNotContain(events, e => e.GetProperty("kind").GetString() is "RepairClaimed" or "WorkOrderDrafted" or "VerificationEvaluated");

        // The same key again is the same answer and no new event; a fresh decline on a closed inspection is refused.
        using var retry = new HttpRequestMessage(HttpMethod.Post, $"/v1/inspections/{id}/decline-view");
        retry.Headers.Add("Idempotency-Key", "decline-1");
        var replayed = await c.SendAsync(retry);
        Assert.Equal(HttpStatusCode.OK, replayed.StatusCode);
        Assert.Equal(sequence, (await Json(replayed)).GetProperty("lastSequence").GetInt64());
        var closed = await c.PostAsync($"/v1/inspections/{id}/decline-view", null);
        Assert.Equal(HttpStatusCode.Conflict, closed.StatusCode);
        Assert.Equal("invalid_state", (await Json(closed)).GetProperty("code").GetString());
        Assert.Equal(sequence, (await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("lastSequence").GetInt64());
    }

    [Fact]
    public async Task Knowledge_packs_list_their_source_documents()
    {
        using var f = new FieldOpsApiFactory();
        var packs = (await Json(await f.CreateClient().GetAsync("/api/vendors/knowledge-packs"))).EnumerateArray().ToList();
        Assert.NotEmpty(packs);
        foreach (var p in packs)
        {
            var docs = p.GetProperty("documents").EnumerateArray().ToList();
            Assert.Equal(p.GetProperty("documentCount").GetInt32(), docs.Count);
            Assert.Equal(p.GetProperty("factCount").GetInt32(), docs.Sum(d => d.GetProperty("factCount").GetInt32()));
            Assert.All(docs, d => Assert.False(string.IsNullOrEmpty(d.GetProperty("title").GetString())));
        }
    }
}
