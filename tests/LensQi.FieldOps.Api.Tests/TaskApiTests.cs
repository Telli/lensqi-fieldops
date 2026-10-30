using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using LensQi.FieldOps.Api.Features.Tasks;

namespace LensQi.FieldOps.Tests;

public class TaskApiTests
{
    internal static string PlanJson(bool handsOn = true, string restricted = "None", bool professional = false, string risk = "Moderate", bool checkRequired = true, string summary = "Drain the old oil, replace the filter, refill.",
        string instruction = "Loosen the drain plug with the socket.", string title = "Remove the drain plug") =>
        JsonSerializer.Serialize(new
        {
            title = "Change the engine oil", summary, risk, estimatedMinutes = 45, restricted, professionalRequired = professional,
            professionalReason = professional ? "Panel work needs a licensed electrician" : null,
            tools = new[] { "Socket set", "Drain pan" }, parts = new[] { "Engine oil (check your manual for grade)" },
            steps = new object[]
            {
                new { title = "Let the engine cool", instruction = "Wait 30 minutes after driving.", handsOn = false, lookFor = Array.Empty<string>(),
                    cautions = new[] { new { kind = "Hot", text = "Oil and exhaust can burn you" } }, stopIf = Array.Empty<string>(), check = new { required = false, whatDoneLooksLike = "" } },
                new { title, instruction, handsOn, lookFor = new[] { "oil drain plug" },
                    cautions = new[] { new { kind = "Hot", text = "Oil may still be warm" } }, stopIf = new[] { "the plug threads are stripped" },
                    check = new { required = checkRequired, whatDoneLooksLike = "The plug is back in and dry" } },
            },
        });

    private static async Task<JsonElement> Body(HttpResponseMessage r) => JsonDocument.Parse(await r.Content.ReadAsStringAsync()).RootElement.Clone();

    private static async Task<(string id, JsonElement task)> Create(HttpClient c, object body)
    {
        var r = await c.PostAsJsonAsync("/v1/tasks", body);
        Assert.Equal(HttpStatusCode.Created, r.StatusCode);
        var json = await Body(r);
        return (json.GetProperty("task").GetProperty("taskId").GetString()!, json);
    }

    private static MultipartFormDataContent Image(string field = "image", int w = 640, int h = 480)
    {
        var form = new MultipartFormDataContent();
        form.Add(new ByteArrayContent(TestImages.Png(w, h)), field, "photo.png");
        return form;
    }

    [Fact]
    public async Task A_DIY_goal_becomes_a_labelled_plan_that_runs_step_by_step()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(checkRequired: false); using var c = f.CreateClient();
        var (id, created) = await Create(c, new { mode = "Diy", goal = "Change the oil in my 2025 Civic" });
        var plan = created.GetProperty("task").GetProperty("plan");
        Assert.Equal("AI-written plan · check your owner's manual", plan.GetProperty("sourceNote").GetString());
        Assert.Equal("s2", plan.GetProperty("steps")[1].GetProperty("id").GetString());
        Assert.True(plan.GetProperty("needsSafetyAck").GetBoolean());

        var refused = await c.PostAsJsonAsync($"/v1/tasks/{id}/accept", new { safetyAcknowledged = false });
        Assert.Equal("safety_not_acknowledged", (await Body(refused)).GetProperty("code").GetString());
        var started = await Body(await c.PostAsJsonAsync($"/v1/tasks/{id}/accept", new { safetyAcknowledged = true }));
        Assert.Equal("s1", started.GetProperty("currentStepId").GetString());

        await c.PostAsync($"/v1/tasks/{id}/steps/s1:complete", null);
        var done = await Body(await c.PostAsync($"/v1/tasks/{id}/steps/s2:complete", null));
        Assert.Equal("Completed", done.GetProperty("phase").GetString());
        Assert.Contains((await Body(await c.GetAsync("/v1/tasks?mode=diy"))).EnumerateArray(), t => t.GetProperty("taskId").GetString() == id);
    }

    [Fact]
    public async Task Restricted_work_never_gets_a_hands_on_plan()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(); using var c = f.CreateClient();
        var (id, created) = await Create(c, new { mode = "Diy", goal = "Replace the breaker panel in my garage" });
        Assert.Equal("unsafe_plan", created.GetProperty("planError").GetProperty("code").GetString());
        Assert.Equal(JsonValueKind.Null, created.GetProperty("task").GetProperty("plan").ValueKind);

        f.FakeChat.CannedResponse = PlanJson(handsOn: false, restricted: "MainsElectrical", professional: true, risk: "High", checkRequired: false,
            instruction: "Photograph the panel label and have a licensed electrician do the work.", title: "Document the panel");
        var replanned = await Body(await c.PostAsync($"/v1/tasks/{id}/plan", null));
        Assert.True(replanned.GetProperty("plan").GetProperty("professionalRequired").GetBoolean());
        Assert.All(replanned.GetProperty("plan").GetProperty("steps").EnumerateArray(), s => Assert.False(s.GetProperty("handsOn").GetBoolean()));
    }

    [Fact]
    public async Task A_plan_with_a_link_is_rejected_and_provider_failures_are_recoverable()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(summary: "Watch https://example.com first"); using var c = f.CreateClient();
        var (id, created) = await Create(c, new { mode = "Diy", goal = "Mount an IP camera on the porch" });
        Assert.Equal("invalid_plan", created.GetProperty("planError").GetProperty("code").GetString());
        f.FakeChat.Throw = new HttpRequestException("down");
        var retry = await c.PostAsync($"/v1/tasks/{id}/plan", null);
        Assert.Equal(HttpStatusCode.Conflict, retry.StatusCode);
        Assert.Equal("provider_unavailable", (await Body(retry)).GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_step_photo_gets_a_suggested_verdict_and_the_user_still_decides()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(); using var c = f.CreateClient();
        var (id, _) = await Create(c, new { mode = "Diy", goal = "Change the oil in my 2025 Civic" });
        await c.PostAsJsonAsync($"/v1/tasks/{id}/accept", new { safetyAcknowledged = true });
        await c.PostAsync($"/v1/tasks/{id}/steps/s1:complete", null);

        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { verdict = "NotYet", reason = "Oil is still dripping from the plug." });
        var checkedStep = await Body(await c.PostAsync($"/v1/tasks/{id}/steps/s2/check", Image()));
        Assert.Equal("NotYet", checkedStep.GetProperty("check").GetProperty("verdict").GetString());
        Assert.Equal("Active", checkedStep.GetProperty("task").GetProperty("plan").GetProperty("steps")[1].GetProperty("status").GetString());
        var photoId = checkedStep.GetProperty("check").GetProperty("photoId").GetString();
        var artifacts = Directory.EnumerateFiles(Path.Combine(f.DataDir, "evidence", id)).Select(Path.GetFileName).ToList();
        Assert.Contains(artifacts, a => a!.StartsWith(photoId + "-"));
        Assert.Equal(HttpStatusCode.OK, (await c.GetAsync($"/v1/tasks/{id}/artifacts/{photoId}")).StatusCode);

        var done = await Body(await c.PostAsync($"/v1/tasks/{id}/steps/s2:complete", null));
        Assert.Equal("Completed", done.GetProperty("phase").GetString());
    }

    [Fact]
    public async Task A_spoken_question_is_answered_from_the_frame_and_steers_the_next_outline()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(); using var c = f.CreateClient();
        var (id, _) = await Create(c, new { mode = "Diy", goal = "Change the oil in my 2025 Civic" });
        await c.PostAsJsonAsync($"/v1/tasks/{id}/accept", new { safetyAcknowledged = true });
        await c.PostAsync($"/v1/tasks/{id}/steps/s2:start", null);

        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { answer = "Yes, that bolt at the lowest point of the oil pan is the drain plug.", focus = new[] { "oil pan" } });
        var form = Image(); form.Add(new StringContent("Is this the drain plug?"), "question");
        var answer = await Body(await c.PostAsync($"/v1/tasks/{id}/ask", form));
        Assert.StartsWith("Yes, that bolt", answer.GetProperty("answer").GetString());
        Assert.Equal("s2", answer.GetProperty("stepId").GetString());

        var frame = Image(); frame.Add(new StringContent("frame-1"), "frameId");
        Assert.Equal(HttpStatusCode.OK, (await c.PostAsync($"/v1/tasks/{id}/live/detect", frame)).StatusCode);
        Assert.Equal(["oil drain plug", "oil pan"], f.FakeSam.Concepts);

        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { answer = "See http://bad.example", focus = Array.Empty<string>() });
        var bad = new MultipartFormDataContent { { new StringContent("Where is the filter?"), "question" } };
        Assert.Equal(HttpStatusCode.Conflict, (await c.PostAsync($"/v1/tasks/{id}/ask", bad)).StatusCode);
        var events = await c.GetStringAsync($"/v1/tasks/{id}/events");
        Assert.Single(JsonDocument.Parse(events).RootElement.EnumerateArray(), e => e.GetProperty("kind").GetString() == "QuestionAnswered");
    }

    [Fact]
    public async Task Live_coaching_follows_the_current_step()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(); using var c = f.CreateClient();
        var (id, _) = await Create(c, new { mode = "Diy", goal = "Change the oil in my 2025 Civic" });
        var early = Image(); early.Add(new StringContent("frame-0"), "frameId");
        await c.PostAsJsonAsync($"/v1/tasks/{id}/accept", new { safetyAcknowledged = true });
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { seen = "The underside of a car.", screenText = (string?)null, recommendation = "Find the drain plug at the lowest point of the oil pan.", focus = new[] { "oil pan" }, expectedEquipmentInView = true });
        var guide = await Body(await c.PostAsync($"/v1/tasks/{id}/live/guide", early));
        Assert.StartsWith("Find the drain plug", guide.GetProperty("recommendation").GetString());
        Assert.DoesNotContain("LiveScene", await c.GetStringAsync($"/v1/tasks/{id}/events"));
    }

    [Fact]
    public async Task A_technician_task_on_a_site_record_hands_over_to_FieldProof()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(); using var c = f.CreateClient();
        Assert.Equal(HttpStatusCode.NotFound, (await c.PostAsJsonAsync("/v1/tasks", new { mode = "Technician", goal = "Reseat the cable", fixtureId = "LAB-RACK-01", assetId = "NOPE" })).StatusCode);
        var (id, _) = await Create(c, new { mode = "Technician", goal = "Reseat the yellow cable on port 2", fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        var linked = await Body(await c.PostAsync($"/v1/tasks/{id}/link-inspection", null));
        var inspectionId = linked.GetProperty("inspectionId").GetString();
        Assert.Equal(HttpStatusCode.OK, (await c.GetAsync($"/v1/inspections/{inspectionId}")).StatusCode);
        Assert.Equal(inspectionId, (await Body(await c.PostAsync($"/v1/tasks/{id}/link-inspection", null))).GetProperty("inspectionId").GetString());

        var (diy, _) = await Create(c, new { mode = "Diy", goal = "Change the oil" });
        Assert.Equal(HttpStatusCode.Conflict, (await c.PostAsync($"/v1/tasks/{diy}/link-inspection", null)).StatusCode);
    }

    [Fact]
    public async Task Concurrent_links_create_one_inspection()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(); using var c = f.CreateClient();
        var (id, _) = await Create(c, new { mode = "Technician", goal = "Reseat the yellow cable on port 2", fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });

        var responses = await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => c.PostAsync($"/v1/tasks/{id}/link-inspection", null)));
        Assert.All(responses, r => Assert.Equal(HttpStatusCode.OK, r.StatusCode));
        var linked = (await Task.WhenAll(responses.Select(Body))).Select(b => b.GetProperty("inspectionId").GetString()).Distinct().ToList();
        Assert.Single(linked);
        Assert.Single((await Body(await c.GetAsync("/v1/inspections"))).EnumerateArray());
        var events = JsonDocument.Parse(await c.GetStringAsync($"/v1/tasks/{id}/events")).RootElement;
        Assert.Single(events.EnumerateArray(), e => e.GetProperty("kind").GetString() == "InspectionLinked");
    }

    [Fact]
    public async Task A_step_check_retried_with_the_same_key_reuses_its_photo_and_replays_its_verdict()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(); using var c = f.CreateClient();
        var (id, _) = await Create(c, new { mode = "Diy", goal = "Change the oil in my 2025 Civic" });
        await c.PostAsJsonAsync($"/v1/tasks/{id}/accept", new { safetyAcknowledged = true });
        await c.PostAsync($"/v1/tasks/{id}/steps/s1:complete", null);
        Task<HttpResponseMessage> Check() => c.SendAsync(new HttpRequestMessage(HttpMethod.Post, $"/v1/tasks/{id}/steps/s2/check")
            { Content = Image(), Headers = { { "Idempotency-Key", "check-retry-1" } } });

        f.FakeChat.Throw = new HttpRequestException("down");
        var failed = await Check();
        Assert.Equal("provider_unavailable", (await Body(failed)).GetProperty("code").GetString());

        f.FakeChat.Throw = null;
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { verdict = "LooksDone", reason = "The plug is back in and the pan is dry." });
        var retried = await Check();
        Assert.Equal(HttpStatusCode.OK, retried.StatusCode);
        var check = (await Body(retried)).GetProperty("check");
        Assert.Equal("LooksDone", check.GetProperty("verdict").GetString());

        var events = JsonDocument.Parse(await c.GetStringAsync($"/v1/tasks/{id}/events")).RootElement.EnumerateArray().ToList();
        var photo = Assert.Single(events, e => e.GetProperty("kind").GetString() == "TaskPhotoRecorded");
        Assert.Equal(photo.GetProperty("evidenceId").GetString(), check.GetProperty("photoId").GetString());
        Assert.Single(events, e => e.GetProperty("kind").GetString() == "StepChecked");
        Assert.Single(Directory.EnumerateFiles(Path.Combine(f.DataDir, "evidence", id)));

        var calls = f.FakeChat.Images;
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { verdict = "NotYet", reason = "Different answer that must not be used." });
        var replayed = (await Body(await Check())).GetProperty("check");
        Assert.Equal("LooksDone", replayed.GetProperty("verdict").GetString());
        Assert.Equal(calls, f.FakeChat.Images);
    }

    [Fact]
    public async Task An_unexpected_server_error_does_not_reveal_its_details()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(); using var c = f.CreateClient();
        var (id, _) = await Create(c, new { mode = "Diy", goal = "Change the oil in my 2025 Civic" });
        await c.PostAsJsonAsync($"/v1/tasks/{id}/accept", new { safetyAcknowledged = true });
        // A file where the task's evidence folder should be makes saving the question frame fail with an IOException.
        Directory.CreateDirectory(Path.Combine(f.DataDir, "evidence"));
        File.WriteAllText(Path.Combine(f.DataDir, "evidence", id), "not a folder");
        var form = Image(); form.Add(new StringContent("Is this the drain plug?"), "question");

        var r = await c.PostAsync($"/v1/tasks/{id}/ask", form);
        Assert.Equal(HttpStatusCode.InternalServerError, r.StatusCode);
        var text = await r.Content.ReadAsStringAsync();
        Assert.Equal("internal", JsonDocument.Parse(text).RootElement.GetProperty("code").GetString());
        Assert.DoesNotContain("Exception", text);
        Assert.DoesNotContain(f.DataDir, text);
    }

    [Fact]
    public async Task Tasks_survive_a_restart()
    {
        string dir, id;
        using (var f = new FieldOpsApiFactory())
        {
            f.FakeChat.CannedResponse = PlanJson(); using var c = f.CreateClient();
            (id, _) = await Create(c, new { mode = "Diy", goal = "Change the oil in my 2025 Civic" });
            await c.PostAsJsonAsync($"/v1/tasks/{id}/accept", new { safetyAcknowledged = true });
            await c.SendAsync(Keyed($"/v1/tasks/{id}/steps/s1:complete", "complete-s1"));
            dir = f.DataDir;
        }
        using var second = FieldOpsApiFactory.Over(dir); using var c2 = second.CreateClient();
        var task = await Body(await c2.GetAsync($"/v1/tasks/{id}"));
        Assert.Equal("InProgress", task.GetProperty("phase").GetString());
        Assert.Equal("s2", task.GetProperty("currentStepId").GetString());

        // A retry of the same request after the restart replays instead of failing as "already finished".
        var events = JsonDocument.Parse(await c2.GetStringAsync($"/v1/tasks/{id}/events")).RootElement.GetArrayLength();
        Assert.Equal(HttpStatusCode.OK, (await c2.SendAsync(Keyed($"/v1/tasks/{id}/steps/s1:complete", "complete-s1"))).StatusCode);
        Assert.Equal(events, JsonDocument.Parse(await c2.GetStringAsync($"/v1/tasks/{id}/events")).RootElement.GetArrayLength());
    }

    private static HttpRequestMessage Keyed(string path, string key) =>
        new(HttpMethod.Post, path) { Headers = { { "Idempotency-Key", key } } };

    [Fact]
    public async Task Manual_photos_ground_the_plan_and_are_kept()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(); using var c = f.CreateClient();
        var form = Image("manual"); form.Add(new StringContent("Diy"), "mode"); form.Add(new StringContent("Change the oil in my 2025 Civic"), "goal");
        var r = await c.PostAsync("/v1/tasks", form);
        Assert.Equal(HttpStatusCode.Created, r.StatusCode);
        var task = (await Body(r)).GetProperty("task");
        Assert.Contains("uses 1 manual photo", task.GetProperty("plan").GetProperty("sourceNote").GetString());
        Assert.Equal("Manual", task.GetProperty("photos")[0].GetProperty("purpose").GetString());
        Assert.Equal(1, f.FakeChat.Images);
    }

    [Fact]
    public async Task Free_form_help_skips_the_planner_answers_from_the_frame_and_keeps_the_safety_referral()
    {
        using var f = new FieldOpsApiFactory(); using var c = f.CreateClient();
        var (id, created) = await Create(c, new { mode = "Freeform" });
        Assert.Equal("InProgress", created.GetProperty("task").GetProperty("phase").GetString());
        Assert.Equal(0, f.FakeChat.Images);

        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { answer = "The screen says the router has no internet. Check the WAN light.", focus = new[] { "WAN light" } });
        var form = Image(); form.Add(new StringContent("What does this error mean?"), "question");
        Assert.StartsWith("The screen says", (await Body(await c.PostAsync($"/v1/tasks/{id}/ask", form))).GetProperty("answer").GetString());

        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { answer = "Turn the valve a quarter turn.", focus = Array.Empty<string>() });
        var gas = new MultipartFormDataContent { { new StringContent("How do I disconnect this gas line?"), "question" } };
        Assert.EndsWith(TaskEndpoints.Referral, (await Body(await c.PostAsync($"/v1/tasks/{id}/ask", gas))).GetProperty("answer").GetString());

        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { seen = "A router with a red light.", screenText = (string?)null, recommendation = "Look at the WAN light.", focus = new[] { "WAN light" }, expectedEquipmentInView = false });
        var frame = Image(); frame.Add(new StringContent("frame-1"), "frameId");
        Assert.Equal(HttpStatusCode.OK, (await c.PostAsync($"/v1/tasks/{id}/live/guide", frame)).StatusCode);

        Assert.Equal(HttpStatusCode.Conflict, (await c.PostAsync($"/v1/tasks/{id}/plan", null)).StatusCode);
        Assert.Equal("Completed", (await Body(await c.PostAsync($"/v1/tasks/{id}/finish", null))).GetProperty("phase").GetString());
    }

    [Fact]
    public async Task A_hands_on_answer_in_restricted_work_is_replaced_by_the_referral()
    {
        using var f = new FieldOpsApiFactory(); using var c = f.CreateClient();
        var (id, _) = await Create(c, new { mode = "Freeform" });
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { answer = "No professional needed: unscrew the panel cover and remove the old breaker.", focus = Array.Empty<string>() });
        var q = new MultipartFormDataContent { { new StringContent("How do I replace a circuit breaker?"), "question" } };
        Assert.Equal(TaskEndpoints.Referral, (await Body(await c.PostAsync($"/v1/tasks/{id}/ask", q))).GetProperty("answer").GetString());
    }

    [Fact]
    public async Task A_retried_question_returns_the_recorded_answer_without_another_model_call()
    {
        using var f = new FieldOpsApiFactory(); using var c = f.CreateClient();
        var (id, _) = await Create(c, new { mode = "Freeform" });
        f.FakeChat.Responses.Enqueue(JsonSerializer.Serialize(new { answer = "First answer.", focus = Array.Empty<string>() }));
        f.FakeChat.Responses.Enqueue(JsonSerializer.Serialize(new { answer = "Second answer.", focus = Array.Empty<string>() }));
        HttpRequestMessage Ask() { var m = new HttpRequestMessage(HttpMethod.Post, $"/v1/tasks/{id}/ask") { Content = new MultipartFormDataContent { { new StringContent("What is this?"), "question" } } }; m.Headers.Add("Idempotency-Key", "ask-1"); return m; }
        Assert.Equal("First answer.", (await Body(await c.SendAsync(Ask()))).GetProperty("answer").GetString());
        Assert.Equal("First answer.", (await Body(await c.SendAsync(Ask()))).GetProperty("answer").GetString());
        Assert.Equal(1, f.FakeChat.Calls);
    }

    [Fact]
    public async Task Bad_create_requests_are_400_and_a_repeated_manual_page_counts_once()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(); using var c = f.CreateClient();
        Assert.Equal(HttpStatusCode.BadRequest, (await c.PostAsync("/v1/tasks", new StringContent("{not json", System.Text.Encoding.UTF8, "application/json"))).StatusCode);
        var form = Image("manual"); form.Add(new ByteArrayContent(TestImages.Png(640, 480)), "manual", "again.png");
        form.Add(new StringContent("Diy"), "mode"); form.Add(new StringContent("Change the oil in my 2025 Civic"), "goal");
        var r = await c.PostAsync("/v1/tasks", form);
        Assert.Equal(HttpStatusCode.Created, r.StatusCode);
        var task = (await Body(r)).GetProperty("task");
        Assert.Single(task.GetProperty("photos").EnumerateArray());
        Assert.Equal(JsonValueKind.Object, task.GetProperty("plan").ValueKind);
    }

    [Fact]
    public async Task Live_coaching_is_given_the_current_step_and_waits_for_the_safety_notes()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(); using var c = f.CreateClient();
        var (id, _) = await Create(c, new { mode = "Diy", goal = "Change the oil in my 2025 Civic" });
        HttpContent Frame(string n) { var x = Image(); x.Add(new StringContent(n), "frameId"); return x; }
        Assert.Equal(HttpStatusCode.Conflict, (await c.PostAsync($"/v1/tasks/{id}/live/guide", Frame("frame-0"))).StatusCode);
        await c.PostAsJsonAsync($"/v1/tasks/{id}/accept", new { safetyAcknowledged = true });
        await c.PostAsync($"/v1/tasks/{id}/steps/s1:complete", null);
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { seen = "A car.", screenText = (string?)null, recommendation = "Find the plug.", focus = Array.Empty<string>(), expectedEquipmentInView = true });
        Assert.Equal(HttpStatusCode.OK, (await c.PostAsync($"/v1/tasks/{id}/live/guide", Frame("frame-1"))).StatusCode);
        Assert.Contains("Remove the drain plug", f.FakeChat.LastText);
        Assert.Equal(HttpStatusCode.Conflict, (await c.PostAsync($"/v1/tasks/{id}/steps/s1:start", null)).StatusCode);
    }
}
