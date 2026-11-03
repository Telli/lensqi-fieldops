using System.Net;
using System.Net.Http.Json;
using System.Text.Json;

namespace LensQi.FieldOps.Tests;

/// <summary>Inspections survive an API restart: every persisted JSONL log is replayed through the Core session.</summary>
public class RestartTests
{
    private static async Task<JsonElement> Json(HttpResponseMessage r) => JsonDocument.Parse(await r.Content.ReadAsStringAsync()).RootElement;

    private static async Task<JsonElement> Post(HttpClient c, string url, object? body = null)
    {
        var r = body is null ? await c.PostAsync(url, null) : await c.PostAsJsonAsync(url, body);
        Assert.True(r.IsSuccessStatusCode, $"{url} -> {(int)r.StatusCode} {await r.Content.ReadAsStringAsync()}");
        return await Json(r);
    }

    private static async Task Upload(HttpClient c, string id, byte salt)
    {
        using var form = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(640, 480, salt)), "image", "f.png" } };
        Assert.Equal(HttpStatusCode.OK, (await c.PostAsync($"/v1/inspections/{id}/observations", form)).StatusCode);
    }

    [Fact]
    public async Task A_restarted_api_keeps_inspections_and_continues_their_event_logs()
    {
        string id; long sequence; string stage; string claim;
        string dir;
        using (var first = new FieldOpsApiFactory())
        {
            dir = first.DataDir;
            var c = first.CreateClient();
            await Post(c, "/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = false });
            id = (await Post(c, "/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" })).GetProperty("inspectionId").GetString()!;
            await Upload(c, id, 1);
            await Post(c, $"/v1/inspections/{id}/diagnostics/NODE-A-HEALTH:run");
            await Post(c, $"/v1/inspections/{id}/work-order", new { summary = "Reseat" });
            var claimed = await Post(c, $"/v1/inspections/{id}/repair-claims", new { description = "Reseated" });
            claim = claimed.GetProperty("claimId").GetString()!;
            var before = await Json(await c.GetAsync($"/v1/inspections/{id}"));
            sequence = before.GetProperty("lastSequence").GetInt64();
            stage = before.GetProperty("stage").GetString()!;
        }

        using var second = FieldOpsApiFactory.Over(dir);
        var c2 = second.CreateClient();
        var after = await Json(await c2.GetAsync($"/v1/inspections/{id}"));
        Assert.Equal(stage, after.GetProperty("stage").GetString());
        Assert.Equal(sequence, after.GetProperty("lastSequence").GetInt64());
        Assert.Equal(claim, after.GetProperty("currentClaimId").GetString());

        // the log continues where it stopped: a new photo appends sequence n+1 and the receipt still verifies
        await Upload(c2, id, 2);
        var events = (await Json(await c2.GetAsync($"/v1/inspections/{id}/events"))).EnumerateArray().Select(e => e.GetProperty("sequence").GetInt64()).ToList();
        Assert.Equal(Enumerable.Range(1, events.Count).Select(i => (long)i), events);
        Assert.True(events.Count > sequence);
        Assert.True((await Json(await c2.GetAsync($"/v1/inspections/{id}/receipt/verify"))).GetProperty("isValid").GetBoolean());
        Assert.Equal(events.Count, File.ReadAllLines(Path.Combine(dir, "inspections", id + ".jsonl")).Length);
    }

    [Fact]
    public async Task A_create_retried_after_a_restart_still_returns_the_inspection_its_key_made()
    {
        string id, dir;
        var body = new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" };
        using (var first = new FieldOpsApiFactory())
        {
            dir = first.DataDir;
            var req = new HttpRequestMessage(HttpMethod.Post, "/v1/inspections") { Content = JsonContent.Create(body), Headers = { { "Idempotency-Key", "create-survives-restart" } } };
            id = (await Json(await first.CreateClient().SendAsync(req))).GetProperty("inspectionId").GetString()!;
        }
        using var second = FieldOpsApiFactory.Over(dir);
        var retry = new HttpRequestMessage(HttpMethod.Post, "/v1/inspections") { Content = JsonContent.Create(body), Headers = { { "Idempotency-Key", "create-survives-restart" } } };
        var r = await second.CreateClient().SendAsync(retry);
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal(id, (await Json(r)).GetProperty("inspectionId").GetString());
    }

    [Fact]
    public async Task Corrupt_foreign_or_gapped_logs_are_left_on_disk_and_never_half_loaded()
    {
        string dir; string good;
        using (var first = new FieldOpsApiFactory())
        {
            dir = first.DataDir;
            good = (await Post(first.CreateClient(), "/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" })).GetProperty("inspectionId").GetString()!;
        }
        var logs = Path.Combine(dir, "inspections");
        var created = File.ReadAllLines(Path.Combine(logs, good + ".jsonl"))[0];
        File.WriteAllText(Path.Combine(logs, "I-corrupt00000.jsonl"), "{not json\n");
        File.WriteAllText(Path.Combine(logs, "I-foreign0000.jsonl"), created.Replace(good, "I-foreign0000").Replace("\"fixtureDigest\":\"", "\"fixtureDigest\":\"0") + "\n");
        File.WriteAllText(Path.Combine(logs, "I-gapped00000.jsonl"), created.Replace(good, "I-gapped00000").Replace("\"sequence\":1", "\"sequence\":2") + "\n");

        using var second = FieldOpsApiFactory.Over(dir);
        var c = second.CreateClient();
        Assert.Equal(HttpStatusCode.OK, (await c.GetAsync("/health")).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await c.GetAsync($"/v1/inspections/{good}")).StatusCode);
        foreach (var bad in new[] { "I-corrupt00000", "I-foreign0000", "I-gapped00000" })
        {
            Assert.Equal(HttpStatusCode.NotFound, (await c.GetAsync($"/v1/inspections/{bad}")).StatusCode);
            Assert.True(File.Exists(Path.Combine(logs, bad + ".jsonl")));
        }
    }

    [Fact]
    public async Task A_retried_photo_upload_after_a_restart_is_not_recorded_twice()
    {
        string dir, id;
        HttpRequestMessage Upload(string inspectionId)
        {
            var form = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(640, 480)), "image", "a.png" } };
            var m = new HttpRequestMessage(HttpMethod.Post, $"/v1/inspections/{inspectionId}/observations") { Content = form };
            m.Headers.Add("Idempotency-Key", "capture-1");
            return m;
        }
        using (var first = new FieldOpsApiFactory())
        {
            using var c = first.CreateClient();
            id = (await Post(c, "/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" })).GetProperty("inspectionId").GetString()!;
            Assert.Equal(HttpStatusCode.OK, (await c.SendAsync(Upload(id))).StatusCode);
            dir = first.DataDir;
        }
        using var second = FieldOpsApiFactory.Over(dir);
        using var c2 = second.CreateClient();
        await c2.SendAsync(Upload(id));
        var view = await Json(await c2.GetAsync($"/v1/inspections/{id}"));
        Assert.Single(view.GetProperty("photos").EnumerateArray());
    }

    [Fact]
    public async Task A_retried_task_creation_returns_the_same_task_and_plans_once_before_and_after_a_restart()
    {
        string dir, id;
        static HttpRequestMessage Create()
        {
            var m = new HttpRequestMessage(HttpMethod.Post, "/v1/tasks") { Content = JsonContent.Create(new { mode = "Diy", goal = "Change the oil in my car" }) };
            m.Headers.Add("Idempotency-Key", "create-1");
            return m;
        }
        using (var first = new FieldOpsApiFactory())
        {
            first.FakeChat.CannedResponse = TaskApiTests.PlanJson();
            using var c = first.CreateClient();
            var made = await c.SendAsync(Create());
            Assert.Equal(HttpStatusCode.Created, made.StatusCode);
            id = (await Json(made)).GetProperty("task").GetProperty("taskId").GetString()!;
            var again = await Json(await c.SendAsync(Create()));
            Assert.Equal(id, again.GetProperty("task").GetProperty("taskId").GetString());
            Assert.True(again.GetProperty("replayed").GetBoolean());
            Assert.Equal(1, first.FakeChat.Calls);
            dir = first.DataDir;
        }
        using var second = FieldOpsApiFactory.Over(dir);
        using var c2 = second.CreateClient();
        Assert.Equal(id, (await Json(await c2.SendAsync(Create()))).GetProperty("task").GetProperty("taskId").GetString());
        Assert.Single((await Json(await c2.GetAsync("/v1/tasks"))).EnumerateArray());
        Assert.Equal(0, second.FakeChat.Calls);
    }
}
