using System.Security.Cryptography;
using LensQi.FieldOps.Api.Infrastructure.Evidence;
using LensQi.FieldOps.Api.Infrastructure.Fixtures;
using LensQi.FieldOps.Api.Infrastructure.Hardening;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Diagnostics;
using LensQi.FieldOps.Meta.Segmentation;
using LensQi.FieldOps.Meta.Vision;

namespace LensQi.FieldOps.Api.Features.Inspections;

/// <summary>PRD §9 candidate endpoints over the Core session. Every state change goes through InspectionSession;
/// endpoints only translate HTTP into commands and typed conflicts into stable error codes.</summary>
public static class InspectionEndpoints
{
    /// <summary>Origins a client may claim for an uploaded photo; model and probe origins are server-assigned only.</summary>
    private static readonly Dictionary<string, EvidenceOrigin> CaptureOrigins = new(StringComparer.OrdinalIgnoreCase)
    {
        ["Phone"] = EvidenceOrigin.Phone, ["Glasses"] = EvidenceOrigin.Glasses,
        ["MockWearables"] = EvidenceOrigin.MockWearables, ["RecordedFixture"] = EvidenceOrigin.RecordedFixture,
    };

    /// <summary>Provenance is never guessed: no source means a phone capture, anything else must name a capture origin.</summary>
    internal static bool TryCaptureOrigin(string? source, out EvidenceOrigin origin)
    {
        if (string.IsNullOrWhiteSpace(source)) { origin = EvidenceOrigin.Phone; return true; }
        return CaptureOrigins.TryGetValue(source.Trim(), out origin);
    }

    internal static IResult InvalidSource() => ApiResults.BadRequest("invalid_source", $"source must be one of {string.Join(", ", CaptureOrigins.Keys)}");

    public sealed record CreateInspectionRequest(string FixtureId, string AssetId);
    public sealed record ChecklistRequest(string RequirementId, ChecklistStatus Status, string PhotoEvidenceId);
    public sealed record RepairClaimRequest(string Description);
    public sealed record WorkOrderRequest(string Summary);
    public sealed record EvidenceRequestBody(string Note);
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
            // The site's expected installation state (port, cable, peer); documentation of intent, never proof of the current state.
            connections = t.Twin.Connections,
            t.Twin.VerificationPolicy,
        })));

        // A retried create (same Idempotency-Key, same fixture and asset) returns the inspection the first attempt made,
        // never a second one; the same key with different inputs is refused.
        g.MapPost("/inspections", (CreateInspectionRequest req, HttpRequest http, ExpectedTwinStore twins, InspectionStore store, TimeProvider clock) =>
        {
            var key = Key(http);
            var twin = twins.Find(req.FixtureId);
            if (twin is null) return ApiResults.NotFound($"Fixture '{req.FixtureId}' is not installed");
            return Guard(() =>
            {
                if (key is not null && store.FindCreated(key) is { } earlier)
                    return Results.Ok(View(InspectionStore.SameCreate(earlier, twin, req.AssetId)));
                var session = store.Create(twin, req.AssetId, clock.GetUtcNow(), key);
                return Results.Created($"/v1/inspections/{session.State.InspectionId}", View(session));
            });
        });

        // Summaries for the technician home and the operations dashboard, newest first; derived from the event logs only.
        g.MapGet("/inspections", (InspectionStore store) => Results.Ok(store.All.Select(Summary).OrderByDescending(x => x.UpdatedAt)));

        g.MapGet("/inspections/{id}", (string id, InspectionStore store) =>
            store.Find(id) is { } s ? Results.Ok(View(s)) : ApiResults.NotFound($"Inspection '{id}' not found"));

        g.MapGet("/inspections/{id}/events", (string id, InspectionStore store) =>
            store.Find(id) is { } s ? Results.Ok(s.Events) : ApiResults.NotFound($"Inspection '{id}' not found"));

        // FR-02: multipart upload of the original JPEG/PNG. Magic bytes decide the type; declared content type is ignored.
        g.MapPost("/inspections/{id}/observations", async (string id, HttpRequest http, InspectionStore store, EvidenceFileStore files, IImageAnalyzer imaging, IImageOrienter orienter, ChangeEvidenceService change, HardeningOptions limits, TimeProvider clock, CancellationToken ct) =>
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
            // A JPEG turned by its EXIF orientation is stored the way it is shown, so SAM, the crops and every client draw
            // on the same pixels. The digest of the bytes as uploaded is returned too, so a client can still check its copy.
            var uploadedSha256 = Convert.ToHexStringLower(SHA256.HashData(bytes));
            var orientation = EvidenceFileStore.ReadExifOrientation(bytes);
            // Re-encoding can grow the file; a turned copy over the upload limit is not stored, the bytes as uploaded are.
            if (orientation != 1 && await orienter.OrientAsync(bytes, orientation, ct) is { Length: > 0 } turned && turned.LongLength <= EvidenceFileStore.MaxImageBytes && EvidenceFileStore.DetectImageType(turned) == "image/jpeg")
                bytes = turned;
            // T6.1: size comes from the file header, never from the form; too small or too large is refused before storage.
            if (!EvidenceFileStore.TryReadDimensions(bytes, out var width, out var height))
                return ApiResults.BadRequest("image_unreadable", "image header has no readable pixel size");
            if (Math.Min(width, height) < limits.MinImageShortSide || Math.Max(width, height) < limits.MinImageLongSide)
                return ApiResults.BadRequest("image_too_small", $"image is {width}x{height}; at least {limits.MinImageLongSide}x{limits.MinImageShortSide} is required");
            if ((long)width * height > limits.MaxImagePixels)
                return ApiResults.BadRequest("image_too_large", $"image is {width}x{height}; at most {limits.MaxImagePixels} pixels are accepted");
            if (!TryCaptureOrigin(form["source"].ToString(), out var origin)) return InvalidSource();
            // What the phone's ARCore measured at the shutter travels with the photo as a measured note; malformed is refused, never guessed.
            if (MeasuredParts.Parse(form["measured"]) is not { } measured) return MeasuredParts.Invalid();
            var observedAt = DateTimeOffset.TryParse(form["observedAt"], out var obs) ? obs : clock.GetUtcNow();
            var evidenceId = "photo-" + Guid.NewGuid().ToString("N")[..10];
            var artifact = files.SaveImage(id, evidenceId, bytes, mediaType, width, height);
            // Blur and luminance are signals beside the evidence; a missing measurement never blocks the upload.
            var artifactPath = Path.Combine(files.DataDirectoryPath, artifact.RelativePath);
            var signals = await imaging.MeasureAsync(artifactPath, ct);
            var requestId = RequestId(http, "observation:" + artifact.Sha256);
            return await GuardAsync(async () =>
            {
                var events = await store.ExecuteAsync(id, s => Task.FromResult(
                    s.RecordPhoto(requestId, evidenceId, origin, artifact.Sha256, observedAt, width, height, clock.GetUtcNow(), signals?.Quality, signals?.PerceptualHash, measured)), ct);
                // A replayed Idempotency-Key returns the earlier events and records no new photo: the answer is about the photo
                // recorded the first time, and the copy this attempt saved is dropped.
                if (events.OfType<PhotoRecorded>().FirstOrDefault() is { } recorded && recorded.EvidenceId != evidenceId)
                {
                    File.Delete(artifactPath);
                    evidenceId = recorded.EvidenceId;
                    artifactPath = Directory.EnumerateFiles(Path.Combine(files.DataDirectoryPath, "evidence", id))
                        .First(f => Path.GetFileName(f).StartsWith(evidenceId + "-", StringComparison.Ordinal));
                }
                // VK-T26: a photo taken after the current claim gets change evidence before anyone can review or verify it,
                // and is not post-repair evidence until then. A retry finishes an analysis an earlier attempt did not record.
                VisualChangeAnalysis? analysis = null;
                var session = store.Find(id)!;
                if (session.State.Photos.FirstOrDefault(p => p.EvidenceId == evidenceId) is { } photo && ChangeEvidenceService.Applies(session.State, photo))
                {
                    analysis = session.State.ChangeAnalyses.LastOrDefault(a => a.ClaimId == session.State.CurrentClaimId && a.PhotoEvidenceId == photo.EvidenceId);
                    if (analysis is null)
                    {
                        // Once the photo is recorded its analysis is finished even if the client goes away: a photo left
                        // half-analysed would hold verification until a newer photo arrived.
                        var result = await change.AnalyzeAsync(session.State, photo, artifactPath, CancellationToken.None);
                        analysis = result.Analysis;
                        await store.ExecuteAsync(id, s =>
                        {
                            if (result.AfterSegmentation is { } seg)
                                s.RecordSegmentation("change-seg:" + evidenceId, seg.SegmentationId, evidenceId, seg.Concept, seg.Matched, seg.MaskSha256, seg.ModelId, clock.GetUtcNow(), seg.GeometrySha256);
                            return Task.FromResult(s.RecordVisualChange("change:" + evidenceId, result.Analysis, clock.GetUtcNow()));
                        }, CancellationToken.None);
                    }
                }
                var st = store.Find(id)!;
                return Results.Ok(new { evidenceId, artifact.Sha256, uploadedSha256, artifact.MediaType, artifact.Bytes, width, height, quality = signals?.Quality, perceptualHash = signals?.PerceptualHash,
                    changeAnalysis = analysis, origin = origin.ToString(), events = events.Count, state = View(st) });
            });
        }).DisableAntiforgery();

        // FR-04: genuine Muse assessment of a photo already in the inspection; the Core validates and decides the stage.
        g.MapPost("/inspections/{id}/investigate", async (string id, HttpRequest http, InspectionStore store, EvidenceFileStore files, VisualAssessmentService muse, ExpectedTwinStore twins, KeyedGate gate, TimeProvider clock, CancellationToken ct) =>
        {
            var session = store.Find(id);
            if (session is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            // A retried key returns what was recorded, and a stage that would refuse the assessment is refused before the paid
            // call. An overlapping retry waits for the first request, then replays it.
            using var _ = await gate.EnterAsync(id, Key(http), ct);
            if (Key(http) is { } key && session.Recorded(key) is { } done)
                return done.OfType<AssessmentRecorded>().FirstOrDefault() is { } a
                    ? Results.Ok(new { a.EvidenceId, a.Assessment.ModelId, promptDigest = a.PromptDigest ?? "", responseDigest = a.ResponseDigest ?? "", usage = (object?)null, assessment = a.Assessment, events = done.Count, state = View(session), replayed = true })
                    : DifferentCommand();
            if (!session.AcceptsAssessment)
                return ApiResults.Conflict(InspectionErrorCodes.InvalidState, $"Assessment is not accepted in the current stage (stage {session.State.Stage})");
            var photo = session.State.Photos.LastOrDefault();
            if (photo is null) return ApiResults.Conflict(InspectionErrorCodes.InvalidState, "No photo to assess");
            var requestedPhoto = http.Query["photoEvidenceId"].ToString();
            if (!string.IsNullOrEmpty(requestedPhoto) && requestedPhoto != photo.EvidenceId)
                return ApiResults.Conflict("photo_superseded", "A newer photo is available. Open the latest capture to review it.");
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
                if (s.State.Photos.LastOrDefault()?.EvidenceId != photo.EvidenceId)
                    return Task.FromResult<IResult>(ApiResults.Conflict("photo_superseded", "A newer photo arrived during analysis. Review the latest capture."));
                var evidenceId = "assess-" + outcome.ResponseDigest[..10];
                var events = s.RecordAssessment(requestId, evidenceId, outcome.Assessment, clock.GetUtcNow(), outcome.PromptDigest, outcome.ResponseDigest);
                return Task.FromResult(Results.Ok(new { evidenceId, outcome.ModelId, outcome.PromptDigest, outcome.ResponseDigest, usage = outcome.Usage, assessment = outcome.Assessment, events = events.Count, state = View(s), replayed = false }));
            }, ct));
        }).AsModelCall();

        // Child design §8: latest change analysis for a repair claim; the analysis itself runs on evidence submission.
        g.MapGet("/inspections/{id}/claims/{claimId}/change-analysis", (string id, string claimId, InspectionStore store) =>
            store.Find(id) is not { } s ? ApiResults.NotFound($"Inspection '{id}' not found")
            : s.State.ChangeAnalyses.LastOrDefault(a => a.ClaimId == claimId) is { } a ? Results.Ok(a)
            : ApiResults.NotFound($"No change analysis for claim '{claimId}'"));

        g.MapPost("/inspections/{id}/segmentations", (string id, SegmentationRequest req, HttpRequest http, InspectionStore store, TimeProvider clock) =>
            Guard(() => store.Execute(id, s =>
            {
                var events = s.RecordSegmentation(RequestId(http, "seg:" + req.PhotoEvidenceId + ":" + req.Concept), "seg-" + Guid.NewGuid().ToString("N")[..10], req.PhotoEvidenceId, req.Concept, req.Matched, req.MaskSha256, req.ModelId, clock.GetUtcNow());
                // A replayed key returns the recorded ID, never the one this attempt would have used.
                return events.OfType<SegmentationRecorded>().FirstOrDefault() is { } seg ? Results.Ok(new { evidenceId = seg.EvidenceId, state = View(s) }) : DifferentCommand();
            })));

        g.MapPost("/inspections/{id}/predictions", (string id, PredictionRequest req, HttpRequest http, InspectionStore store, TimeProvider clock) =>
            Guard(() => store.Execute(id, s =>
            {
                var events = s.RegisterPrediction(RequestId(http, "pred:" + req.DiagnosticId + ":" + req.PredictedValue), "pred-" + Guid.NewGuid().ToString("N")[..10], req.DiagnosticId, req.PredictedValue, req.BasedOnFactIds, clock.GetUtcNow());
                return events.OfType<PredictionRegistered>().FirstOrDefault() is { } p ? Results.Ok(new { predictionId = p.PredictionId, state = View(s) }) : DifferentCommand();
            })));

        // FR-06: only a fixture-approved diagnostic ID; the dispatcher resolves the alias server-side. The replay check, the
        // probe and the record run under the inspection's lock: a retried key returns the recorded measurement instead of
        // probing again, so the response always matches the event trail.
        g.MapPost("/inspections/{id}/diagnostics/{diagnosticId}:run", async (string id, string diagnosticId, HttpRequest http, InspectionStore store, DiagnosticDispatcher dispatcher, TimeProvider clock, CancellationToken ct) =>
        {
            if (store.Find(id) is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            var requestId = RequestId(http, "diag");
            return await GuardAsync(() => store.ExecuteAsync(id, async s =>
            {
                if (s.Recorded(requestId) is { } done)
                    return done.OfType<DiagnosticCompleted>().FirstOrDefault() is { } d
                        ? Results.Ok(new { evidenceId = d.EvidenceId, result = d.Result, source = d.Result.Source.ToString(), events = done.Count, state = View(s), replayed = true })
                        : DifferentCommand();
                var traceId = Guid.NewGuid().ToString("N");
                var result = await dispatcher.RunAsync(s.Twin.Twin, diagnosticId, traceId, ct);
                var evidenceId = "diag-" + traceId[..10];
                var events = s.RecordDiagnostic(requestId, evidenceId, result, clock.GetUtcNow());
                return Results.Ok(new { evidenceId, result, source = result.Source.ToString(), events = events.Count, state = View(s), replayed = false });
            }, ct));
        });

        g.MapPost("/inspections/{id}/decline-view", (string id, HttpRequest http, InspectionStore store, TimeProvider clock) =>
            Guard(() => store.Execute(id, s => { s.DeclineView(RequestId(http, "decline"), clock.GetUtcNow()); return Results.Ok(View(s)); })));

        g.MapPost("/inspections/{id}/work-order", (string id, WorkOrderRequest req, HttpRequest http, InspectionStore store, TimeProvider clock) =>
            Guard(() => store.Execute(id, s => { s.DraftWorkOrder(RequestId(http, "wo:" + req.Summary), req.Summary, clock.GetUtcNow()); return Results.Ok(View(s)); })));

        g.MapPost("/inspections/{id}/repair-claims", (string id, RepairClaimRequest req, HttpRequest http, InspectionStore store, TimeProvider clock) =>
            Guard(() => store.Execute(id, s =>
            {
                var claimId = "claim-" + Guid.NewGuid().ToString("N")[..10];
                var events = s.ClaimRepair(RequestId(http, "claim:" + claimId), claimId, req.Description, clock.GetUtcNow());
                // A replayed key returns the claim that was recorded, not the ID this attempt generated.
                return events.OfType<RepairClaimed>().FirstOrDefault() is { } c ? Results.Ok(new { claimId = c.ClaimId, state = View(s) }) : DifferentCommand();
            })));

        g.MapPost("/inspections/{id}/checklist", (string id, ChecklistRequest req, HttpRequest http, InspectionStore store, TimeProvider clock) =>
            Guard(() => store.Execute(id, s => { s.ReviewChecklist(RequestId(http, $"check:{req.RequirementId}:{req.Status}:{req.PhotoEvidenceId}"), req.RequirementId, req.Status, req.PhotoEvidenceId, clock.GetUtcNow()); return Results.Ok(View(s)); })));

        // A reviewer on the operations desk asks the technician for more evidence. There are no user accounts yet,
        // so the requester is recorded as the operations desk, not as a named person.
        g.MapPost("/inspections/{id}/evidence-requests", (string id, EvidenceRequestBody req, HttpRequest http, InspectionStore store, TimeProvider clock) =>
        {
            if (string.IsNullOrWhiteSpace(req.Note) || req.Note.Length > 500) return ApiResults.BadRequest("note_invalid", "note is required and at most 500 characters");
            return Guard(() => store.Execute(id, s => { s.RequestEvidence(RequestId(http, "evidence-request"), req.Note, "Operations desk", clock.GetUtcNow()); return Results.Ok(View(s)); }));
        });

        g.MapPost("/inspections/{id}/acknowledge", (string id, HttpRequest http, InspectionStore store, TimeProvider clock) =>
            Guard(() => store.Execute(id, s => { s.Acknowledge(RequestId(http, "ack:" + s.State.CurrentClaimId), clock.GetUtcNow()); return Results.Ok(View(s)); })));

        // FR-08: server-owned gate. Every verify that passes the visual and human prerequisites runs the fixture's
        // diagnostics again, so the decision always rests on a measurement taken for this attempt; when the prerequisites
        // already decide the outcome no probe is spent. The stage check, the probes and the decision run under one lock,
        // so a concurrent or retried verify waits and then replays its recorded decision or is refused, instead of
        // running the probes a second time. The probes are read-only health checks, so one cut off mid-call is safe to run again.
        g.MapPost("/inspections/{id}/verify", async (string id, HttpRequest http, InspectionStore store, DiagnosticDispatcher dispatcher, TimeProvider clock, CancellationToken ct) =>
        {
            if (store.Find(id) is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            var requestId = RequestId(http, "verify:" + Guid.NewGuid().ToString("N"));
            return await GuardAsync(() => store.ExecuteAsync(id, async s =>
            {
                if (s.State.DecisionsByRequest.TryGetValue(requestId, out var earlier))
                    return Results.Ok(new { decision = earlier, state = View(s), replayed = true });
                if (s.State.Stage != InspectionStage.ReadyToVerify)
                    throw new InspectionConflictException(InspectionErrorCodes.InvalidState, $"Verification requires a pending repair claim (stage {s.State.Stage})");
                var twin = s.Twin.Twin;
                // Photo, checklist and acknowledgement are checked first and decide first; when they already decide, no
                // probe is spent and the recorded decision says what is missing.
                var ready = VerificationPolicyEvaluator.Prerequisites(s.State, s.Twin) is null;
                foreach (var d in ready ? twin.Diagnostics : [])
                {
                    var traceId = Guid.NewGuid().ToString("N");
                    var result = await dispatcher.RunAsync(twin, d.DiagnosticId, traceId, ct);
                    // The evidence id marks a measurement taken by verify, so clients can show which checks a decision used.
                    s.RecordDiagnostic("verify-diag:" + traceId, VerifyDiagnosticPrefix + traceId[..10], result, clock.GetUtcNow());
                }
                var decision = s.Verify(requestId, clock.GetUtcNow());
                return Results.Ok(new { decision, state = View(s), replayed = false });
            }, ct));
        });

        return app;
    }

    /// <summary>Evidence ids of diagnostics the verify call ran itself start with this; a manual run's start with "diag-".</summary>
    public const string VerifyDiagnosticPrefix = "verify-diag-";

    public sealed record InspectionSummary(string InspectionId, string FixtureId, string AssetId, string Stage, string? OutcomeKind,
        DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt, int Photos, int Diagnostics, string? LastDecision, string? LatestChange,
        string? CurrentClaimId, string? WorkOrder, string? Claim, int VerificationAttempts, EvidenceRequest? OpenEvidenceRequest);

    public static InspectionSummary Summary(InspectionSession s)
    {
        var st = s.State;
        return new InspectionSummary(st.InspectionId, st.FixtureId, st.AssetId, st.Stage.ToString(), st.LastDecision?.OutcomeKind?.ToString(),
            s.Events[0].OccurredAt, s.Events[^1].OccurredAt, st.Photos.Count, st.Diagnostics.Count, st.LastDecision?.Result.ToString(),
            st.ChangeAnalyses.LastOrDefault()?.Verdict.ToString(), st.CurrentClaimId,
            s.Events.OfType<WorkOrderDrafted>().LastOrDefault()?.Summary, s.Events.OfType<RepairClaimed>().LastOrDefault()?.Description,
            s.Events.OfType<VerificationEvaluated>().Count(), st.EvidenceRequests.LastOrDefault(r => r.AnsweredByPhotoId is null));
    }

    public static object View(InspectionSession s)
    {
        var st = s.State;
        return new
        {
            inspectionId = st.InspectionId, st.FixtureId, st.FixtureRevision, st.FixtureDigest, st.AssetId,
            stage = st.Stage.ToString(), st.LastSequence, st.ViewRequests, st.CurrentClaimId, st.RepairClaimedAt, st.TechnicianAcknowledged,
            photos = st.Photos, diagnostics = st.Diagnostics.Select(d => new { d.EvidenceId, d.Result, source = d.Result.Source.ToString() }),
            checklist = st.Checklist.Values, segmentations = st.Segmentations, changeAnalyses = st.ChangeAnalyses,
            acceptedPostRepairPhotoId = st.LatestPostRepairPhoto?.EvidenceId, awaitingAnalysis = st.PhotosBeingCompared.Select(p => p.EvidenceId), pendingPredictions = st.PendingPredictions, predictionScores = st.PredictionScores,
            lastDecision = st.LastDecision, evidenceRequests = st.EvidenceRequests,
            outcomeKind = st.LastDecision?.OutcomeKind?.ToString(),
            readiness = Readiness(s),
        };
    }

    /// <summary>The server's verification gate for clients: whether verify can run, the next step and every blocking
    /// reason, plus the latest result of each fixture diagnostic. Verify always measures again; these are for display.</summary>
    public static object Readiness(InspectionSession s)
    {
        var st = s.State;
        var r = VerificationReadiness.Of(st, s.Twin);
        return new
        {
            r.Ready, next = r.Next.ToString(), r.Blocking,
            checks = s.Twin.Twin.Diagnostics.Select(d =>
            {
                var last = st.LatestDiagnosticFor(d.DiagnosticId)?.Result;
                return new
                {
                    d.DiagnosticId, lastRunAt = last?.CompletedAt, source = last?.Source.ToString(), passed = last?.Passed, value = last?.NormalizedValue,
                    afterClaim = last is not null && st.RepairClaimedAt is { } at && last.CompletedAt > at,
                };
            }),
            measuresOnVerify = true,
        };
    }

    /// <summary>Idempotency is keyed only by a client-supplied Idempotency-Key (PRD FR-05). Without one, every request is
    /// distinct; content-derived keys would silently swallow legitimate repeats such as two identical test photos.</summary>
    internal static IResult DifferentCommand() => ApiResults.Conflict(InspectionErrorCodes.InvalidState, "This Idempotency-Key was used for a different command");

    /// <summary>The client's Idempotency-Key, or null when the request did not send one.</summary>
    internal static string? Key(HttpRequest http) =>
        http.Headers.TryGetValue("Idempotency-Key", out var v) && !string.IsNullOrWhiteSpace(v) ? v.ToString() : null;

    private static string RequestId(HttpRequest http, string purpose) =>
        http.Headers.TryGetValue("Idempotency-Key", out var v) && !string.IsNullOrWhiteSpace(v) ? v.ToString() : purpose + ":" + Guid.NewGuid().ToString("N");

    private static IResult Guard(Func<IResult> action)
    {
        try { return action(); }
        catch (InspectionConflictException ex) { return ApiResults.Conflict(ex.Code, ex.Message); }
        catch (KeyNotFoundException ex) { return ApiResults.NotFound($"Inspection '{ex.Message}' not found"); }
    }

    private static async Task<IResult> GuardAsync(Func<Task<IResult>> action)
    {
        try { return await action(); }
        catch (OperationCanceledException) { throw; }   // request aborted or timed out: the timeout middleware answers
        catch (InspectionConflictException ex) { return ApiResults.Conflict(ex.Code, ex.Message); }
        catch (KeyNotFoundException ex) { return ApiResults.NotFound($"Inspection '{ex.Message}' not found"); }
    }
}
