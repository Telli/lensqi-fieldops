using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using LensQi.FieldOps.Api.Infrastructure.Evidence;
using LensQi.FieldOps.Api.Infrastructure.Hardening;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Meta.Segmentation;

namespace LensQi.FieldOps.Api.Features.Segmentation;

/// <summary>Parent FR-03 / T1.3: genuine SAM 3.1 segmentation of a photo already in the inspection, a padded ROI crop cut from
/// the original pixels with its transform recorded, and the geometry stored beside the evidence. No match → no mask, stage
/// may only move toward NeedsEvidence.</summary>
public static class SegmentationEndpoints
{
    public sealed record SegmentRequest(string? PhotoEvidenceId, string? Concept);

    public static IEndpointRouteBuilder MapSegmentation(this IEndpointRouteBuilder app)
    {
        app.MapPost("/v1/inspections/{id}/segment", async (string id, SegmentRequest? req, HttpRequest http, InspectionStore store, EvidenceFileStore files, ISegmenter segmenter, IRoiCropper cropper, SamOptions sam, KeyedGate gate, TimeProvider clock, CancellationToken ct) =>
        {
            var session = store.Find(id);
            if (session is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            // A retried key returns the recorded segmentation instead of a second SAM call whose result would not be recorded.
            var key = Features.Inspections.InspectionEndpoints.Key(http);
            using var _ = await gate.EnterAsync(id, key, ct);   // an overlapping retry waits, then replays
            if (key is not null && session.Recorded(key) is { } done)
            {
                var earlier = done.OfType<SegmentationRecorded>().FirstOrDefault();
                var stored = earlier is null ? null : Path.Combine(files.DataDirectoryPath, "evidence", id, earlier.EvidenceId + ".json");
                if (stored is null || !File.Exists(stored)) return ApiResults.Conflict(InspectionErrorCodes.InvalidState, "This Idempotency-Key was used for a different command");
                return Results.Ok(new { segmentation = JsonNode.Parse(await File.ReadAllTextAsync(stored, ct)), state = Features.Inspections.InspectionEndpoints.View(session), replayed = true });
            }
            var photo = req?.PhotoEvidenceId is { } pid ? session.State.Photos.FirstOrDefault(p => p.EvidenceId == pid) : session.State.Photos.LastOrDefault();
            if (photo is null) return ApiResults.Conflict(InspectionErrorCodes.InvalidState, "No photo to segment");

            // Concept: explicit request, else the latest assessment's requested concept, else the first mandatory requirement with a prompt.
            var concept = req?.Concept?.Trim();
            if (string.IsNullOrEmpty(concept)) concept = session.State.Assessments.LastOrDefault()?.RequestedSegmentationConcept;
            if (string.IsNullOrEmpty(concept)) concept = session.Twin.Twin.VisualRequirements.FirstOrDefault(v => v.SegmentationPrompt is not null)?.SegmentationPrompt;
            if (string.IsNullOrEmpty(concept)) return ApiResults.BadRequest("No segmentation concept: pass 'concept' or declare segmentationPrompt on a visual requirement");

            var evidenceDir = Path.Combine(files.DataDirectoryPath, "evidence", id);
            var sourcePath = Directory.Exists(evidenceDir) ? Directory.EnumerateFiles(evidenceDir).FirstOrDefault(f => Path.GetFileName(f).StartsWith(photo.EvidenceId + "-", StringComparison.Ordinal)) : null;
            if (sourcePath is null) return ApiResults.Conflict(InspectionErrorCodes.InvalidState, "Photo artifact missing");
            var bytes = await File.ReadAllBytesAsync(sourcePath, ct);
            var mediaType = EvidenceFileStore.DetectImageType(bytes) ?? "image/jpeg";

            SegmentationOutcome outcome;
            try { outcome = await segmenter.SegmentAsync(bytes, mediaType, concept, ct); }
            catch (Exception ex) when ((ex is HttpRequestException or TaskCanceledException or InvalidOperationException or TimeoutException) && !ct.IsCancellationRequested) { return ApiResults.Conflict("provider_unavailable", ex.Message); }

            var segId = "seg-" + Guid.NewGuid().ToString("N")[..10];
            var maskSha = outcome.Masks.Count > 0 ? Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(string.Join("|", outcome.Masks.Select(m => m.Rle.Counts))))) : null;

            CropResult? crop = null; string? cropName = null; string? cropSha = null;
            if (outcome.Best is { } best)
            {
                var box = RoiCrop.FromFloat(best.Left, best.Top, best.Right, best.Bottom, int.MaxValue, int.MaxValue);
                // Image dimensions come back from the cropper; pad after we know them by letting the sidecar clamp.
                var padded = new PixelBox(Math.Max(0, box.Left - Pad(box, sam)), Math.Max(0, box.Top - Pad(box, sam)), box.Right + Pad(box, sam), box.Bottom + Pad(box, sam));
                cropName = $"{segId}-crop-{photo.EvidenceId}.jpg";
                var outPath = Path.Combine(evidenceDir, cropName);
                try { crop = await cropper.CropAsync(sourcePath, photo.EvidenceId, photo.ArtifactSha256, padded, outPath, ct); }
                catch (Exception ex) when (ex is InvalidOperationException or TimeoutException) { return ApiResults.Conflict("crop_failed", ex.Message); }
                cropSha = Convert.ToHexStringLower(SHA256.HashData(await File.ReadAllBytesAsync(outPath, ct)));
            }

            // The stored pixels' size (a JPEG's before EXIF rotation): the frame every box and mask below is placed in.
            var framed = EvidenceFileStore.TryReadDimensions(bytes, out var photoWidth, out var photoHeight);
            var geometry = new
            {
                segmentationId = segId, photoEvidenceId = photo.EvidenceId, photoSha256 = photo.ArtifactSha256, concept,
                photoWidth = framed ? photoWidth : (int?)null, photoHeight = framed ? photoHeight : (int?)null,
                model = outcome.ModelId, responseId = outcome.ResponseId, rawSha256 = outcome.RawSha256, parser = outcome.ParserVersion, elapsedSeconds = outcome.ElapsedSeconds,
                matched = outcome.Matched, boxes = outcome.Boxes, masks = outcome.Masks, maskSha256 = maskSha,
                crop = crop is null ? null : new { file = cropName, sha256 = cropSha, transform = crop.Transform },
                label = "LIVE_META", note = "Boxes and masks are model interpretations of the photo; a crop is not independent evidence.",
            };
            var geometryBytes = JsonSerializer.SerializeToUtf8Bytes(geometry, new JsonSerializerOptions(KnowledgeJson.Options) { WriteIndented = true });
            await File.WriteAllBytesAsync(Path.Combine(evidenceDir, segId + ".json"), geometryBytes, ct);
            var geometrySha = Convert.ToHexStringLower(SHA256.HashData(geometryBytes));

            var requestId = key ?? "segment:" + segId;
            try
            {
                return await store.ExecuteAsync(id, s =>
                {
                    s.RecordSegmentation(requestId, segId, photo.EvidenceId, concept, outcome.Matched, maskSha, outcome.ModelId, clock.GetUtcNow(), geometrySha);
                    return Task.FromResult(Results.Ok(new { segmentation = geometry, state = Features.Inspections.InspectionEndpoints.View(s), replayed = false }));
                }, ct);
            }
            catch (InspectionConflictException ex) { return ApiResults.Conflict(ex.Code, ex.Message); }
        }).AsModelCall();

        // Serves evidence artifacts (photos, crops) and segmentation geometry for the UI and the receipt viewer. Read-only.
        app.MapGet("/v1/inspections/{id}/artifacts/{name}", (string id, string name, InspectionStore store, EvidenceFileStore files) =>
        {
            if (store.Find(id) is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            if (name.Contains("..") || name.Contains('/') || name.Contains('\\')) return ApiResults.BadRequest("invalid artifact name");
            var path = Path.Combine(files.DataDirectoryPath, "evidence", id, name);
            if (!File.Exists(path)) return ApiResults.NotFound("artifact not found");
            var bytes = File.ReadAllBytes(path);
            var type = name.EndsWith(".json", StringComparison.OrdinalIgnoreCase) ? "application/json" : EvidenceFileStore.DetectImageType(bytes) ?? "application/octet-stream";
            return Results.File(bytes, type);
        });

        app.MapGet("/v1/inspections/{id}/artifacts", (string id, InspectionStore store, EvidenceFileStore files) =>
        {
            if (store.Find(id) is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            var dir = Path.Combine(files.DataDirectoryPath, "evidence", id);
            var names = Directory.Exists(dir) ? Directory.EnumerateFiles(dir).Select(Path.GetFileName).OrderBy(n => n, StringComparer.Ordinal).ToList() : [];
            return Results.Ok(names);
        });
        return app;
    }

    private static int Pad(PixelBox box, SamOptions sam) => (int)Math.Ceiling(Math.Max(16, Math.Max(box.Width, box.Height) * sam.CropPadFraction));
}
