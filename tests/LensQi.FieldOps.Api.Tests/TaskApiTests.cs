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

    private static MultipartFormDataContent Image(string field = "image", int w = 640, int h = 480, string? source = null)
    {
        var form = new MultipartFormDataContent();
        form.Add(new ByteArrayContent(TestImages.Png(w, h)), field, "photo.png");
        if (source is not null) form.Add(new StringContent(source), "source");
        return form;
    }

    [Fact]
    public async Task Freeform_job_titles_follow_the_work_and_survive_a_restart()
    {
        using var factory = new FieldOpsApiFactory(); using var client = factory.CreateClient();
        var (id, created) = await Create(client, new { mode = "Freeform" });
        Assert.Equal("Just ask", created.GetProperty("task").GetProperty("title").GetString());
        async Task Ask(string question, string? title)
        {
            factory.FakeChat.CannedResponse = JsonSerializer.Serialize(new { answer = "Inspect the switch label.", focus = Array.Empty<string>(), sessionTitle = title });
            using var form = new MultipartFormDataContent { { new StringContent(question), "question" } };
            Assert.True((await client.PostAsync($"/v1/tasks/{id}/ask", form)).IsSuccessStatusCode);
        }
        await Ask("What is this switch?", null);
        Assert.Equal("What is this switch?", (await Body(await client.GetAsync($"/v1/tasks/{id}"))).GetProperty("title").GetString());
        const string topic = "Diagnosing a switch cable";
        await Ask("How can I tell which cable is loose?", topic);
        await Ask("What next?", "Just ask");
        Assert.True((await client.PostAsync($"/v1/tasks/{id}/finish", null)).IsSuccessStatusCode);
        using var restart = FieldOpsApiFactory.Over(factory.DataDir); using var after = restart.CreateClient();
        var job = await Body(await after.GetAsync($"/v1/tasks/{id}"));
        Assert.Equal(topic, job.GetProperty("title").GetString());
        var history = await Body(await after.GetAsync("/v1/tasks"));
        Assert.Equal(topic, history.EnumerateArray().Single(t => t.GetProperty("taskId").GetString() == id).GetProperty("title").GetString());
        using var zip = new System.IO.Compression.ZipArchive(new MemoryStream(await after.GetByteArrayAsync($"/v1/tasks/{id}/package.zip")));
        using var html = new StreamReader(zip.GetEntry("summary.html")!.Open());
        Assert.Contains($"<h1>{topic}</h1>", await html.ReadToEndAsync());
    }

    [Fact]
    public async Task A_step_check_keeps_the_photo_s_capture_device_and_refuses_an_unknown_one()
    {
        using var f = new FieldOpsApiFactory(); f.FakeChat.CannedResponse = PlanJson(); using var c = f.CreateClient();
        var (id, _) = await Create(c, new { mode = "Diy", goal = "Change the oil in my 2025 Civic" });
        await c.PostAsJsonAsync($"/v1/tasks/{id}/accept", new { safetyAcknowledged = true });
        await c.PostAsync($"/v1/tasks/{id}/steps/s1:complete", null);
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { verdict = "CantTell", reason = "The plug is out of frame." });

        var bogus = await c.PostAsync($"/v1/tasks/{id}/steps/s2/check", Image(source: "Webcam"));
        Assert.Equal(HttpStatusCode.BadRequest, bogus.StatusCode);
        Assert.Equal("invalid_source", (await Body(bogus)).GetProperty("code").GetString());
        Assert.Empty((await Body(await c.GetAsync($"/v1/tasks/{id}"))).GetProperty("photos").EnumerateArray());

        var mock = await Body(await c.PostAsync($"/v1/tasks/{id}/steps/s2/check", Image(source: "MockWearables")));
        var photoId = mock.GetProperty("check").GetProperty("photoId").GetString();
        var photo = mock.GetProperty("task").GetProperty("photos").EnumerateArray().Single(p => p.GetProperty("evidenceId").GetString() == photoId);
        Assert.Equal("MockWearables", photo.GetProperty("origin").GetString());

        // No source is a phone capture, as for inspection photos; the origin survives a restart with the event log.
        var phone = await Body(await c.PostAsync($"/v1/tasks/{id}/steps/s2/check", Image()));
        var phoneId = phone.GetProperty("check").GetProperty("photoId").GetString();
        var reloaded = await Body(await c.GetAsync($"/v1/tasks/{id}"));
        var origins = reloaded.GetProperty("photos").EnumerateArray().ToDictionary(p => p.GetProperty("evidenceId").GetString()!, p => p.GetProperty("origin").GetString());
        Assert.Equal("MockWearables", origins[photoId!]);
        Assert.Equal("Phone", origins[phoneId!]);
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
        using var capture = Image();
        Assert.True((await c.PostAsync($"/v1/inspections/{inspectionId}/observations", capture)).IsSuccessStatusCode);
        Assert.True((await c.PostAsync($"/v1/inspections/{inspectionId}/diagnostics/NODE-A-HEALTH:run", null)).IsSuccessStatusCode);
        async Task<int> Versions() => (await Body(await c.GetAsync($"/v1/inspections/{inspectionId}/receipts"))).GetProperty("versions").GetArrayLength();
        async Task<(System.IO.Compression.ZipArchive Zip, string Summary)> Package()
        {
            var zip = new System.IO.Compression.ZipArchive(new MemoryStream(await c.GetByteArrayAsync($"/v1/tasks/{id}/package.zip")));
            using var reader = new StreamReader(zip.GetEntry("summary.html")!.Open());
            return (zip, await reader.ReadToEndAsync());
        }
        // Downloading the package never issues a receipt: before one exists it says so and carries none.
        var (unissued, before) = await Package();
        Assert.Null(unissued.GetEntry("fieldproof/receipt.html"));
        Assert.Contains("No FieldProof receipt has been issued", before);
        Assert.Contains("Simulated", before);
        Assert.Equal(0, await Versions());
        Assert.True((await c.GetAsync($"/v1/inspections/{inspectionId}/receipt.html")).IsSuccessStatusCode);
        Assert.Equal(1, await Versions());
        var (package, summary) = await Package();
        Assert.NotNull(package.GetEntry("fieldproof/receipt.html"));
        Assert.Contains(package.Entries, e => e.FullName.StartsWith("fieldproof/artifacts/", StringComparison.Ordinal));
        Assert.Contains("FieldProof receipt v1, issued", summary);
        Assert.DoesNotContain("recorded more since", summary);
        // The record moves on: the package still carries v1, says the record has grown, and still issues nothing.
        Assert.True((await c.PostAsync($"/v1/inspections/{inspectionId}/diagnostics/NODE-A-HEALTH:run", null)).IsSuccessStatusCode);
        var (_, later) = await Package();
        Assert.Contains("FieldProof receipt v1, issued", later);
        Assert.Contains("recorded more since", later);
        Assert.Equal(1, await Versions());

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

    [Fact]
    public async Task A_question_retried_after_a_failed_model_call_keeps_its_first_photo()
    {
        using var f = new FieldOpsApiFactory(); using var c = f.CreateClient();
        var (id, _) = await Create(c, new { mode = "Freeform" });
        // The retry carries a newer frame; the answer is about the photo the first attempt recorded.
        HttpRequestMessage Ask(int w) { var form = Image(w: w); form.Add(new StringContent("What is this?"), "question"); var m = new HttpRequestMessage(HttpMethod.Post, $"/v1/tasks/{id}/ask") { Content = form }; m.Headers.Add("Idempotency-Key", "ask-photo-1"); return m; }
        f.FakeChat.Throw = new HttpRequestException("down");
        Assert.Equal(HttpStatusCode.Conflict, (await c.SendAsync(Ask(640))).StatusCode);
        f.FakeChat.Throw = null;
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { answer = "A network switch.", focus = Array.Empty<string>() });
        var r = await c.SendAsync(Ask(800));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal(TestImages.Png(640, 480), f.FakeChat.LastImage);
        var task = await Body(await c.GetAsync($"/v1/tasks/{id}"));
        var photos = task.GetProperty("photos").EnumerateArray().ToList();
        Assert.Single(photos);
        Assert.Equal(photos[0].GetProperty("evidenceId").GetString(), (await Body(r)).GetProperty("photoId").GetString());
    }
    [Fact]
    public async Task Restricted_step_check_reasons_never_return_hands_on_instructions()
    {
        using var f = new FieldOpsApiFactory();
        f.FakeChat.CannedResponse = PlanJson(handsOn: false, restricted: "MainsElectrical", professional: true, risk: "High",
            instruction: "Photograph the panel label and have a licensed electrician do the work.", title: "Document the panel");
        using var c = f.CreateClient();
        var (id, _) = await Create(c, new { mode = "Diy", goal = "Replace the breaker panel" });
        await c.PostAsJsonAsync($"/v1/tasks/{id}/accept", new { safetyAcknowledged = true });
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { verdict = "NotYet", reason = "Remove the panel cover." });
        var response = await c.PostAsync($"/v1/tasks/{id}/steps/s1/check", Image());
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = await Body(response);
        Assert.Equal(LensQi.FieldOps.Core.Tasks.TaskSafety.Referral, body.GetProperty("check").GetProperty("reason").GetString());
        Assert.Equal("InProgress", body.GetProperty("task").GetProperty("phase").GetString());
        var events = await c.GetStringAsync($"/v1/tasks/{id}/events");
        Assert.DoesNotContain("Remove the panel cover", events);
    }

    [Fact]
    public async Task Selected_conversation_and_reference_pages_survive_restart_and_export()
    {
        using var factory = new FieldOpsApiFactory();
        using var client = factory.CreateClient();
        var (sourceId, _) = await Create(client, new { mode = "Freeform", details = "Variant B" });
        factory.FakeChat.CannedResponse = JsonSerializer.Serialize(new { answer = "The label says B.", focus = Array.Empty<string>() });
        using var ask = Image();
        ask.Add(new StringContent("Which variant?"), "question");
        Assert.True((await client.PostAsync($"/v1/tasks/{sourceId}/ask", ask)).IsSuccessStatusCode);
        var session = await Body(await client.GetAsync($"/v1/tasks/{sourceId}"));
        var answer = session.GetProperty("answers")[0];
        var photo = session.GetProperty("photos")[0].GetProperty("evidenceId").GetString()!;
        MultipartFormDataContent Form(string selectedPhoto) => new()
        {
            { new StringContent("Diy"), "mode" }, { new StringContent("Inspect the labelled equipment"), "goal" },
            { new StringContent(sourceId), "sourceTaskId" }, { new StringContent(selectedPhoto), "sourcePhotoId" },
            { new StringContent(answer.GetProperty("at").GetString()!), "sourceAnswerAt" }
        };
        using var invalid = Form("unrelated-photo");
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsync("/v1/tasks", invalid)).StatusCode);
        var plan = System.Text.Json.Nodes.JsonNode.Parse(PlanJson())!;
        plan["steps"]![0]!["referencePhotoIds"] = new System.Text.Json.Nodes.JsonArray("fabricated-page");
        factory.FakeChat.CannedResponse = plan.ToJsonString();
        using var form = Form(photo);
        var result = await Body(await client.PostAsync("/v1/tasks", form));
        Assert.Equal("invalid_model_output", result.GetProperty("planError").GetProperty("code").GetString());
        var job = result.GetProperty("task"); var id = job.GetProperty("taskId").GetString()!;
        var reference = job.GetProperty("photos")[0].GetProperty("evidenceId").GetString()!;
        plan["steps"]![0]!["referencePhotoIds"] = new System.Text.Json.Nodes.JsonArray(reference);
        factory.FakeChat.CannedResponse = plan.ToJsonString();
        Assert.True((await client.PostAsync($"/v1/tasks/{id}/plan", null)).IsSuccessStatusCode);
        using var restart = FieldOpsApiFactory.Over(factory.DataDir); using var after = restart.CreateClient();
        var saved = await Body(await after.GetAsync($"/v1/tasks/{id}"));
        var handoff = saved.GetProperty("handoff");
        Assert.Equal("Variant B", handoff.GetProperty("equipmentDetails").GetString());
        Assert.Equal(sourceId, handoff.GetProperty("sourceTaskId").GetString());
        Assert.Equal("The label says B.", handoff.GetProperty("answers")[0].GetProperty("answer").GetString());
        Assert.Equal(reference, saved.GetProperty("plan").GetProperty("steps")[0].GetProperty("referencePhotoIds")[0].GetString());
        using var zip = new System.IO.Compression.ZipArchive(new MemoryStream(await after.GetByteArrayAsync($"/v1/tasks/{id}/package.zip")));
        using var reader = new StreamReader(zip.GetEntry("summary.html")!.Open());
        var summary = await reader.ReadToEndAsync();
        Assert.Contains("No linked verification record", summary);
        Assert.Contains("Variant B", summary);
        Assert.Contains(reference, summary);
        using var file = Assert.Single(zip.Entries, e => e.FullName.StartsWith("photos/", StringComparison.Ordinal)).Open();
        using var bytes = new MemoryStream(); await file.CopyToAsync(bytes);
        Assert.Equal(TestImages.Png(640, 480), bytes.ToArray());
    }

    [Fact]
    public async Task Technician_plans_use_only_applicable_vendor_citations_and_preserve_them_on_reload()
    {
        using var f = new FieldOpsApiFactory(); using var c = f.CreateClient();
        var node = System.Text.Json.Nodes.JsonNode.Parse(PlanJson())!;
        node["steps"]![0]!["factIds"] = new System.Text.Json.Nodes.JsonArray("LAB-HEALTH-CHECK-01");
        f.FakeChat.CannedResponse = node.ToJsonString();
        var (id, result) = await Create(c, new { mode = "Technician", goal = "Inspect the lab switch", fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        var fact = result.GetProperty("task").GetProperty("plan").GetProperty("steps")[0].GetProperty("sourceFacts")[0];
        Assert.Equal("LAB-HEALTH-CHECK-01", fact.GetProperty("factId").GetString());
        Assert.Equal(64, fact.GetProperty("citation").GetProperty("packSha256").GetString()!.Length);
        Assert.Equal("SiteConfigured", fact.GetProperty("provenance").GetString());
        using var restarted = FieldOpsApiFactory.Over(f.DataDir);
        using var afterRestart = restarted.CreateClient();
        var reloaded = await Body(await afterRestart.GetAsync($"/v1/tasks/{id}"));
        Assert.Equal(fact.ToString(), reloaded.GetProperty("plan").GetProperty("steps")[0].GetProperty("sourceFacts")[0].ToString());

        node["steps"]![0]!["factIds"] = new System.Text.Json.Nodes.JsonArray("invented-fact");
        f.FakeChat.CannedResponse = node.ToJsonString();
        var (_, invalid) = await Create(c, new { mode = "Technician", goal = "Inspect the lab switch", fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        Assert.Equal(JsonValueKind.Null, invalid.GetProperty("task").GetProperty("plan").ValueKind);
        Assert.Equal("invalid_model_output", invalid.GetProperty("planError").GetProperty("code").GetString());
    }
}
