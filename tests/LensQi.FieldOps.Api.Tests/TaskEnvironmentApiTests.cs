using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using LensQi.FieldOps.Api.Features.LiveVision;
using LensQi.FieldOps.Meta.Segmentation;

namespace LensQi.FieldOps.Tests;

/// <summary>One box per physical part in live detection, and plans and coaching that fit the person and the measured room.</summary>
public class TaskEnvironmentApiTests
{
    private static BoxRecord Box(string id, double l, double t, double r, double b, double c = 0.9) => new(id, l, t, r, b, c);

    [Fact]
    public void One_box_per_part_keeps_the_first_name_and_real_sub_parts()
    {
        var kept = LiveVisionEndpoints.OnePerPart([
            ("smoke detector", [Box("a", 100, 100, 200, 200), Box("b", 110, 110, 190, 190, 0.8)]),   // SAM's body and whole: one detector
            ("test button", [Box("c", 140, 140, 160, 160)]),                                           // inside the detector, still its own part
            ("ceiling detector", [Box("d", 102, 98, 203, 201)]),                                       // the same detector under another name
            ("closet door", [Box("e", 400, 100, 600, 500), Box("f", 700, 100, 800, 500), Box("g", 900, 100, 950, 500, 0.5)]),
        ]);
        Assert.Equal(["smoke detector", "test button", "closet door", "closet door"], kept.Select(k => k.Concept));
        Assert.Equal(["a", "c", "e", "f"], kept.Select(k => k.Box.ObjectId));   // at most two per name
    }

    private static string AccessPlan() => JsonSerializer.Serialize(new
    {
        title = "Test the smoke detector", summary = "Reach the detector and press its test button.", risk = "Low", estimatedMinutes = 10,
        restricted = "None", professionalRequired = false, professionalReason = (string?)null, tools = new[] { "Step stool" }, parts = Array.Empty<string>(),
        steps = new object[]
        {
            new { title = "Set up the step stool", instruction = "Open the step stool under the detector, only if you cannot reach it standing.", handsOn = true,
                lookFor = new[] { "step stool" }, accessFor = "smoke detector", cautions = new[] { new { kind = "Height", text = "Keep three points of contact" } },
                stopIf = Array.Empty<string>(), check = new { required = false, whatDoneLooksLike = "" } },
            new { title = "Press the test button", instruction = "Press and hold the test button until it sounds.", handsOn = true,
                lookFor = new[] { "test button" }, accessFor = (string?)null, cautions = new[] { new { kind = "Other", text = "The alarm is loud" } },
                stopIf = Array.Empty<string>(), check = new { required = false, whatDoneLooksLike = "" } },
        },
    });

    private static async Task<JsonElement> Json(HttpResponseMessage r) => JsonDocument.Parse(await r.Content.ReadAsStringAsync()).RootElement.Clone();

    [Fact]
    public async Task The_plan_and_coach_get_the_person_and_the_measured_part_and_a_skip_keeps_its_measured_reason()
    {
        using var f = new FieldOpsApiFactory();
        f.FakeChat.CannedResponse = AccessPlan();
        var c = f.CreateClient();
        var bad = await c.PostAsJsonAsync("/v1/tasks", new { mode = "Diy", goal = "Test my smoke detector", heightCm = 40 });
        Assert.Equal(HttpStatusCode.Conflict, bad.StatusCode);

        var created = await Json(await c.PostAsJsonAsync("/v1/tasks", new { mode = "Diy", goal = "Test my smoke detector", heightCm = 188, tools = new[] { "step stool" } }));
        Assert.Contains("\"standingReachCm\":250", f.FakeChat.LastText);   // the planner saw who is doing the job
        var task = created.GetProperty("task");
        var id = task.GetProperty("taskId").GetString();
        Assert.Equal(250, task.GetProperty("person").GetProperty("standingReachCm").GetInt32());
        Assert.Equal("smoke detector", task.GetProperty("plan").GetProperty("steps")[0].GetProperty("accessFor").GetString());
        (await c.PostAsJsonAsync($"/v1/tasks/{id}/accept", new { safetyAcknowledged = true })).EnsureSuccessStatusCode();

        // A question with what the phone measured: the coach sees the numbers; malformed numbers are refused.
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new { answer = "It is 240 cm up, within your reach standing.", focus = new[] { "smoke detector" } });
        using (var form = new MultipartFormDataContent { { new StringContent("Do I need the stool?"), "question" }, { new StringContent("""[{"part":"smoke detector","distanceCm":95,"heightAboveFloorCm":240}]"""), "measured" } })
            (await c.PostAsync($"/v1/tasks/{id}/ask", form)).EnsureSuccessStatusCode();
        Assert.Contains("\"heightAboveFloorCm\":240", f.FakeChat.LastText);
        using (var form = new MultipartFormDataContent { { new StringContent("Do I need the stool?"), "question" }, { new StringContent("""[{"part":"see https://x.example","distanceCm":-4}]"""), "measured" } })
        {
            var refused = await c.PostAsync($"/v1/tasks/{id}/ask", form);
            Assert.Equal(HttpStatusCode.BadRequest, refused.StatusCode);
            Assert.Equal("invalid_measured", (await Json(refused)).GetProperty("code").GetString());
        }

        var skipped = await Json(await c.PostAsJsonAsync($"/v1/tasks/{id}/steps/s1:skip", new { reason = "The smoke detector is within reach standing", partHeightCm = 240, reachCm = 250 }));
        var why = skipped.GetProperty("plan").GetProperty("steps")[0].GetProperty("skipReason");
        Assert.Equal((240, 250), (why.GetProperty("partHeightCm").GetInt32(), why.GetProperty("reachCm").GetInt32()));
        Assert.Equal("s2", skipped.GetProperty("currentStepId").GetString());
    }
}
