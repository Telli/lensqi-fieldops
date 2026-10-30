using System.Text.Json;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Core.Tasks;

namespace LensQi.FieldOps.Tests;

/// <summary>The person and the room: height and tools on the task, access steps, and skips that keep their measured reason.</summary>
public class TaskEnvironmentTests
{
    private static readonly DateTimeOffset T0 = new(2026, 10, 30, 9, 0, 0, TimeSpan.Zero);

    private static TaskPlan Plan() => TaskSessionTests.Plan(RiskLevel.Low, RestrictedCategory.None, false,
        new TaskStep("s1", "Set up the step stool", "Open the step stool under the detector.", true, ["step stool"], [new Caution(CautionKind.Height, "Keep three points of contact")], [], null, AccessFor: "smoke detector"),
        new TaskStep("s2", "Press the test button", "Press and hold the test button.", true, ["test button"], [], [], null));

    [Fact]
    public void A_person_is_recorded_with_an_estimated_reach_and_checked()
    {
        var s = TaskSession.Create("T-1", TaskMode.Diy, "Test my smoke detector", null, null, T0, person: new PersonContext(188, ["step stool"]));
        Assert.Equal(188, s.State.Person!.HeightCm);
        Assert.Equal(250, s.State.Person.StandingReachCm);
        Assert.Null(TaskSession.Create("T-2", TaskMode.Diy, "Test my smoke detector", null, null, T0, person: new PersonContext(null, [])).State.Person);
        Assert.Equal(TaskErrorCodes.InvalidInput, Assert.Throws<TaskConflictException>(() =>
            TaskSession.Create("T-3", TaskMode.Diy, "Test my smoke detector", null, null, T0, person: new PersonContext(40, []))).Code);
        Assert.Equal(TaskErrorCodes.InvalidInput, Assert.Throws<TaskConflictException>(() =>
            TaskSession.Create("T-4", TaskMode.Diy, "Test my smoke detector", null, null, T0, person: new PersonContext(180, ["see https://example.com"]))).Code);

        // Replay keeps the person; older logs without one still load.
        var json = s.Events.Select(e => JsonSerializer.Serialize(e, KnowledgeJson.Options)).ToList();
        Assert.Equal(188, TaskSession.Rehydrate(json.Select(l => JsonSerializer.Deserialize<TaskEvent>(l, KnowledgeJson.Options)!)).State.Person!.HeightCm);
    }

    [Fact]
    public void A_skip_keeps_its_measured_reason_and_a_bad_reason_is_refused()
    {
        var s = TaskSession.Create("T-1", TaskMode.Diy, "Test my smoke detector", null, null, T0, person: new PersonContext(188, []));
        s.ProposePlan("plan", Plan(), "muse", "p", "r", [], T0);
        s.AcceptPlan("accept", true, T0);
        Assert.Equal(TaskErrorCodes.InvalidInput, Assert.Throws<TaskConflictException>(() => s.SkipStep("bad", "s1", T0, new SkipReason("", 240, 250))).Code);
        var reason = new SkipReason("The smoke detector is within reach standing", 240, 250);
        s.SkipStep("skip", "s1", T0, reason);
        Assert.Equal(reason, s.State.SkipReasons["s1"]);
        Assert.Equal("s2", s.State.CurrentStepId);
        Assert.Equal(reason, TaskSession.Rehydrate(s.Events).State.SkipReasons["s1"]);
    }

    [Fact]
    public void An_access_step_must_name_a_plain_part()
    {
        var plan = Plan();
        TaskPlanValidator.Validate(plan, "Test my smoke detector", null);
        var bad = plan with { Steps = [plan.Steps[0] with { AccessFor = "the thing at https://example.com" }, plan.Steps[1]] };
        var refused = Assert.Throws<TaskConflictException>(() => TaskPlanValidator.Validate(bad, "Test my smoke detector", null));
        Assert.Equal(TaskErrorCodes.InvalidPlan, refused.Code);
        Assert.Contains("accessFor", refused.Message);
    }
}
