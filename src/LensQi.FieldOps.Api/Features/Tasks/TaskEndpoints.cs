using LensQi.FieldOps.Api.Features.LiveVision;
using LensQi.FieldOps.Api.Infrastructure.Evidence;
using LensQi.FieldOps.Api.Infrastructure.Fixtures;
using LensQi.FieldOps.Api.Infrastructure.Hardening;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Infrastructure.Tasks;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Tasks;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Meta.Segmentation;
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
        g.MapPost("", async (HttpRequest http, TaskStore store, ExpectedTwinStore twins, IReadOnlyList<FixtureKnowledgeBinding> bindings, IVendorKnowledgeResolver resolver, EvidenceFileStore files, IImageOrienter orienter, TaskPlanner planner, KeyedGate gate, HardeningOptions limits, TimeProvider clock, CancellationToken ct) =>
        {
            string? mode, goal, details, fixtureId, assetId, height;
            IReadOnlyList<string> tools;
            TaskHandoff? handoff = null;
            var manuals = new List<(byte[] Bytes, string MediaType, int Width, int Height, EvidenceOrigin Origin)>();
            if (http.HasFormContentType)
            {
                var form = await http.ReadFormAsync(ct);
                (mode, goal, details, fixtureId, assetId) = (form["mode"], form["goal"], form["details"], form["fixtureId"], form["assetId"]);
                // The person, when they said: height in cm and the tools they have, so the plan fits them.
                (height, tools) = (form["heightCm"], [.. form["tools"].Where(t => !string.IsNullOrWhiteSpace(t)).Select(t => t!.Trim())]);
                if (form["sourceTaskId"].ToString() is { Length: > 0 } sourceId)
                {
                    var previous = store.Find(sourceId)?.State;
                    if (previous is null || previous.Mode != TaskMode.Freeform)
                        return ApiResults.BadRequest("invalid_source_task", "Select a Just ask session");
                    var selected = form["sourcePhotoId"].Select(x => x ?? "").Distinct().ToList();
                    if (selected.Count + form.Files.Count > 3)
                        return ApiResults.BadRequest("too_many_photos", "Select up to three reference photos");
                    var answers = new List<AnswerRecord>();
                    foreach (var value in form["sourceAnswerAt"].Distinct())
                    {
                        if (!DateTimeOffset.TryParse(value, out var at) || previous.Answers.FirstOrDefault(a => a.At == at) is not { } answer || answers.Count >= 10)
                            return ApiResults.BadRequest("invalid_source_answer", "Select up to ten answers from this session");
                        answers.Add(answer);
                    }
                    foreach (var photoId in selected)
                    {
                        if (previous.Photos.FirstOrDefault(p => p.EvidenceId == photoId) is not { } photo ||
                            ArtifactPath(files, sourceId, photoId) is not { } path)
                            return ApiResults.BadRequest("invalid_source_photo", "The selected photo is unavailable");
                        var image = await File.ReadAllBytesAsync(path, ct);
                        if (!string.Equals(Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(image)), photo.Sha256, StringComparison.Ordinal))
                            return ApiResults.Conflict("source_digest_mismatch", "The reference photo has changed");
                        manuals.Add((image, EvidenceFileStore.DetectImageType(image)!, photo.Width, photo.Height, photo.Origin));
                    }
                    handoff = new TaskHandoff(sourceId, previous.Details, answers, selected, previous.Site);
                }
                var photos = form.Files.GetFiles("manual");
                if (photos.Count > 3) return ApiResults.BadRequest("too_many_photos", "Attach at most 3 manual photos");
                foreach (var f in photos)
                {
                    var image = await ReadImage(f, limits, orienter, ct);
                    if (image.Error is { } bad) return bad;
                    // The same page attached twice is one manual photo.
                    if (!manuals.Any(m => m.Bytes.AsSpan().SequenceEqual(image.Value!.Value.Bytes))) { var m = image.Value!.Value; manuals.Add((m.Bytes, m.MediaType, m.Width, m.Height, EvidenceOrigin.Phone)); }
                }
            }
            else
            {
                CreateBody? body;
                try { body = http.HasJsonContentType() ? await http.ReadFromJsonAsync<CreateBody>(ct) : null; }
                catch (Exception ex) when (ex is System.Text.Json.JsonException or BadHttpRequestException) { body = null; }
                if (body is null) return ApiResults.BadRequest("A JSON body or multipart form is required");
                (mode, goal, details, fixtureId, assetId) = (body.Mode, body.Goal, body.Details, body.FixtureId, body.AssetId);
                (height, tools) = (body.HeightCm?.ToString(), body.Tools ?? []);
            }
            // GT-R01.5: a repeated creation key returns the task it made, with no new task, photos or plan call. The key's gate is
            // held until the plan is written, so a retry that overlaps the first request (a lost response during the planner
            // call) waits and replays the task with its plan, instead of a planless copy. The replay itself is checked below.
            var creationKey = http.Headers.TryGetValue("Idempotency-Key", out var k) && !string.IsNullOrWhiteSpace(k) ? "create:" + k : null;
            using var _ = await gate.EnterAsync("tasks:create", creationKey, ct);
            if (!Enum.TryParse<TaskMode>(mode, ignoreCase: true, out var taskMode)) return ApiResults.BadRequest("invalid_mode", "mode must be Diy, Technician or Freeform");
            int? heightCm = null;
            if (!string.IsNullOrWhiteSpace(height))
            {
                if (!int.TryParse(height, out var h)) return ApiResults.BadRequest("invalid_height", "heightCm must be a whole number of centimetres");
                heightCm = h;
            }
            var person = heightCm is null && tools.Count == 0 ? null : new PersonContext(heightCm, tools);
            SiteLink? site = null;
            PlanSiteContext? siteContext = null;
            if (!string.IsNullOrWhiteSpace(fixtureId) || !string.IsNullOrWhiteSpace(assetId))
            {
                var twin = twins.Find(fixtureId ?? "");
                var asset = twin?.Twin.FindAsset(assetId ?? "");
                if (twin is null || asset is null) return ApiResults.NotFound($"Asset '{assetId}' is not on site record '{fixtureId}'");
                site = new SiteLink(twin.Twin.FixtureId, asset.AssetId);
                siteContext = SiteContext(twins, site, bindings, resolver);
            }
            return await GuardAsync(async () =>
            {
                // A reused key answers only the same command: a different mode, goal or site is refused, never given another job's task.
                if (store.FindCreated(creationKey) is { } earlier) return Replayed(TaskStore.SameCreate(earlier, taskMode, goal ?? "", site));
                var (task, created) = store.Create(taskMode, goal ?? "", details, site, clock.GetUtcNow(), creationKey, person, handoff);
                var id = task.State.TaskId;
                if (!created) return Replayed(TaskStore.SameCreate(task, taskMode, goal ?? "", site));
                var manualIds = new List<string>();
                foreach (var m in manuals)
                {
                    var evidenceId = "manual-" + Guid.NewGuid().ToString("N")[..10];
                    var stored = files.SaveImage(id, evidenceId, m.Bytes, m.MediaType, m.Width, m.Height);
                    store.Execute(id, s => s.RecordPhoto("manual:" + stored.Sha256, evidenceId, stored.Sha256, m.Width, m.Height, TaskPhotoPurpose.Manual, null, clock.GetUtcNow(), m.Origin));
                    manualIds.Add(evidenceId);
                }
                // Free-form help starts at once with the camera; there is no plan to write.
                var planError = taskMode == TaskMode.Freeform ? null : await Plan(store, planner, id, siteContext, [.. manuals.Select(m => (m.Bytes, m.MediaType))], manualIds, clock, ct);
                return Results.Created($"/v1/tasks/{id}", new { task = View(store.Find(id)!), planError });
            });
            static IResult Replayed(TaskSession task) => Results.Ok(new { task = View(task), planError = (PlanError?)null, replayed = true });
        }).DisableAntiforgery().AsModelCall();

        g.MapGet("", (TaskStore store, string? mode) => Results.Ok(store.All
            .Where(t => mode is null || string.Equals(t.State.Mode.ToString(), mode, StringComparison.OrdinalIgnoreCase))
            .Select(t => Summary(t.State)).OrderByDescending(s => s.UpdatedAt)));

        g.MapGet("/{id}", (string id, TaskStore store) => store.Find(id) is { } t ? Results.Ok(View(t)) : ApiResults.NotFound($"Task '{id}' not found"));

        // A copy taken under the task's lock, so a concurrent command never changes the list while it is written out.
        g.MapGet("/{id}/events", (string id, TaskStore store) => store.Find(id) is null ? ApiResults.NotFound($"Task '{id}' not found") : Results.Ok(store.Execute(id, s => s.Events.ToList())));

        // Retry the plan when the first attempt failed or was rejected; a task keeps at most one plan.
        g.MapPost("/{id}/plan", async (string id, TaskStore store, ExpectedTwinStore twins, IReadOnlyList<FixtureKnowledgeBinding> bindings, IVendorKnowledgeResolver resolver, EvidenceFileStore files, TaskPlanner planner, TimeProvider clock, CancellationToken ct) =>
        {
            if (store.Find(id) is not { } task) return ApiResults.NotFound($"Task '{id}' not found");
            // State first, so a request that cannot be recorded never spends a model call.
            if (task.State.Mode == TaskMode.Freeform) return ApiResults.Conflict(TaskErrorCodes.InvalidState, "Free-form help has no plan; start a planned task instead");
            if (task.State.Plan is not null || task.State.Phase != TaskPhase.Planned) return ApiResults.Conflict(TaskErrorCodes.InvalidState, "This task already has a plan or is closed");
            var manuals = task.State.Photos.Where(p => p.Purpose == TaskPhotoPurpose.Manual).ToList();
            if (manuals.Any(p => ArtifactPath(files, id, p.EvidenceId) is null)) return ApiResults.Conflict("missing_reference", "A reference photo is missing");
            var images = manuals.Select(p => ArtifactPath(files, id, p.EvidenceId)).Where(p => p is not null)
                .Select(p => { var b = File.ReadAllBytes(p!); return (b, EvidenceFileStore.DetectImageType(b)!); }).ToList();
            var site = task.State.Site is { } link ? SiteContext(twins, link, bindings, resolver) : null;
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

        // An optional JSON body says why: {reason, partHeightCm?, reachCm?}, the numbers when the phone measured the part.
        g.MapPost("/{id}/steps/{stepId}:skip", async (string id, string stepId, HttpRequest http, TaskStore store, TimeProvider clock) =>
        {
            SkipBody? body = null;
            if (http.HasJsonContentType())
            {
                try { body = await http.ReadFromJsonAsync<SkipBody>(); }
                catch (Exception ex) when (ex is System.Text.Json.JsonException or BadHttpRequestException) { return ApiResults.BadRequest("invalid_skip_reason", "The skip body is {reason, partHeightCm?, reachCm?}"); }
            }
            var reason = body?.Reason is { } text ? new SkipReason(text.Trim(), body.PartHeightCm, body.ReachCm) : null;
            return Guard(() => { store.Execute(id, s => s.SkipStep(RequestId(http, "skip"), stepId, clock.GetUtcNow(), reason)); return Results.Ok(View(store.Find(id)!)); });
        });

        g.MapPost("/{id}/finish", (string id, HttpRequest http, TaskStore store, TimeProvider clock) =>
            Guard(() => { store.Execute(id, s => s.Finish(RequestId(http, "finish"), clock.GetUtcNow())); return Results.Ok(View(store.Find(id)!)); }));

        g.MapPost("/{id}/abandon", (string id, AbandonRequest req, HttpRequest http, TaskStore store, TimeProvider clock) =>
            Guard(() => { store.Execute(id, s => s.Abandon(RequestId(http, "abandon"), req.Reason, clock.GetUtcNow())); return Results.Ok(View(store.Find(id)!)); }));

        // A step photo, saved as the task's record, then a suggested verdict. The user still decides.
        g.MapPost("/{id}/steps/{stepId}/check", async (string id, string stepId, HttpRequest http, TaskStore store, EvidenceFileStore files, IImageOrienter orienter, StepCoach coach, HardeningOptions limits, TimeProvider clock, CancellationToken ct) =>
        {
            if (store.Find(id) is not { } task) return ApiResults.NotFound($"Task '{id}' not found");
            if (task.State.Plan?.Steps.FirstOrDefault(s => s.Id == stepId) is not { } step) return ApiResults.NotFound($"Step '{stepId}' not found");
            if (task.State.Phase != TaskPhase.InProgress) return ApiResults.Conflict(TaskErrorCodes.InvalidState, "Start the plan before checking a step");
            if (!http.HasFormContentType) return ApiResults.BadRequest("multipart/form-data with an 'image' file is required");
            var form = await http.ReadFormAsync(ct);
            var image = await ReadImage(form.Files.GetFile("image"), limits, orienter, ct);
            if (image.Error is { } bad) return bad;
            var (bytes, mediaType, width, height) = image.Value!.Value;
            // The capture device travels with the photo, so a mock-glasses check is never shown as a phone or real-glasses photo.
            if (!Inspections.InspectionEndpoints.TryCaptureOrigin(form["source"].ToString(), out var origin)) return Inspections.InspectionEndpoints.InvalidSource();
            var requestId = RequestId(http, "check");
            return await GuardAsync(async () =>
            {
                // A retried key returns the recorded check; after a failed model call it re-checks the photo already saved. A key
                // that recorded another command is refused before any photo is saved or model call made.
                if (store.Execute(id, s => s.Replayed(requestId)) is { } done)
                    return done.OfType<StepChecked>().FirstOrDefault() is { } earlier ? Results.Ok(CheckResult(earlier, store.Find(id)!)) : DifferentCommand();
                var photo = store.Execute(id, s => s.Replayed(requestId + ":photo"))?.OfType<TaskPhotoRecorded>().FirstOrDefault();
                if (photo is not null && ArtifactPath(files, id, photo.EvidenceId) is { } savedPath)
                {
                    bytes = File.ReadAllBytes(savedPath);
                    mediaType = EvidenceFileStore.DetectImageType(bytes)!;
                }
                else
                {
                    var evidenceId = "step-" + Guid.NewGuid().ToString("N")[..10];
                    var stored = files.SaveImage(id, evidenceId, bytes, mediaType, width, height);
                    photo = store.Execute(id, s => s.RecordPhoto(requestId + ":photo", evidenceId, stored.Sha256, width, height, TaskPhotoPurpose.StepCheck, stepId, clock.GetUtcNow(), origin))
                        .OfType<TaskPhotoRecorded>().Single();
                }
                var result = await coach.CheckAsync(store.Find(id)!.State, step, bytes, mediaType, ct);
                if (result.Value is not { } check || check.Reason is not { Length: > 0 and <= 400 } || TaskPlanValidator.HasLink(check.Reason))
                    return ModelFailure(result.Error, "The step check is unavailable; the photo is saved, try again or decide yourself");
                var reason = TaskSafety.Guard(store.Find(id)!.State, check.Reason);
                if (reason.Length > 400) reason = TaskSafety.Referral;
                // The session replays whatever the key recorded first; only a check is answered as one.
                return store.Execute(id, s => s.RecordCheck(requestId, stepId, photo.EvidenceId, check.Verdict, reason, result.ModelId, clock.GetUtcNow()))
                    .OfType<StepChecked>().FirstOrDefault() is { } recorded ? Results.Ok(CheckResult(recorded, store.Find(id)!)) : DifferentCommand();
            });
        }).DisableAntiforgery().AsModelCall();

        // A question by voice or text, optionally with the current camera frame (kept as the task's record).
        g.MapPost("/{id}/ask", async (string id, HttpRequest http, TaskStore store, EvidenceFileStore files, IImageOrienter orienter, StepCoach coach, LiveVisionGate gate, HardeningOptions limits, TimeProvider clock, CancellationToken ct) =>
        {
            if (store.Find(id) is not { } task) return ApiResults.NotFound($"Task '{id}' not found");
            if (!http.HasFormContentType) return ApiResults.BadRequest("multipart/form-data with a 'question' is required");
            var form = await http.ReadFormAsync(ct);
            var question = form["question"].ToString().Trim();
            if (question.Length is < 1 or > 500) return ApiResults.BadRequest("invalid_question", "Ask in 1 to 500 characters");
            if (MeasuredParts.Parse(form["measured"]) is not { } measured) return MeasuredParts.Invalid();
            // The frame's capture device is recorded as for a step check: no source is the phone, an unknown one is refused.
            if (!Inspections.InspectionEndpoints.TryCaptureOrigin(form["source"].ToString(), out var origin)) return Inspections.InspectionEndpoints.InvalidSource();
            (byte[] Bytes, string MediaType, int Width, int Height)? frame = null;
            if (form.Files.GetFile("image") is { } file)
            {
                var image = await ReadImage(file, limits, orienter, ct, minShortSide: 120);
                if (image.Error is { } bad) return bad;
                frame = image.Value;
            }
            var requestId = RequestId(http, "ask");
            // A retried request returns the recorded answer: no new frame, no new model call.
            if (store.Execute(id, s => s.Replayed(requestId))?.OfType<QuestionAnswered>().FirstOrDefault() is { } earlier)
                return Results.Ok(new { answer = earlier.Answer, focus = Array.Empty<string>(), stepId = earlier.StepId, photoId = earlier.PhotoId, earlier.ModelId, source = "AI inference" });
            if (task.State.Phase != TaskPhase.InProgress) return ApiResults.Conflict(TaskErrorCodes.InvalidState, "Questions need a started task, or free-form help");
            return await GuardAsync(async () =>
            {
                string? photoId = null;
                // A retry after a failed model call keeps the photo the first attempt recorded under this request, and
                // asks about that photo rather than a newer frame.
                if (store.Execute(id, s => s.Replayed(requestId + ":photo"))?.OfType<TaskPhotoRecorded>().FirstOrDefault() is { } earlierPhoto)
                {
                    photoId = earlierPhoto.EvidenceId;
                    if (ArtifactPath(files, id, photoId) is { } savedPath)
                    {
                        var saved = File.ReadAllBytes(savedPath);
                        frame = (saved, EvidenceFileStore.DetectImageType(saved)!, earlierPhoto.Width, earlierPhoto.Height);
                    }
                }
                else if (frame is { } f)
                {
                    photoId = "ask-" + Guid.NewGuid().ToString("N")[..10];
                    var stored = files.SaveImage(id, photoId, f.Bytes, f.MediaType, f.Width, f.Height);
                    var pid = photoId;
                    store.Execute(id, s => s.RecordPhoto(requestId + ":photo", pid, stored.Sha256, f.Width, f.Height, TaskPhotoPurpose.Question, s.State.CurrentStepId, clock.GetUtcNow(), origin));
                }
                var state = store.Find(id)!.State;
                var result = await coach.AskAsync(state, question, frame is { } fr ? (fr.Bytes, fr.MediaType) : null, ct, measured);
                if (result.Value is not { } a || a.Answer is not { Length: > 0 and <= 1200 } || TaskPlanValidator.HasLink(a.Answer) || a.Focus is not { Count: <= 2 } || !a.Focus.All(TaskPlanValidator.IsConcept))
                    return ModelFailure(result.Error, "LensQi couldn't answer that just now; try again");
                gate.SetFocus(id, a.Focus);
                // Professionals-only work (the plan's, the goal's or the question's) gets the referral, and any hands-on
                // instruction in the answer is replaced by it, whatever the model wrote.
                var answer = TaskSafety.Guard(state, a.Answer, question);
                store.Execute(id, s => s.RecordAnswer(requestId, state.CurrentStepId, question, answer, photoId, result.ModelId, clock.GetUtcNow(),
                    answer == a.Answer && a.SessionTitle is { } title && TaskSafety.Guard(state, title, question) == title ? title : null));
                return Results.Ok(new { answer, focus = a.Focus, stepId = state.CurrentStepId, photoId, result.ModelId, source = "AI inference" });
            });
        }).DisableAntiforgery().AsModelCall();

        // A technician task on a site record hands over to FieldProof: a linked inspection on the same asset.
        g.MapPost("/{id}/link-inspection", (string id, HttpRequest http, TaskStore store, ExpectedTwinStore twins, InspectionStore inspections, TimeProvider clock) =>
        {
            if (store.Find(id) is not { } task) return ApiResults.NotFound($"Task '{id}' not found");
            if (task.State.Phase == TaskPhase.Abandoned) return ApiResults.Conflict(TaskErrorCodes.InvalidState, "A stopped task cannot be verified");
            if (task.State.Site is not { } site || twins.Find(site.FixtureId) is not { } twin)
                return ApiResults.Conflict(TaskErrorCodes.InvalidState, "Only a technician task on an installed site record can be verified with FieldProof");
            var requestId = RequestId(http, "link");
            return Guard(() =>
            {
                // Check and create under the task's lock, so concurrent calls link one inspection and leave no orphan. A key that
                // recorded another command is refused before an inspection is created, so none is left unlinked.
                var inspectionId = store.Execute(id, s =>
                {
                    if (s.Replayed(requestId) is { } done)
                        return done.OfType<InspectionLinked>().FirstOrDefault()?.InspectionId ?? throw new TaskConflictException(TaskErrorCodes.InvalidState, DifferentCommandMessage);
                    if (s.State.InspectionId is { } existing) return existing;
                    var inspection = inspections.Create(twin, site.AssetId, clock.GetUtcNow());
                    s.LinkInspection(requestId, inspection.State.InspectionId, clock.GetUtcNow());
                    return inspection.State.InspectionId;
                });
                return Results.Ok(new { inspectionId, task = View(store.Find(id)!) });
            });
        });

        g.MapGet("/{id}/package.zip", (string id, TaskStore store, InspectionStore inspections, EvidenceFileStore files, Features.Receipts.ReceiptService receipts) =>
        {
            if (store.Find(id) is not { } session) return ApiResults.NotFound("Task not found");
            var state = session.State;
            var inspection = state.InspectionId is { } linked ? inspections.Find(linked)?.State : null;
            return TaskPackage.Export(state, inspection, files.DataDirectoryPath, receipts);
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

    public const string Referral = TaskSafety.Referral;

    private static PlanSiteContext? SiteContext(ExpectedTwinStore twins, SiteLink link, IReadOnlyList<FixtureKnowledgeBinding> bindings, IVendorKnowledgeResolver resolver)
    {
        var twin = twins.Find(link.FixtureId);
        if (twin?.Twin.FindAsset(link.AssetId) is not { } asset) return null;
        var binding = bindings.FirstOrDefault(b => b.FixtureId == link.FixtureId && b.AssetId == link.AssetId);
        var facts = binding is null ? [] : resolver.Resolve(binding, binding.ToAssetIdentity(), []).Facts;
        return new PlanSiteContext(asset.AssetId, asset.DisplayName, asset.Kind,
            twin.Twin.Connections.Where(c => c.FromAssetId == asset.AssetId || c.ToAssetId == asset.AssetId)
                .Select(c => $"{c.FromAssetId} port {c.FromPortLabel} to {c.ToAssetId}, cable {c.CableLabel}, {c.ExpectedState}").ToList(), facts);
    }

    private sealed record CreateBody(string? Mode, string? Goal, string? Details, string? FixtureId, string? AssetId, int? HeightCm = null, IReadOnlyList<string>? Tools = null);
    private sealed record SkipBody(string? Reason, int? PartHeightCm, int? ReachCm);
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
            var result = await planner.PlanAsync(state.Mode, state.Goal, state.Details, site, manuals, ct, error?.Message, state.Person, manualIds, state.Handoff);
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
            catch (TaskConflictException ex) { return new PlanError(ex.Code, ex.Message); }
        }
        return error;
    }

    private static string? ArtifactPath(EvidenceFileStore files, string id, string evidenceId)
    {
        var dir = Path.Combine(files.DataDirectoryPath, "evidence", id);
        return Directory.Exists(dir) ? Directory.EnumerateFiles(dir, evidenceId + "-*").FirstOrDefault() : null;
    }

    /// <summary>A key answers the command it recorded, whatever that was; asking it for another is refused (the inspection endpoints' rule and code).</summary>
    private const string DifferentCommandMessage = "This Idempotency-Key was used for a different command";
    private static IResult DifferentCommand() => ApiResults.Conflict(TaskErrorCodes.InvalidState, DifferentCommandMessage);

    private static IResult ModelFailure(string? error, string message) =>
        ApiResults.Conflict(error?.StartsWith("provider_unavailable") == true ? "provider_unavailable" : "invalid_model_output", message);

    private sealed record ImageRead((byte[] Bytes, string MediaType, int Width, int Height)? Value, IResult? Error);

    /// <summary>
    /// Reads an uploaded photo and turns a JPEG the way its EXIF orientation says, as the inspection upload does: phones send
    /// the sensor's bytes with a tag, and the model, the stored file and every client must see the same upright pixels.
    /// The pixel limit is checked on the header before anything is decoded; the turned copy is used only when it is a JPEG
    /// within the upload and pixel limits, otherwise the bytes as uploaded are kept.
    /// </summary>
    private static async Task<ImageRead> ReadImage(IFormFile? file, HardeningOptions limits, IImageOrienter orienter, CancellationToken ct, int? minShortSide = null)
    {
        if (file is null || file.Length == 0) return new(null, ApiResults.BadRequest("'image' file is required"));
        if (file.Length > EvidenceFileStore.MaxImageBytes) return new(null, ApiResults.BadRequest($"image exceeds {EvidenceFileStore.MaxImageBytes} bytes"));
        using var ms = new MemoryStream();
        await file.CopyToAsync(ms, ct);
        var bytes = ms.ToArray();
        var mediaType = EvidenceFileStore.DetectImageType(bytes);
        if (mediaType is null || !EvidenceFileStore.TryReadDimensions(bytes, out var width, out var height))
            return new(null, ApiResults.BadRequest("image must be a readable JPEG or PNG"));
        if ((long)width * height > limits.MaxImagePixels)
            return new(null, ApiResults.BadRequest("image_size", $"image is {width}x{height}, outside the accepted size"));
        var orientation = EvidenceFileStore.ReadExifOrientation(bytes);
        if (orientation != 1 && await orienter.OrientAsync(bytes, orientation, limits.MaxImagePixels, ct) is { Length: > 0 } turned
            && turned.LongLength <= EvidenceFileStore.MaxImageBytes && EvidenceFileStore.DetectImageType(turned) == "image/jpeg"
            && EvidenceFileStore.TryReadDimensions(turned, out var turnedWidth, out var turnedHeight) && (long)turnedWidth * turnedHeight <= limits.MaxImagePixels)
        {
            bytes = turned; width = turnedWidth; height = turnedHeight;
        }
        if (Math.Min(width, height) < (minShortSide ?? limits.MinImageShortSide))
            return new(null, ApiResults.BadRequest("image_size", $"image is {width}x{height}, outside the accepted size"));
        return new((bytes, mediaType, width, height), null);
    }

    public sealed record TaskSummary(string TaskId, TaskMode Mode, string Goal, string? Title, TaskPhase Phase, int Steps, int Finished, string? CurrentStepTitle, SiteLink? Site, string? InspectionId, DateTimeOffset UpdatedAt, bool TitleByAi = false);

    private static TaskSummary Summary(TaskState s) => new(s.TaskId, s.Mode, s.Goal, s.DisplayTitle, s.Phase, s.Plan?.Steps.Count ?? 0,
        s.CompletedStepIds.Count + s.SkippedStepIds.Count, s.CurrentStep?.Title, s.Site, s.InspectionId, s.UpdatedAt, s.TitleByAi);

    private static object CheckResult(StepChecked c, TaskSession session) => new
    {
        check = new { c.StepId, c.PhotoId, c.Verdict, c.Reason, c.ModelId, source = "AI inference" },
        task = View(session),
    };

    /// <summary>The task with each step's status and its latest photo check, for the clients.</summary>
    private static object View(TaskSession session)
    {
        var s = session.State;
        var plan = s.Plan;
        return new
        {
            s.TaskId, s.Mode, s.Goal, title = s.DisplayTitle, titleByAi = s.TitleByAi, s.Details, s.Site, s.Phase, s.Handoff,
            person = s.Person is { } p ? new { p.HeightCm, p.StandingReachCm, p.Tools } : null, s.CurrentStepId, s.InspectionId, s.CreatedAt, s.UpdatedAt, s.SafetyAcknowledged, s.AbandonReason,
            plan = plan is null ? null : new
            {
                plan.Title, plan.Summary, plan.Risk, plan.EstimatedMinutes, plan.Restricted, plan.ProfessionalRequired, plan.ProfessionalReason,
                plan.Tools, plan.Parts, plan.SourceNote, modelId = s.PlanModelId, source = "AI inference",
                needsSafetyAck = plan.Risk != RiskLevel.Low || plan.ProfessionalRequired || plan.Steps.Any(x => x.Cautions.Count > 0),
                steps = plan.Steps.Select(step => new
                {
                    step.Id, step.Title, step.Instruction, step.HandsOn, step.LookFor, step.AccessFor, step.Cautions, step.StopIf, step.Check, sourceFacts = step.SourceFacts ?? [], referencePhotoIds = step.ReferencePhotoIds ?? [],
                    skipReason = s.SkipReasons.GetValueOrDefault(step.Id),
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
    }

    private static async Task<IResult> GuardAsync(Func<Task<IResult>> action)
    {
        try { return await action(); }
        catch (OperationCanceledException) { throw; }
        catch (TaskConflictException ex) { return ApiResults.Conflict(ex.Code, ex.Message); }
        catch (KeyNotFoundException ex) { return ApiResults.NotFound($"Task '{ex.Message}' not found"); }
    }
}
