using LensQi.FieldOps.Api.Features.LiveVision;
using LensQi.FieldOps.Api.Infrastructure.Evidence;
using LensQi.FieldOps.Api.Infrastructure.Fixtures;
using LensQi.FieldOps.Api.Infrastructure.Hardening;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Infrastructure.Tasks;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Tasks;
using LensQi.FieldOps.Meta.Tasks;

namespace LensQi.FieldOps.Api.Features.Tasks;

/// <summary>
/// Guided tasks for DIY users and technicians. Endpoints translate HTTP into <see cref="TaskSession"/> commands; models
/// write plans, coach and suggest verdicts, the validator and the session decide what is recorded, and the user decides
/// when a step is done.
/// </summary>
public static class TaskEndpoints
{
    public sealed record AcceptRequest(bool SafetyAcknowledged);
    public sealed record AbandonRequest(string? Reason);

    public static IEndpointRouteBuilder MapTasks(this IEndpointRouteBuilder app)
    {
        var g = app.MapGroup("/v1/tasks");

        // Create a task and write its plan. JSON {mode, goal, details, fixtureId?, assetId?} or multipart with up to 3 manual photos.
        g.MapPost("", async (HttpRequest http, TaskStore store, ExpectedTwinStore twins, EvidenceFileStore files, TaskPlanner planner, HardeningOptions limits, TimeProvider clock, CancellationToken ct) =>
        {
            string? mode, goal, details, fixtureId, assetId;
            var manuals = new List<(byte[] Bytes, string MediaType, int Width, int Height)>();
            if (http.HasFormContentType)
            {
                var form = await http.ReadFormAsync(ct);
                (mode, goal, details, fixtureId, assetId) = (form["mode"], form["goal"], form["details"], form["fixtureId"], form["assetId"]);
                var photos = form.Files.GetFiles("manual");
                if (photos.Count > 3) return ApiResults.BadRequest("too_many_photos", "Attach at most 3 manual photos");
                foreach (var f in photos)
                {
                    var image = await ReadImage(f, limits, ct);
                    if (image.Error is { } bad) return bad;
                    manuals.Add(image.Value!.Value);
                }
            }
            else
            {
                var body = await http.ReadFromJsonAsync<CreateBody>(ct);
                if (body is null) return ApiResults.BadRequest("A JSON body or multipart form is required");
                (mode, goal, details, fixtureId, assetId) = (body.Mode, body.Goal, body.Details, body.FixtureId, body.AssetId);
            }
            if (!Enum.TryParse<TaskMode>(mode, ignoreCase: true, out var taskMode)) return ApiResults.BadRequest("invalid_mode", "mode must be Diy or Technician");
            SiteLink? site = null;
            PlanSiteContext? siteContext = null;
            if (!string.IsNullOrWhiteSpace(fixtureId) || !string.IsNullOrWhiteSpace(assetId))
            {
                var twin = twins.Find(fixtureId ?? "");
                var asset = twin?.Twin.FindAsset(assetId ?? "");
                if (twin is null || asset is null) return ApiResults.NotFound($"Asset '{assetId}' is not on site record '{fixtureId}'");
                site = new SiteLink(twin.Twin.FixtureId, asset.AssetId);
                siteContext = new PlanSiteContext(asset.AssetId, asset.DisplayName, asset.Kind,
                    twin.Twin.Connections.Where(c => c.FromAssetId == asset.AssetId || c.ToAssetId == asset.AssetId)
                        .Select(c => $"{c.FromAssetId} port {c.FromPortLabel} to {c.ToAssetId}, cable {c.CableLabel}, {c.ExpectedState}").ToList());
            }
            return await GuardAsync(async () =>
            {
                var task = store.Create(taskMode, goal ?? "", details, site, clock.GetUtcNow());
                var id = task.State.TaskId;
                var manualIds = new List<string>();
                foreach (var m in manuals)
                {
                    var evidenceId = "manual-" + Guid.NewGuid().ToString("N")[..10];
                    var stored = files.SaveImage(id, evidenceId, m.Bytes, m.MediaType, m.Width, m.Height);
                    store.Execute(id, s => s.RecordPhoto("manual:" + stored.Sha256, evidenceId, stored.Sha256, m.Width, m.Height, TaskPhotoPurpose.Manual, null, clock.GetUtcNow()));
                    manualIds.Add(evidenceId);
                }
                var planError = await Plan(store, planner, id, siteContext, [.. manuals.Select(m => (m.Bytes, m.MediaType))], manualIds, clock, ct);
                return Results.Created($"/v1/tasks/{id}", new { task = View(store.Find(id)!), planError });
            });
        }).DisableAntiforgery().AsModelCall();

        g.MapGet("", (TaskStore store, string? mode) => Results.Ok(store.All
            .Where(t => mode is null || string.Equals(t.State.Mode.ToString(), mode, StringComparison.OrdinalIgnoreCase))
            .Select(t => Summary(t.State)).OrderByDescending(s => s.UpdatedAt)));

        g.MapGet("/{id}", (string id, TaskStore store) => store.Find(id) is { } t ? Results.Ok(View(t)) : ApiResults.NotFound($"Task '{id}' not found"));

        g.MapGet("/{id}/events", (string id, TaskStore store) => store.Find(id) is { } t ? Results.Ok(t.Events) : ApiResults.NotFound($"Task '{id}' not found"));

        // Retry the plan when the first attempt failed or was rejected; a task keeps at most one plan.
        g.MapPost("/{id}/plan", async (string id, TaskStore store, ExpectedTwinStore twins, EvidenceFileStore files, TaskPlanner planner, TimeProvider clock, CancellationToken ct) =>
        {
            if (store.Find(id) is not { } task) return ApiResults.NotFound($"Task '{id}' not found");
            if (task.State.Plan is not null) return ApiResults.Conflict(TaskErrorCodes.InvalidState, "This task already has a plan");
            var manuals = task.State.Photos.Where(p => p.Purpose == TaskPhotoPurpose.Manual).ToList();
            var images = manuals.Select(p => ArtifactPath(files, id, p.EvidenceId)).Where(p => p is not null)
                .Select(p => { var b = File.ReadAllBytes(p!); return (b, EvidenceFileStore.DetectImageType(b)!); }).ToList();
            PlanSiteContext? site = task.State.Site is { } s && twins.Find(s.FixtureId)?.Twin.FindAsset(s.AssetId) is { } a ? new PlanSiteContext(a.AssetId, a.DisplayName, a.Kind, []) : null;
            return await GuardAsync(async () =>
            {
                var planError = await Plan(store, planner, id, site, images, [.. manuals.Select(m => m.EvidenceId)], clock, ct);
                return planError is null ? Results.Ok(View(store.Find(id)!)) : ApiResults.Conflict(planError.Code, planError.Message);
            });
        }).AsModelCall();

        g.MapPost("/{id}/accept", (string id, AcceptRequest req, HttpRequest http, TaskStore store, TimeProvider clock) =>
            Guard(() => { store.Execute(id, s => s.AcceptPlan(RequestId(http, "accept"), req.SafetyAcknowledged, clock.GetUtcNow())); return Results.Ok(View(store.Find(id)!)); }));

        g.MapPost("/{id}/steps/{stepId}:start", (string id, string stepId, HttpRequest http, TaskStore store, TimeProvider clock) =>
            Guard(() => { store.Execute(id, s => s.StartStep(RequestId(http, "start"), stepId, clock.GetUtcNow())); return Results.Ok(View(store.Find(id)!)); }));

        g.MapPost("/{id}/steps/{stepId}:complete", (string id, string stepId, HttpRequest http, TaskStore store, TimeProvider clock) =>
            Guard(() => { store.Execute(id, s => s.CompleteStep(RequestId(http, "complete"), stepId, clock.GetUtcNow())); return Results.Ok(View(store.Find(id)!)); }));

        g.MapPost("/{id}/steps/{stepId}:skip", (string id, string stepId, HttpRequest http, TaskStore store, TimeProvider clock) =>
            Guard(() => { store.Execute(id, s => s.SkipStep(RequestId(http, "skip"), stepId, clock.GetUtcNow())); return Results.Ok(View(store.Find(id)!)); }));

        g.MapPost("/{id}/abandon", (string id, AbandonRequest req, HttpRequest http, TaskStore store, TimeProvider clock) =>
            Guard(() => { store.Execute(id, s => s.Abandon(RequestId(http, "abandon"), req.Reason, clock.GetUtcNow())); return Results.Ok(View(store.Find(id)!)); }));

        // A step photo, saved as the task's record, then a suggested verdict. The user still decides.
        g.MapPost("/{id}/steps/{stepId}/check", async (string id, string stepId, HttpRequest http, TaskStore store, EvidenceFileStore files, StepCoach coach, HardeningOptions limits, TimeProvider clock, CancellationToken ct) =>
        {
            if (store.Find(id) is not { } task) return ApiResults.NotFound($"Task '{id}' not found");
            if (task.State.Plan?.Steps.FirstOrDefault(s => s.Id == stepId) is not { } step) return ApiResults.NotFound($"Step '{stepId}' not found");
            if (!http.HasFormContentType) return ApiResults.BadRequest("multipart/form-data with an 'image' file is required");
            var image = await ReadImage((await http.ReadFormAsync(ct)).Files.GetFile("image"), limits, ct);
            if (image.Error is { } bad) return bad;
            var (bytes, mediaType, width, height) = image.Value!.Value;
            var evidenceId = "step-" + Guid.NewGuid().ToString("N")[..10];
            var stored = files.SaveImage(id, evidenceId, bytes, mediaType, width, height);
            var requestId = RequestId(http, "check:" + stored.Sha256);
            return await GuardAsync(async () =>
            {
                store.Execute(id, s => s.RecordPhoto(requestId + ":photo", evidenceId, stored.Sha256, width, height, TaskPhotoPurpose.StepCheck, stepId, clock.GetUtcNow()));
                var result = await coach.CheckAsync(store.Find(id)!.State, step, bytes, mediaType, ct);
                if (result.Value is not { } check || check.Reason is not { Length: > 0 and <= 400 } || check.Reason.Contains("://"))
                    return ModelFailure(result.Error, "The step check is unavailable; the photo is saved, try again or decide yourself");
                store.Execute(id, s => s.RecordCheck(requestId, stepId, evidenceId, check.Verdict, check.Reason, result.ModelId, clock.GetUtcNow()));
                return Results.Ok(new { check = new { stepId, photoId = evidenceId, verdict = check.Verdict, reason = check.Reason, result.ModelId, source = "AI inference" }, task = View(store.Find(id)!) });
            });
        }).DisableAntiforgery().AsModelCall();

        // A question by voice or text, optionally with the current camera frame (kept as the task's record).
        g.MapPost("/{id}/ask", async (string id, HttpRequest http, TaskStore store, EvidenceFileStore files, StepCoach coach, LiveVisionGate gate, HardeningOptions limits, TimeProvider clock, CancellationToken ct) =>
        {
            if (store.Find(id) is not { } task) return ApiResults.NotFound($"Task '{id}' not found");
            if (!http.HasFormContentType) return ApiResults.BadRequest("multipart/form-data with a 'question' is required");
            var form = await http.ReadFormAsync(ct);
            var question = form["question"].ToString().Trim();
            if (question.Length is < 1 or > 500) return ApiResults.BadRequest("invalid_question", "Ask in 1 to 500 characters");
            (byte[] Bytes, string MediaType, int Width, int Height)? frame = null;
            if (form.Files.GetFile("image") is { } file)
            {
                var image = await ReadImage(file, limits, ct, minShortSide: 120);
                if (image.Error is { } bad) return bad;
                frame = image.Value;
            }
            var requestId = RequestId(http, "ask");
            return await GuardAsync(async () =>
            {
                string? photoId = null;
                if (frame is { } f)
                {
                    photoId = "ask-" + Guid.NewGuid().ToString("N")[..10];
                    var stored = files.SaveImage(id, photoId, f.Bytes, f.MediaType, f.Width, f.Height);
                    var pid = photoId;
                    store.Execute(id, s => s.RecordPhoto(requestId + ":photo", pid, stored.Sha256, f.Width, f.Height, TaskPhotoPurpose.Question, s.State.CurrentStepId, clock.GetUtcNow()));
                }
                var state = store.Find(id)!.State;
                var result = await coach.AskAsync(state, question, frame is { } fr ? (fr.Bytes, fr.MediaType) : null, ct);
                if (result.Value is not { } a || a.Answer is not { Length: > 0 and <= 1200 } || a.Answer.Contains("://") || a.Focus is not { Count: <= 2 } || !a.Focus.All(TaskPlanValidator.IsConcept))
                    return ModelFailure(result.Error, "LensQi couldn't answer that just now; try again");
                gate.SetFocus(id, a.Focus);
                store.Execute(id, s => s.RecordAnswer(requestId, state.CurrentStepId, question, a.Answer, photoId, result.ModelId, clock.GetUtcNow()));
                return Results.Ok(new { answer = a.Answer, focus = a.Focus, stepId = state.CurrentStepId, photoId, result.ModelId, source = "AI inference" });
            });
        }).DisableAntiforgery().AsModelCall();

        // A technician task on a site record hands over to FieldProof: a linked inspection on the same asset.
        g.MapPost("/{id}/link-inspection", (string id, HttpRequest http, TaskStore store, ExpectedTwinStore twins, InspectionStore inspections, TimeProvider clock) =>
        {
            if (store.Find(id) is not { } task) return ApiResults.NotFound($"Task '{id}' not found");
            if (task.State.InspectionId is { } existing) return Results.Ok(new { inspectionId = existing, task = View(task) });
            if (task.State.Site is not { } site || twins.Find(site.FixtureId) is not { } twin)
                return ApiResults.Conflict(TaskErrorCodes.InvalidState, "Only a technician task on an installed site record can be verified with FieldProof");
            return Guard(() =>
            {
                var inspection = inspections.Create(twin, site.AssetId, clock.GetUtcNow());
                store.Execute(id, s => s.LinkInspection(RequestId(http, "link"), inspection.State.InspectionId, clock.GetUtcNow()));
                return Results.Ok(new { inspectionId = inspection.State.InspectionId, task = View(store.Find(id)!) });
            });
        });

        g.MapGet("/{id}/artifacts/{name}", (string id, string name, TaskStore store, EvidenceFileStore files) =>
        {
            if (store.Find(id) is null) return ApiResults.NotFound($"Task '{id}' not found");
            if (name.Contains("..") || name.Contains('/') || name.Contains('\\')) return ApiResults.BadRequest("invalid artifact name");
            // A stored file name, or a photo id (files are saved as "{photoId}-{sha12}.{ext}").
            var path = Path.Combine(files.DataDirectoryPath, "evidence", id, name);
            if (!File.Exists(path)) path = ArtifactPath(files, id, name) ?? path;
            if (!File.Exists(path)) return ApiResults.NotFound("artifact not found");
            var bytes = File.ReadAllBytes(path);
            return Results.File(bytes, EvidenceFileStore.DetectImageType(bytes) ?? "application/octet-stream");
        });
        return app;
    }

    private sealed record CreateBody(string? Mode, string? Goal, string? Details, string? FixtureId, string? AssetId);
    private sealed record PlanError(string Code, string Message);

    /// <summary>
    /// Asks the planner and records the plan if the validator accepts it. A rejected plan is asked for once more with the
    /// rejection reason; the second answer faces the same validator. Returns why there is no plan, otherwise null.
    /// </summary>
    private static async Task<PlanError?> Plan(TaskStore store, TaskPlanner planner, string id, PlanSiteContext? site,
        IReadOnlyList<(byte[] Bytes, string MediaType)> manuals, IReadOnlyList<string> manualIds, TimeProvider clock, CancellationToken ct)
    {
        PlanError? error = null;
        for (var attempt = 0; attempt < 2; attempt++)
        {
            var state = store.Find(id)!.State;
            var result = await planner.PlanAsync(state.Mode, state.Goal, state.Details, site, manuals, ct, error?.Message);
            if (result.Value is not { } plan)
                return result.Error?.StartsWith("provider_unavailable") == true
                    ? new PlanError("provider_unavailable", "The planner is unavailable; try again shortly")
                    : new PlanError("invalid_model_output", "The planner's answer was not a usable plan; try again");
            try
            {
                store.Execute(id, s => s.ProposePlan("plan:" + result.ResponseDigest, plan, result.ModelId, result.PromptDigest, result.ResponseDigest, manualIds, clock.GetUtcNow()));
                return null;
            }
            catch (TaskConflictException ex) when (ex.Code is TaskErrorCodes.InvalidPlan or TaskErrorCodes.UnsafePlan) { error = new PlanError(ex.Code, ex.Message); }
        }
        return error;
    }

    private static string? ArtifactPath(EvidenceFileStore files, string id, string evidenceId)
    {
        var dir = Path.Combine(files.DataDirectoryPath, "evidence", id);
        return Directory.Exists(dir) ? Directory.EnumerateFiles(dir, evidenceId + "-*").FirstOrDefault() : null;
    }

    private static IResult ModelFailure(string? error, string message) =>
        ApiResults.Conflict(error?.StartsWith("provider_unavailable") == true ? "provider_unavailable" : "invalid_model_output", message);

    private sealed record ImageRead((byte[] Bytes, string MediaType, int Width, int Height)? Value, IResult? Error);

    private static async Task<ImageRead> ReadImage(IFormFile? file, HardeningOptions limits, CancellationToken ct, int? minShortSide = null)
    {
        if (file is null || file.Length == 0) return new(null, ApiResults.BadRequest("'image' file is required"));
        if (file.Length > EvidenceFileStore.MaxImageBytes) return new(null, ApiResults.BadRequest($"image exceeds {EvidenceFileStore.MaxImageBytes} bytes"));
        using var ms = new MemoryStream();
        await file.CopyToAsync(ms, ct);
        var bytes = ms.ToArray();
        var mediaType = EvidenceFileStore.DetectImageType(bytes);
        if (mediaType is null || !EvidenceFileStore.TryReadDimensions(bytes, out var width, out var height))
            return new(null, ApiResults.BadRequest("image must be a readable JPEG or PNG"));
        if (Math.Min(width, height) < (minShortSide ?? limits.MinImageShortSide) || (long)width * height > limits.MaxImagePixels)
            return new(null, ApiResults.BadRequest("image_size", $"image is {width}x{height}, outside the accepted size"));
        return new((bytes, mediaType, width, height), null);
    }

    public sealed record TaskSummary(string TaskId, TaskMode Mode, string Goal, string? Title, TaskPhase Phase, int Steps, int Finished, string? CurrentStepTitle, SiteLink? Site, string? InspectionId, DateTimeOffset UpdatedAt);

    private static TaskSummary Summary(TaskState s) => new(s.TaskId, s.Mode, s.Goal, s.Plan?.Title, s.Phase, s.Plan?.Steps.Count ?? 0,
        s.CompletedStepIds.Count + s.SkippedStepIds.Count, s.CurrentStep?.Title, s.Site, s.InspectionId, s.UpdatedAt);

    /// <summary>The task with each step's status and its latest photo check, for the clients.</summary>
    private static object View(TaskSession session)
    {
        var s = session.State;
        var plan = s.Plan;
        return new
        {
            s.TaskId, s.Mode, s.Goal, s.Details, s.Site, s.Phase, s.CurrentStepId, s.InspectionId, s.CreatedAt, s.UpdatedAt, s.SafetyAcknowledged, s.AbandonReason,
            plan = plan is null ? null : new
            {
                plan.Title, plan.Summary, plan.Risk, plan.EstimatedMinutes, plan.Restricted, plan.ProfessionalRequired, plan.ProfessionalReason,
                plan.Tools, plan.Parts, plan.SourceNote, modelId = s.PlanModelId, source = "AI inference",
                needsSafetyAck = plan.Risk != RiskLevel.Low || plan.ProfessionalRequired || plan.Steps.Any(x => x.Cautions.Count > 0),
                steps = plan.Steps.Select(step => new
                {
                    step.Id, step.Title, step.Instruction, step.HandsOn, step.LookFor, step.Cautions, step.StopIf, step.Check,
                    status = s.CompletedStepIds.Contains(step.Id) ? "Done" : s.SkippedStepIds.Contains(step.Id) ? "Skipped" : s.CurrentStepId == step.Id ? "Active" : "Todo",
                    lastCheck = s.Checks.LastOrDefault(c => c.StepId == step.Id),
                }),
            },
            photos = s.Photos,
            answers = s.Answers.TakeLast(20),
        };
    }

    private static string RequestId(HttpRequest http, string purpose) =>
        http.Headers.TryGetValue("Idempotency-Key", out var v) && !string.IsNullOrWhiteSpace(v) ? v.ToString() : purpose + ":" + Guid.NewGuid().ToString("N");

    private static IResult Guard(Func<IResult> action)
    {
        try { return action(); }
        catch (TaskConflictException ex) { return ApiResults.Conflict(ex.Code, ex.Message); }
        catch (InspectionConflictException ex) { return ApiResults.Conflict(ex.Code, ex.Message); }
        catch (KeyNotFoundException ex) { return ApiResults.NotFound($"Task '{ex.Message}' not found"); }
        catch (Exception ex) { return Results.Json(new ApiError("internal", ex.GetType().Name + ": " + ex.Message), statusCode: 500); }
    }

    private static async Task<IResult> GuardAsync(Func<Task<IResult>> action)
    {
        try { return await action(); }
        catch (OperationCanceledException) { throw; }
        catch (TaskConflictException ex) { return ApiResults.Conflict(ex.Code, ex.Message); }
        catch (KeyNotFoundException ex) { return ApiResults.NotFound($"Task '{ex.Message}' not found"); }
        catch (Exception ex) { return Results.Json(new ApiError("internal", ex.GetType().Name + ": " + ex.Message), statusCode: 500); }
    }
}
