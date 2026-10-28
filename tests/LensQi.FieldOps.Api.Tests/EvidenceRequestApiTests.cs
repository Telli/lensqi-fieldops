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
