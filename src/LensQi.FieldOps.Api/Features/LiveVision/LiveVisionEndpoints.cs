using System.Diagnostics;
using System.Text.RegularExpressions;
using LensQi.FieldOps.Api.Infrastructure.Evidence;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Core.Tasks;
using LensQi.FieldOps.Api.Infrastructure.Tasks;
using LensQi.FieldOps.Meta.Tasks;
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

    /// <summary>What the latest guidance said to outline, so the next detection looks for it. Expires with the view.</summary>
    public void SetFocus(string inspectionId, IReadOnlyList<string> focus) => cache.Set($"focus:{inspectionId}", focus, TimeSpan.FromSeconds(60));
    public IReadOnlyList<string> Focus(string inspectionId) => cache.TryGetValue($"focus:{inspectionId}", out IReadOnlyList<string>? f) ? f! : [];
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

    /// <summary>A guidance answer in one shape for inspections and tasks.</summary>
    private sealed record Guidance(LiveScene? Scene, string? Error, string ModelId, string PromptDigest, string ResponseDigest);

    public static IEndpointRouteBuilder MapLiveVision(this IEndpointRouteBuilder app)
    {
        app.MapPost("/v1/inspections/{id}/live/{kind}", async (string id, string kind, HttpContext context, InspectionStore store,
            ISegmenter sam, LiveSceneService muse, LiveVisionGate gate, LiveVisionOptions options, CancellationToken ct) =>
        {
            var session = store.Find(id);
            if (session is null) return ApiResults.NotFound("Inspection not found");
            if (session.State.Stage is InspectionStage.VerifiedComplete or InspectionStage.Inconclusive)
                return ApiResults.Conflict("inspection_closed", "Live assistance is available for open inspections");
            // The site's configured parts follow whatever the latest guidance named.
            var parts = session.Twin.Twin.VisualRequirements.Where(v => !string.IsNullOrWhiteSpace(v.SegmentationPrompt)).Select(v => v.SegmentationPrompt!).ToList();
            return await RunLive(context, id, kind, sam, gate, options, focus => [.. focus, .. parts],
                async (bytes, mediaType, token) => { var o = await muse.DescribeAsync(session.State, session.Twin.Twin, bytes, mediaType, token); return new Guidance(o.Scene, o.Error, o.ModelId, o.PromptDigest, o.ResponseDigest); }, ct);
        }).DisableAntiforgery().RequireRateLimiting("live-vision").WithMetadata(FrameLimits);

        app.MapPost("/v1/tasks/{id}/live/{kind}", async (string id, string kind, HttpContext context, TaskStore store,
            ISegmenter sam, StepCoach coach, LiveVisionGate gate, LiveVisionOptions options, CancellationToken ct) =>
        {
            var task = store.Find(id);
            if (task is null) return ApiResults.NotFound("Task not found");
            if (task.State.IsClosed || (task.State.Plan is null && task.State.Mode != TaskMode.Freeform))
                return ApiResults.Conflict("task_not_active", "Live assistance runs on an open task with a plan, or free-form help");
            // The current step's parts lead; what the latest guidance named follows.
            var stepParts = task.State.CurrentStep?.LookFor ?? [];
            return await RunLive(context, id, kind, sam, gate, options, focus => [.. stepParts, .. focus],
                async (bytes, mediaType, token) => { var o = await coach.GuideAsync(task.State, bytes, mediaType, token); return new Guidance(o.Value, o.Error, o.ModelId, o.PromptDigest, o.ResponseDigest); }, ct);
        }).DisableAntiforgery().RequireRateLimiting("live-vision").WithMetadata(FrameLimits);
        return app;
    }

    private static readonly object[] FrameLimits = [new RequestSizeLimitAttribute(1_100_000), new RequestFormLimitsAttribute { MultipartBodyLengthLimit = 1_100_000, MemoryBufferThreshold = 1_100_000 }];

    /// <summary>
    /// One live frame: validated, gated per session and kind, then either outlined by SAM (concepts chosen by the server)
    /// or described by Muse. Never stored, never an event, never a decision.
    /// </summary>
    private static async Task<IResult> RunLive(HttpContext context, string id, string kind, ISegmenter sam, LiveVisionGate gate, LiveVisionOptions options,
        Func<IReadOnlyList<string>, IReadOnlyList<string>> concepts, Func<byte[], string, CancellationToken, Task<Guidance>> guide, CancellationToken ct)
    {
        context.Response.Headers.CacheControl = "no-store";
        if (kind is not ("detect" or "guide")) return ApiResults.BadRequest("invalid_live_kind", "Use detect or guide");
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
                // Prompts never come from the client: the session's parts and the latest validated guidance only.
                var prompts = concepts(gate.Focus(id)).Distinct(StringComparer.OrdinalIgnoreCase).Take(3).ToArray();
                var outcomes = await Task.WhenAll(prompts.Select(c => sam.SegmentAsync(bytes, mediaType, c, timeout.Token)));
                var objects = outcomes.SelectMany((o, n) => o.Boxes.Where(b => ValidBox(b, width, height)).OrderByDescending(b => b.Confidence ?? 0).Take(2)
                    .Select(b => new { id = $"{n}-{b.ObjectId}", concept = o.Concept, label = o.Concept,
                        box = new { left = b.Left / width, top = b.Top / height, right = b.Right / width, bottom = b.Bottom / height }, b.Confidence })).ToArray();
                return Results.Ok(new { frameId, width, height, objects, models = outcomes.Select(o => o.ModelId).Distinct(), inferenceMs = watch.ElapsedMilliseconds, refreshAfterMs = interval, source = "AI inference" });
            }
            var outcome = await guide(bytes, mediaType, timeout.Token);
            if (outcome.Scene is null)
                return outcome.Error?.StartsWith("invalid_model_output") == true || outcome.Error == "empty"
                    ? ApiResults.Conflict("invalid_model_output", "Live guidance was not in the expected form")
                    : ApiResults.Conflict("provider_unavailable", "Live guidance is unavailable; try again shortly");
            var scene = outcome.Scene;
            if (!ValidScene(scene)) return ApiResults.Conflict("invalid_model_output", "Live guidance did not pass validation");
            var focus = scene.Focus.Select(f => f.Trim()).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
            gate.SetFocus(id, focus);
            return Results.Ok(new { frameId, recommendation = scene.Recommendation, seen = scene.Seen, screenText = scene.ScreenText, focus,
                scene.ExpectedEquipmentInView, outcome.ModelId, outcome.PromptDigest, outcome.ResponseDigest, inferenceMs = watch.ElapsedMilliseconds, refreshAfterMs = interval, source = "AI inference" });
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { return Results.Json(new ApiError("live_timeout", "Live analysis took too long; hold steady for the next update"), statusCode: 504); }
        catch (Exception ex) when (ex is HttpRequestException or InvalidOperationException or TimeoutException) { return ApiResults.Conflict("provider_unavailable", "Live analysis is unavailable; try again shortly"); }
        finally { gate.Exit(key); }
    }

    private static bool ValidBox(BoxRecord b, int w, int h) => double.IsFinite(b.Left) && double.IsFinite(b.Top) && double.IsFinite(b.Right) && double.IsFinite(b.Bottom)
        && b.Left >= 0 && b.Top >= 0 && b.Right <= w && b.Bottom <= h && b.Right > b.Left && b.Bottom > b.Top
        && (b.Confidence is null || double.IsFinite(b.Confidence.Value) && b.Confidence >= 0.35 && b.Confidence <= 1);


    /// <summary>Bounded text, no links, and focus terms that are short plain noun phrases (they become SAM prompts).</summary>
    internal static bool ValidScene(LiveScene s) =>
        s.Seen is { Length: > 0 and <= 400 } && s.Recommendation is { Length: > 0 and <= 400 } && s.ScreenText is null or { Length: <= 600 }
        && !$"{s.Seen} {s.Recommendation} {s.ScreenText}".Contains("://", StringComparison.Ordinal)
        && s.Focus is { Count: <= 2 } && s.Focus.All(f => f is not null && TaskPlanValidator.IsConcept(f.Trim()));
}
