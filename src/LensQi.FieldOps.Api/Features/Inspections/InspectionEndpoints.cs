using LensQi.FieldOps.Api.Infrastructure.Evidence;
using LensQi.FieldOps.Api.Infrastructure.Fixtures;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Diagnostics;
using LensQi.FieldOps.Meta.Vision;

namespace LensQi.FieldOps.Api.Features.Inspections;

/// <summary>PRD §9 candidate endpoints over the Core session. Every state change goes through InspectionSession;
/// endpoints only translate HTTP into commands and typed conflicts into stable error codes.</summary>
public static class InspectionEndpoints
{
    public sealed record CreateInspectionRequest(string FixtureId, string AssetId);
    public sealed record ChecklistRequest(string RequirementId, ChecklistStatus Status, string PhotoEvidenceId);
    public sealed record RepairClaimRequest(string Description);
    public sealed record WorkOrderRequest(string Summary);
    public sealed record SegmentationRequest(string PhotoEvidenceId, string Concept, bool Matched, string? MaskSha256, string? ModelId);
    public sealed record PredictionRequest(string DiagnosticId, string PredictedValue, IReadOnlyList<string> BasedOnFactIds);

    public static IEndpointRouteBuilder MapInspections(this IEndpointRouteBuilder app)
    {
        var g = app.MapGroup("/v1");

        g.MapGet("/fixtures", (ExpectedTwinStore twins) => Results.Ok(twins.Twins.Select(t => new
        {
            t.Twin.FixtureId, t.Twin.FixtureRevision, t.Sha256, t.Twin.FixtureDescription,
            assets = t.Twin.Assets.Select(a => new { a.AssetId, a.Kind, a.DisplayName }),
            diagnostics = t.Twin.Diagnostics.Select(d => new { d.DiagnosticId, d.Type, d.TargetAlias, d.ClaimVerified }),
            visualRequirements = t.Twin.VisualRequirements,
            t.Twin.VerificationPolicy,
        })));

        g.MapPost("/inspections", (CreateInspectionRequest req, ExpectedTwinStore twins, InspectionStore store, TimeProvider clock) =>
        {
            var twin = twins.Find(req.FixtureId);
            if (twin is null) return ApiResults.NotFound($"Fixture '{req.FixtureId}' is not installed");
            return Guard(() =>
            {
                var session = store.Create(twin, req.AssetId, clock.GetUtcNow());
                return Results.Created($"/v1/inspections/{session.State.InspectionId}", View(session));
            });
        });

        g.MapGet("/inspections/{id}", (string id, InspectionStore store) =>
            store.Find(id) is { } s ? Results.Ok(View(s)) : ApiResults.NotFound($"Inspection '{id}' not found"));

        g.MapGet("/inspections/{id}/events", (string id, InspectionStore store) =>
            store.Find(id) is { } s ? Results.Ok(s.Events) : ApiResults.NotFound($"Inspection '{id}' not found"));

        // FR-02: multipart upload of the original JPEG/PNG. Magic bytes decide the type; declared content type is ignored.
        g.MapPost("/inspections/{id}/observations", async (string id, HttpRequest http, InspectionStore store, EvidenceFileStore files, TimeProvider clock, CancellationToken ct) =>
        {
            if (store.Find(id) is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            if (!http.HasFormContentType) return ApiResults.BadRequest("multipart/form-data with an 'image' file is required");
            var form = await http.ReadFormAsync(ct);
            var file = form.Files.GetFile("image");
            if (file is null || file.Length == 0) return ApiResults.BadRequest("'image' file is required");
            if (file.Length > EvidenceFileStore.MaxImageBytes) return ApiResults.BadRequest($"image exceeds {EvidenceFileStore.MaxImageBytes} bytes");
            using var ms = new MemoryStream();
            await file.CopyToAsync(ms, ct);
            var bytes = ms.ToArray();
            var mediaType = EvidenceFileStore.DetectImageType(bytes);
            if (mediaType is null) return ApiResults.BadRequest("image must be JPEG or PNG (checked by magic bytes)");
            var origin = Enum.TryParse<EvidenceOrigin>(form["source"], ignoreCase: true, out var o) ? o : EvidenceOrigin.Phone;
            var observedAt = DateTimeOffset.TryParse(form["observedAt"], out var obs) ? obs : clock.GetUtcNow();
            int.TryParse(form["width"], out var width); int.TryParse(form["height"], out var height);
            var evidenceId = "photo-" + Guid.NewGuid().ToString("N")[..10];
            var artifact = files.SaveImage(id, evidenceId, bytes, mediaType, width, height);
            var requestId = RequestId(http, "observation:" + artifact.Sha256);
            return await GuardAsync(() => store.ExecuteAsync(id, s =>
            {
                var events = s.RecordPhoto(requestId, evidenceId, origin, artifact.Sha256, observedAt, width, height, clock.GetUtcNow());
                return Task.FromResult(Results.Ok(new { evidenceId, artifact.Sha256, artifact.MediaType, artifact.Bytes, origin = origin.ToString(), events = events.Count, state = View(s) }));
            }, ct));
        }).DisableAntiforgery();

        // FR-04: genuine Muse assessment of a photo already in the inspection; the Core validates and decides the stage.
        g.MapPost("/inspections/{id}/investigate", async (string id, HttpRequest http, InspectionStore store, EvidenceFileStore files, VisualAssessmentService muse, ExpectedTwinStore twins, TimeProvider clock, CancellationToken ct) =>
        {
            var session = store.Find(id);
            if (session is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            var photo = session.State.Photos.LastOrDefault();
            if (photo is null) return ApiResults.Conflict(InspectionErrorCodes.InvalidState, "No photo to assess");
            var twin = session.Twin;
            var dir = Path.Combine("evidence", id);
            var artifactPath = Directory.EnumerateFiles(Path.Combine(files.DataDirectoryPath, dir)).FirstOrDefault(f => Path.GetFileName(f).StartsWith(photo.EvidenceId + "-", StringComparison.Ordinal));
            if (artifactPath is null) return ApiResults.Conflict(InspectionErrorCodes.InvalidState, "Photo artifact missing");
            var bytes = File.ReadAllBytes(artifactPath);
            var mediaType = EvidenceFileStore.DetectImageType(bytes) ?? "image/jpeg";
            var outcome = await muse.AssessAsync(session.State, twin.Twin, bytes, mediaType, photo.EvidenceId, ct);
            if (outcome.Assessment is null)
                return ApiResults.Conflict(outcome.Error?.StartsWith("provider") == true ? "provider_unavailable" : InspectionErrorCodes.InvalidModelOutput, outcome.Error ?? "model returned nothing");
            var requestId = RequestId(http, "investigate:" + outcome.ResponseDigest);
            return await GuardAsync(() => store.ExecuteAsync(id, s =>
            {
                var evidenceId = "assess-" + outcome.ResponseDigest[..10];
                var events = s.RecordAssessment(requestId, evidenceId, outcome.Assessment, clock.GetUtcNow());
                return Task.FromResult(Results.Ok(new { evidenceId, outcome.ModelId, outcome.PromptDigest, outcome.ResponseDigest, usage = outcome.Usage, assessment = outcome.Assessment, events = events.Count, state = View(s) }));
            }, ct));
        });

        g.MapPost("/inspections/{id}/segmentations", (string id, SegmentationRequest req, HttpRequest http, InspectionStore store, TimeProvider clock) =>
            Guard(() => store.Execute(id, s =>
            {
                var evidenceId = "seg-" + Guid.NewGuid().ToString("N")[..10];
                s.RecordSegmentation(RequestId(http, "seg:" + req.PhotoEvidenceId + ":" + req.Concept), evidenceId, req.PhotoEvidenceId, req.Concept, req.Matched, req.MaskSha256, req.ModelId, clock.GetUtcNow());
                return Results.Ok(new { evidenceId, state = View(s) });
            })));

        g.MapPost("/inspections/{id}/predictions", (string id, PredictionRequest req, HttpRequest http, InspectionStore store, TimeProvider clock) =>
            Guard(() => store.Execute(id, s =>
            {
                var predictionId = "pred-" + Guid.NewGuid().ToString("N")[..10];
                s.RegisterPrediction(RequestId(http, "pred:" + req.DiagnosticId + ":" + req.PredictedValue), predictionId, req.DiagnosticId, req.PredictedValue, req.BasedOnFactIds, clock.GetUtcNow());
                return Results.Ok(new { predictionId, state = View(s) });
            })));

        // FR-06: only a fixture-approved diagnostic ID; the dispatcher resolves the alias server-side.
        g.MapPost("/inspections/{id}/diagnostics/{diagnosticId}:run", async (string id, string diagnosticId, HttpRequest http, InspectionStore store, DiagnosticDispatcher dispatcher, TimeProvider clock, CancellationToken ct) =>
        {
            var session = store.Find(id);
            if (session is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            return await GuardAsync(async () =>
            {
                var traceId = Guid.NewGuid().ToString("N");
                var result = await dispatcher.RunAsync(session.Twin.Twin, diagnosticId, traceId, ct);
                return await store.ExecuteAsync(id, s =>
                {
                    var evidenceId = "diag-" + traceId[..10];
                    var events = s.RecordDiagnostic(RequestId(http, "diag:" + traceId), evidenceId, result, clock.GetUtcNow());
                    return Task.FromResult(Results.Ok(new { evidenceId, result, source = result.Source.ToString(), events = events.Count, state = View(s) }));
                }, ct);
            });
        });

        g.MapPost("/inspections/{id}/work-order", (string id, WorkOrderRequest req, HttpRequest http, InspectionStore store, TimeProvider clock) =>
            Guard(() => store.Execute(id, s => { s.DraftWorkOrder(RequestId(http, "wo:" + req.Summary), req.Summary, clock.GetUtcNow()); return Results.Ok(View(s)); })));

        g.MapPost("/inspections/{id}/repair-claims", (string id, RepairClaimRequest req, HttpRequest http, InspectionStore store, TimeProvider clock) =>
            Guard(() => store.Execute(id, s =>
            {
                var claimId = "claim-" + Guid.NewGuid().ToString("N")[..10];
                s.ClaimRepair(RequestId(http, "claim:" + claimId), claimId, req.Description, clock.GetUtcNow());
                return Results.Ok(new { claimId, state = View(s) });
            })));

        g.MapPost("/inspections/{id}/checklist", (string id, ChecklistRequest req, HttpRequest http, InspectionStore store, TimeProvider clock) =>
            Guard(() => store.Execute(id, s => { s.ReviewChecklist(RequestId(http, $"check:{req.RequirementId}:{req.Status}:{req.PhotoEvidenceId}"), req.RequirementId, req.Status, req.PhotoEvidenceId, clock.GetUtcNow()); return Results.Ok(View(s)); })));

        g.MapPost("/inspections/{id}/acknowledge", (string id, HttpRequest http, InspectionStore store, TimeProvider clock) =>
            Guard(() => store.Execute(id, s => { s.Acknowledge(RequestId(http, "ack:" + s.State.CurrentClaimId), clock.GetUtcNow()); return Results.Ok(View(s)); })));

        // FR-08: server-owned gate. Runs the fixture's diagnostics first unless a fresh post-claim result exists.
        g.MapPost("/inspections/{id}/verify", async (string id, HttpRequest http, InspectionStore store, DiagnosticDispatcher dispatcher, TimeProvider clock, CancellationToken ct) =>
        {
            var session = store.Find(id);
            if (session is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            var requestId = RequestId(http, "verify:" + Guid.NewGuid().ToString("N"));
            return await GuardAsync(async () =>
            {
                if (session.State.DecisionsByRequest.ContainsKey(requestId))
                    return Results.Ok(new { decision = session.State.DecisionsByRequest[requestId], state = View(session), replayed = true });
                if (session.State.Stage != InspectionStage.ReadyToVerify)
                    throw new InspectionConflictException(InspectionErrorCodes.InvalidState, $"Verification requires a pending repair claim (stage {session.State.Stage})");
                var twin = session.Twin.Twin;
                var now = clock.GetUtcNow();
                foreach (var d in twin.Diagnostics)
                {
                    var latest = session.State.LatestDiagnosticFor(d.DiagnosticId);
                    var fresh = latest is not null && latest.Result.CompletedAt > session.State.RepairClaimedAt
                                && (now - latest.Result.CompletedAt).TotalSeconds <= twin.VerificationPolicy.DiagnosticMaxAgeSeconds;
                    if (fresh) continue;
                    var traceId = Guid.NewGuid().ToString("N");
                    var result = await dispatcher.RunAsync(twin, d.DiagnosticId, traceId, ct);
                    await store.ExecuteAsync(id, s => { s.RecordDiagnostic("verify-diag:" + traceId, "diag-" + traceId[..10], result, clock.GetUtcNow()); return Task.FromResult(0); }, ct);
                }
                return await store.ExecuteAsync(id, s =>
                {
                    var decision = s.Verify(requestId, clock.GetUtcNow());
                    return Task.FromResult(Results.Ok(new { decision, state = View(s), replayed = false }));
                }, ct);
            });
        });

        return app;
    }

    public static object View(InspectionSession s)
    {
        var st = s.State;
        return new
        {
            inspectionId = st.InspectionId, st.FixtureId, st.FixtureRevision, st.FixtureDigest, st.AssetId,
            stage = st.Stage.ToString(), st.LastSequence, st.ViewRequests, st.CurrentClaimId, st.RepairClaimedAt, st.TechnicianAcknowledged,
            photos = st.Photos, diagnostics = st.Diagnostics.Select(d => new { d.EvidenceId, d.Result, source = d.Result.Source.ToString() }),
            checklist = st.Checklist.Values, pendingPredictions = st.PendingPredictions, predictionScores = st.PredictionScores,
            lastDecision = st.LastDecision,
            outcomeKind = st.LastDecision?.OutcomeKind?.ToString(),
        };
    }

    /// <summary>Idempotency is keyed only by a client-supplied Idempotency-Key (PRD FR-05). Without one, every request is
    /// distinct; content-derived keys would silently swallow legitimate repeats such as two identical test photos.</summary>
    private static string RequestId(HttpRequest http, string purpose) =>
        http.Headers.TryGetValue("Idempotency-Key", out var v) && !string.IsNullOrWhiteSpace(v) ? v.ToString() : purpose + ":" + Guid.NewGuid().ToString("N");

    private static IResult Guard(Func<IResult> action)
    {
        try { return action(); }
        catch (InspectionConflictException ex) { return ApiResults.Conflict(ex.Code, ex.Message); }
        catch (KeyNotFoundException ex) { return ApiResults.NotFound($"Inspection '{ex.Message}' not found"); }
        catch (Exception ex) { return Results.Json(new ApiError("internal", ex.GetType().Name + ": " + ex.Message), statusCode: 500); }
    }

    private static async Task<IResult> GuardAsync(Func<Task<IResult>> action)
    {
        try { return await action(); }
        catch (InspectionConflictException ex) { return ApiResults.Conflict(ex.Code, ex.Message); }
        catch (KeyNotFoundException ex) { return ApiResults.NotFound($"Inspection '{ex.Message}' not found"); }
        catch (Exception ex) { return Results.Json(new ApiError("internal", ex.GetType().Name + ": " + ex.Message), statusCode: 500); }
    }
}
