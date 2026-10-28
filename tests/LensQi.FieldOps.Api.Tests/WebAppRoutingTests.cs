using System.Net;
using System.Net.Http.Json;
using System.Text.Json;

namespace LensQi.FieldOps.Tests;

/// <summary>The single-page app is served for client routes; API paths keep JSON errors; the list endpoint reads the logs.</summary>
public class WebAppRoutingTests : IClassFixture<FieldOpsApiFactory>
{
    private readonly HttpClient _c;
    public WebAppRoutingTests(FieldOpsApiFactory f) => _c = f.CreateClient();

    [Theory]
    [InlineData("/")]
    [InlineData("/m")]
    [InlineData("/m/i/I-anything/verify")]
    [InlineData("/ops/inspections")]
    public async Task Client_routes_load_the_app(string path)
    {
        var r = await _c.GetAsync(path);
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.StartsWith("text/html", r.Content.Headers.ContentType?.MediaType);
        Assert.Contains("<div id=\"root\">", await r.Content.ReadAsStringAsync());
    }

    [Theory]
    [InlineData("/v1/no-such-route")]
    [InlineData("/api/nothing")]
    public async Task Api_misses_stay_json_404(string path)
    {
        var r = await _c.GetAsync(path);
        Assert.Equal(HttpStatusCode.NotFound, r.StatusCode);
        Assert.Equal("not_found", JsonDocument.Parse(await r.Content.ReadAsStringAsync()).RootElement.GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_missing_static_asset_is_404_not_the_app() =>
        Assert.Equal(HttpStatusCode.NotFound, (await _c.GetAsync("/assets/missing.js")).StatusCode);

    [Fact]
    public async Task Inspection_list_summarises_from_the_event_log_newest_first()
    {
        var a = (await (await _c.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" })).Content.ReadFromJsonAsync<JsonElement>()).GetProperty("inspectionId").GetString();
        await Task.Delay(20);
        var b = (await (await _c.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-NODE-A" })).Content.ReadFromJsonAsync<JsonElement>()).GetProperty("inspectionId").GetString();
        var list = (await _c.GetFromJsonAsync<JsonElement>("/v1/inspections")).EnumerateArray().ToList();
        var ids = list.Select(x => x.GetProperty("inspectionId").GetString()).ToList();
        Assert.True(ids.IndexOf(b) < ids.IndexOf(a));
        var first = list.Single(x => x.GetProperty("inspectionId").GetString() == b);
        Assert.Equal("Created", first.GetProperty("stage").GetString());
        Assert.Equal("LAB-NODE-A", first.GetProperty("assetId").GetString());
        Assert.Equal(0, first.GetProperty("photos").GetInt32());
    }
}
