using System.Net;
using Microsoft.Extensions.DependencyInjection;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using System.Net.Http.Json;
using System.Text.Json;

namespace LensQi.FieldOps.Tests;

/// <summary>GET /v1/fixtures/{id}/pack: what a phone downloads before going offline. Everything in it is what the live endpoints would say.</summary>
public sealed class SitePackApiTests
{
    private static async Task<JsonElement> Json(HttpResponseMessage r) => JsonDocument.Parse(await r.Content.ReadAsStringAsync()).RootElement;

    private static async Task<JsonElement> Pack(HttpClient c, string fixtureId = "LAB-RACK-01")
    {
        var r = await c.GetAsync($"/v1/fixtures/{fixtureId}/pack");
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        return await Json(r);
    }

    private static async Task<string> NewInspection(HttpClient c)
    {
        var r = await c.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        Assert.Equal(HttpStatusCode.Created, r.StatusCode);
        return (await Json(r)).GetProperty("inspectionId").GetString()!;
    }

    private static async Task<string> Upload(HttpClient c, string id)
    {
        var r = await c.PostAsync($"/v1/inspections/{id}/observations", new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(640, 480)), "image", "f.png" } });
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        return (await Json(r)).GetProperty("evidenceId").GetString()!;
    }

    private static IEnumerable<string> Ids(JsonElement rows) => rows.EnumerateArray().Select(x => x.GetProperty("inspectionId").GetString()!);

    [Fact]
    public async Task Pack_waits_for_a_command_and_materializes_the_matching_summary_and_view()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var id = await NewInspection(c);
        var store = f.Services.GetRequiredService<InspectionStore>();
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var command = store.ExecuteAsync(id, async session =>
        {
            entered.SetResult();
            await release.Task;
            session.RequestEvidence("pack-race", "Show connector", "desk", DateTimeOffset.UtcNow);
            return true;
        }, CancellationToken.None);
        await entered.Task;
        var download = Pack(c);
        try { await Assert.ThrowsAsync<TimeoutException>(async () => await download.WaitAsync(TimeSpan.FromMilliseconds(150))); }
        finally { release.SetResult(); await command; }
        var pack = await download;
        var summary = Assert.Single(pack.GetProperty("inspections").EnumerateArray());
        var view = Assert.Single(pack.GetProperty("open").EnumerateArray());
        Assert.Equal("Show connector", summary.GetProperty("openEvidenceRequest").GetProperty("note").GetString());
        Assert.Equal("Show connector", Assert.Single(view.GetProperty("evidenceRequests").EnumerateArray()).GetProperty("note").GetString());
    }

    [Fact]
    public async Task Pack_carries_the_fixture_as_listed_and_every_assets_knowledge()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var pack = await Pack(c);
        var listed = (await Json(await c.GetAsync("/v1/fixtures")))[0];

        Assert.Equal("LAB-RACK-01", pack.GetProperty("fixtureId").GetString());
        Assert.Equal(listed.GetProperty("fixtureRevision").GetInt32(), pack.GetProperty("fixtureRevision").GetInt32());
        Assert.Equal(listed.GetProperty("sha256").GetString(), pack.GetProperty("sha256").GetString());
        Assert.True(DateTimeOffset.TryParse(pack.GetProperty("generatedAt").GetString(), out var generated) && generated.Offset == TimeSpan.Zero, "generatedAt is a UTC timestamp");
        // The same object the list returns, byte for byte: an offline client sees exactly what an online one does.
        Assert.Equal(listed.GetRawText(), pack.GetProperty("fixture").GetRawText());

        var assets = pack.GetProperty("assets").EnumerateArray().ToDictionary(a => a.GetProperty("assetId").GetString()!, a => a.GetProperty("knowledge"));
        Assert.Equal(listed.GetProperty("assets").EnumerateArray().Select(a => a.GetProperty("assetId").GetString()).OrderBy(x => x), assets.Keys.OrderBy(x => x));

        var bound = assets["LAB-SW-01"];
        Assert.Equal("Applicable", bound.GetProperty("status").GetString());
        Assert.Equal("lab-switch-01.synthetic", bound.GetProperty("packId").GetString());
        Assert.Equal(64, bound.GetProperty("packSha256").GetString()!.Length);
        Assert.Equal("Synthetic", bound.GetProperty("originKind").GetString());
        Assert.NotEmpty(bound.GetProperty("facts").EnumerateArray());
        Assert.Contains("NODE-A-HEALTH", bound.GetProperty("approvedDiagnostics").EnumerateArray().Select(d => d.GetString()));
        var fact = bound.GetProperty("facts")[0];
        foreach (var name in new[] { "factId", "factKey", "assertedValue", "statement", "claimKind", "provenance", "safety", "tags", "citation" })
            Assert.True(fact.TryGetProperty(name, out _), $"fact has {name}");

        // The node has no binding: a NoPack view with the plain warning, never a 404 that would make the pack unusable.
        var unbound = assets["LAB-NODE-A"];
        Assert.Equal("NoPack", unbound.GetProperty("status").GetString());
        Assert.Equal(JsonValueKind.Null, unbound.GetProperty("packId").ValueKind);
        Assert.Empty(unbound.GetProperty("facts").EnumerateArray());
        Assert.Empty(unbound.GetProperty("excluded").EnumerateArray());
        Assert.Empty(unbound.GetProperty("approvedDiagnostics").EnumerateArray());
        Assert.Equal("No vendor-knowledge binding for this asset", Assert.Single(unbound.GetProperty("warnings").EnumerateArray()).GetString());

        // The per-inspection knowledge endpoint says the same thing about the bound asset.
        var id = await NewInspection(c);
        var live = await Json(await c.GetAsync($"/v1/inspections/{id}/knowledge"));
        Assert.Equal(bound.GetProperty("facts").GetRawText(), live.GetProperty("facts").GetRawText());
        Assert.Equal(bound.GetProperty("packSha256").GetString(), live.GetProperty("packSha256").GetString());
    }

    [Fact]
    public async Task Open_inspections_travel_in_full_and_leave_open_when_closed_but_stay_in_the_summaries()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        Assert.Empty((await Pack(c)).GetProperty("inspections").EnumerateArray());

        var id = await NewInspection(c);
        var photo = await Upload(c, id);
        var pack = await Pack(c);
        Assert.Equal([id], Ids(pack.GetProperty("inspections")));
        var open = Assert.Single(pack.GetProperty("open").EnumerateArray());
        Assert.Equal(id, open.GetProperty("inspectionId").GetString());
        Assert.Equal(photo, Assert.Single(open.GetProperty("photos").EnumerateArray()).GetProperty("evidenceId").GetString());
        Assert.True(open.TryGetProperty("readiness", out _), "the open view is the full GET /v1/inspections/{id} view");
        Assert.Equal(1, Assert.Single(pack.GetProperty("inspections").EnumerateArray()).GetProperty("photos").GetInt32());

        // Muse (fake) asks for a view the technician cannot safely take; declining closes the inspection as Inconclusive.
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new
        {
            assetId = "LAB-SW-01",
            hypotheses = new[] { new { featureId = "yellow_cable_near_end_visible", status = "Unknown", reason = "occluded", evidenceIds = new[] { photo } } },
            nextAction = "RequestView", requestedView = "Show the back of the rack", requestedSegmentationConcept = (string?)null,
        });
        Assert.Equal("NeedsEvidence", (await Json(await c.PostAsync($"/v1/inspections/{id}/investigate", null))).GetProperty("state").GetProperty("stage").GetString());
        var declined = await c.PostAsync($"/v1/inspections/{id}/decline-view", null);
        Assert.Equal(HttpStatusCode.OK, declined.StatusCode);
        Assert.Equal("Inconclusive", (await Json(declined)).GetProperty("stage").GetString());

        // A second, still-open inspection sorts first: newest activity on top.
        var newer = await NewInspection(c);
        pack = await Pack(c);
        Assert.Equal([newer, id], Ids(pack.GetProperty("inspections")));
        Assert.Equal([newer], Ids(pack.GetProperty("open")));
        Assert.Equal("Inconclusive", pack.GetProperty("inspections").EnumerateArray().Single(s => s.GetProperty("inspectionId").GetString() == id).GetProperty("stage").GetString());
    }

    [Fact]
    public async Task Unknown_fixture_is_404_and_downloading_a_pack_records_nothing()
    {
        using var f = new FieldOpsApiFactory();
        var c = f.CreateClient();
        var r = await c.GetAsync("/v1/fixtures/LAB-RACK-99/pack");
        Assert.Equal(HttpStatusCode.NotFound, r.StatusCode);
        Assert.Equal("not_found", (await Json(r)).GetProperty("code").GetString());

        var id = await NewInspection(c);
        var before = (await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("lastSequence").GetInt64();
        await Pack(c);
        await Pack(c);
        Assert.Equal(before, (await Json(await c.GetAsync($"/v1/inspections/{id}"))).GetProperty("lastSequence").GetInt64());
    }
}
