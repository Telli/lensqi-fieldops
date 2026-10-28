using System.Diagnostics;
using System.Text.RegularExpressions;
using LensQi.FieldOps.Api.Infrastructure.Evidence;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Meta.Segmentation;
using LensQi.FieldOps.Meta.Vision;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.Extensions.Caching.Memory;
using System.Threading.RateLimiting;

namespace LensQi.FieldOps.Api.Features.LiveVision;

public sealed class LiveVisionOptions
{
    public int DetectionIntervalMs { get; set; } = 4000;
    public int GuidanceIntervalMs { get; set; } = 12000;
    public int TimeoutSeconds { get; set; } = 20;
    public int RequestsPerMinute { get; set; } = 30;
}

/// <summary>Single flight per inspection/kind, with a bounded global provider concurrency and expiring cooldowns.</summary>
public sealed class LiveVisionGate(IMemoryCache cache)
{
    private readonly object sync = new();
    private readonly HashSet<string> active = [];
    private readonly SemaphoreSlim slots = new(4);
    public bool Enter(string key, int intervalMs)
    {
        lock (sync)
        {
            if (active.Contains(key) || cache.TryGetValue(key, out _) || !slots.Wait(0)) return false;
            active.Add(key);
            cache.Set(key, true, TimeSpan.FromMilliseconds(intervalMs));
            return true;
        }
    }
    public void Exit(string key) { lock (sync) { if (active.Remove(key)) slots.Release(); } }
}

/// <summary>Ephemeral preview inference. Never appends events, persists frames, consumes view requests, or decides a repair.</summary>
public static class LiveVisionEndpoints
{
    public static IServiceCollection AddLiveVision(this IServiceCollection services, IConfiguration config)
    {
        var options = new LiveVisionOptions(); config.GetSection("LiveVision").Bind(options);
        options.DetectionIntervalMs = Math.Clamp(options.DetectionIntervalMs, 1000, 60000);
        options.GuidanceIntervalMs = Math.Clamp(options.GuidanceIntervalMs, 4000, 120000);
        options.TimeoutSeconds = Math.Clamp(options.TimeoutSeconds, 1, 60);
        services.AddSingleton(options);
        services.AddMemoryCache(); services.AddSingleton<LiveVisionGate>();
        services.AddRateLimiter(o => o.AddPolicy("live-vision", ctx => RateLimitPartition.GetFixedWindowLimiter(
            ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown", _ => new FixedWindowRateLimiterOptions {
                PermitLimit = Math.Clamp(options.RequestsPerMinute, 1, 120), Window = TimeSpan.FromMinutes(1), QueueLimit = 0, AutoReplenishment = true })));
        return services;
    }

    public static IEndpointRouteBuilder MapLiveVision(this IEndpointRouteBuilder app)
    {
        app.MapPost("/v1/inspections/{id}/live/{kind}", async (string id, string kind, HttpContext context, InspectionStore store,
            ISegmenter sam, VisualAssessmentService muse, LiveVisionGate gate, LiveVisionOptions options, TimeProvider clock, CancellationToken ct) =>
        {
            context.Response.Headers.CacheControl = "no-store";
            if (kind is not ("detect" or "guide")) return ApiResults.BadRequest("invalid_live_kind", "Use detect or guide");
            var session = store.Find(id);
            if (session is null) return ApiResults.NotFound("Inspection not found");
            if (session.State.Stage is InspectionStage.VerifiedComplete or InspectionStage.Inconclusive)
                return ApiResults.Conflict("inspection_closed", "Live assistance is available for open inspections");
            var http = context.Request;
            if (!http.HasFormContentType) return ApiResults.BadRequest("Send a frame as multipart/form-data");
            var form = await http.ReadFormAsync(ct);
            var frameId = form["frameId"].ToString();
            if (!Regex.IsMatch(frameId, "^[a-zA-Z0-9_-]{1,80}$")) return ApiResults.BadRequest("invalid_frame_id", "A frameId of up to 80 letters, digits, dashes or underscores is required");
            var file = form.Files.GetFile("image");
            if (file is null || file.Length is <= 0 or > 1048576) return ApiResults.BadRequest("invalid_frame", "Send a JPEG or PNG frame under 1 MiB");
            using var ms = new MemoryStream(); await file.CopyToAsync(ms, ct); var bytes = ms.ToArray();
            var mediaType = EvidenceFileStore.DetectImageType(bytes);
            if (mediaType is null || !EvidenceFileStore.TryReadDimensions(bytes, out var width, out var height))
                return ApiResults.BadRequest("invalid_frame", "Frame must have a readable JPEG or PNG header");
            if (Math.Min(width, height) < 120 || Math.Max(width, height) > 1280)
                return ApiResults.BadRequest("invalid_frame_size", "Preview frame sides must be between 120 and 1280 pixels");
            var interval = kind == "detect" ? options.DetectionIntervalMs : options.GuidanceIntervalMs;
            var key = $"live:{id}:{kind}";
            if (!gate.Enter(key, interval))
            {
                context.Response.Headers.RetryAfter = ((int)Math.Ceiling(interval / 1000d)).ToString();
                return Results.Json(new ApiError("live_busy", "The previous frame is still processing or cooling down"), statusCode: 429);
            }
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(options.TimeoutSeconds));
            var watch = Stopwatch.StartNew();
            try
            {
                if (kind == "detect")
                {
                    // Only site-configured concepts; the preview cannot submit arbitrary prompts or probe targets.
                    var concepts = session.Twin.Twin.VisualRequirements.Where(v => !string.IsNullOrWhiteSpace(v.SegmentationPrompt))
                        .Select(v => v.SegmentationPrompt!).Distinct().Take(2).ToArray();
                    var outcomes = await Task.WhenAll(concepts.Select(c => sam.SegmentAsync(bytes, mediaType, c, timeout.Token)));
                    var objects = outcomes.SelectMany((o, n) => o.Boxes.Where(b => ValidBox(b, width, height)).OrderByDescending(b => b.Confidence ?? 0).Take(2)
                        .Select(b => new { id = $"{n}-{b.ObjectId}", concept = o.Concept, label = o.Concept,
                            box = new { left = b.Left / width, top = b.Top / height, right = b.Right / width, bottom = b.Bottom / height }, b.Confidence })).ToArray();
                    return Results.Ok(new { frameId, width, height, objects, models = outcomes.Select(o => o.ModelId).Distinct(), inferenceMs = watch.ElapsedMilliseconds, refreshAfterMs = interval, source = "AI inference" });
                }
                // A temporary context cites only this frame. It is never inserted into InspectionStore.
                var preview = session.State with { Photos = [new PhotoEvidence(frameId, EvidenceOrigin.Phone, "preview", clock.GetUtcNow())],
                    Stage = InspectionStage.InitialScan, ViewRequests = 0 };
                var outcome = await muse.AssessAsync(preview, session.Twin.Twin, bytes, mediaType, frameId, timeout.Token, livePreview: true);
                var a = outcome.Assessment;
                var features = session.Twin.Twin.VisualRequirements.Select(v => v.RequirementId).ToHashSet();
                if (a is null) return ApiResults.Conflict("provider_unavailable", "Live guidance is unavailable; try again shortly");
                if (!ValidAssessment(a, session.State.AssetId, frameId, features))
                    return ApiResults.Conflict("invalid_model_output", "Live guidance did not match this frame and fixture");
                var recommendation = a.NextAction switch {
                    ProposedNextAction.RequestView => a.RequestedView!,
                    ProposedNextAction.RequestHumanReview => "Keep the connection in view and ask a reviewer to check the conflicting details.",
                    ProposedNextAction.RunApprovedProbe => "Capture this view, then run the approved equipment check.",
                    ProposedNextAction.StopInconclusive => "Improve the lighting and frame the equipment clearly.",
                    _ => "Keep the equipment and connection in view. Capture a clear photo when ready."
                };
                return Results.Ok(new { frameId, recommendation, findings = a.Hypotheses.Select(h => new { h.FeatureId, h.Status, h.Reason }),
                    outcome.ModelId, outcome.PromptDigest, outcome.ResponseDigest, inferenceMs = watch.ElapsedMilliseconds, refreshAfterMs = interval, source = "AI inference" });
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested) { return Results.Json(new ApiError("live_timeout", "Live analysis took too long; hold steady for the next update"), statusCode: 504); }
            catch (Exception ex) when (ex is HttpRequestException or InvalidOperationException or TimeoutException) { return ApiResults.Conflict("provider_unavailable", "Live analysis is unavailable; try again shortly"); }
            finally { gate.Exit(key); }
        }).DisableAntiforgery().RequireRateLimiting("live-vision")
            .WithMetadata(new RequestSizeLimitAttribute(1_100_000), new RequestFormLimitsAttribute { MultipartBodyLengthLimit = 1_100_000, MemoryBufferThreshold = 1_100_000 });
        return app;
    }

    private static bool ValidBox(BoxRecord b, int w, int h) => double.IsFinite(b.Left) && double.IsFinite(b.Top) && double.IsFinite(b.Right) && double.IsFinite(b.Bottom)
        && b.Left >= 0 && b.Top >= 0 && b.Right <= w && b.Bottom <= h && b.Right > b.Left && b.Bottom > b.Top
        && (b.Confidence is null || double.IsFinite(b.Confidence.Value) && b.Confidence >= 0.35 && b.Confidence <= 1);

    private static bool ValidAssessment(VisualAssessment a, string asset, string frame, HashSet<string> features) => a.AssetId == asset
        && Enum.IsDefined(a.NextAction) && a.NextAction != ProposedNextAction.PrepareWorkOrder && a.Hypotheses is { Count: > 0 } && a.Hypotheses.Count <= features.Count
        && a.Hypotheses.All(h => h is not null)
        && a.Hypotheses.Select(h => h.FeatureId).Distinct().Count() == a.Hypotheses.Count
        && a.Hypotheses.All(h => features.Contains(h.FeatureId) && Enum.IsDefined(h.Status) && h.Reason is { Length: > 0 and <= 1000 }
            && h.EvidenceIds is { Count: 1 } && h.EvidenceIds[0] == frame)
        && (a.NextAction != ProposedNextAction.RequestView || a.RequestedView is { Length: > 0 and <= 400 });
}
