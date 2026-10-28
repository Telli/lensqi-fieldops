using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Meta.Segmentation;

namespace LensQi.FieldOps.Api.Infrastructure.Evidence;

/// <summary>Bound from the "ChangeEvidence" section; the version string travels with every analysis.</summary>
public sealed class ChangeEvidenceOptions
{
    public const string SectionName = "ChangeEvidence";
    public string ThresholdsVersion { get; set; } = VisualChangeThresholds.Default.Version;
    public int DuplicateMaxHashDistance { get; set; } = VisualChangeThresholds.Default.DuplicateMaxHashDistance;
    public int ChangeMinHashDistance { get; set; } = VisualChangeThresholds.Default.ChangeMinHashDistance;
    public double ChangeMaxIoU { get; set; } = VisualChangeThresholds.Default.ChangeMaxIoU;
    public double ChangeMinCentroidShift { get; set; } = VisualChangeThresholds.Default.ChangeMinCentroidShift;
    public int MinMaskPixels { get; set; } = VisualChangeThresholds.Default.MinMaskPixels;

    public VisualChangeThresholds ToThresholds() =>
        new(ThresholdsVersion, DuplicateMaxHashDistance, ChangeMinHashDistance, ChangeMaxIoU, ChangeMinCentroidShift, MinMaskPixels);
}

/// <summary>The SAM call made for a post-repair photo, stored beside the evidence like any other segmentation.</summary>
public sealed record ChangeSegmentation(string SegmentationId, string Concept, bool Matched, string? MaskSha256, string ModelId);

public sealed record ChangeEvidenceResult(VisualChangeAnalysis Analysis, ChangeSegmentation? AfterSegmentation);

/// <summary>
/// Child VK-T26 pipeline for a photo taken after the current repair claim:
/// 1. duplicate check against every earlier photo (byte digest, then dHash distance) — no SAM call;
/// 2. reference = the newest matched pre-claim segmentation; without one the result is Unscorable;
/// 3. one SAM call on the new photo with the reference's concept; masks compared by Core's VisualChangeAnalyzer.
/// At most one SAM call per post-repair photo; the reference mask is reused from the stored segmentation.
/// </summary>
public sealed class ChangeEvidenceService(ISegmenter segmenter, EvidenceFileStore files, VisualChangeThresholds thresholds)
{
    public VisualChangeThresholds Thresholds => thresholds;

    /// <summary>Only photos taken after the current claim are analysed.</summary>
    public static bool Applies(InspectionState state, PhotoEvidence photo) =>
        state.CurrentClaimId is not null && state.RepairClaimedAt is { } at && photo.ObservedAt > at;

    public async Task<ChangeEvidenceResult> AnalyzeAsync(InspectionState state, PhotoEvidence photo, string photoPath, CancellationToken ct)
    {
        var claimId = state.CurrentClaimId!;
        var earlier = state.Photos.Where(p => p.EvidenceId != photo.EvidenceId).ToList();
        if (VisualChangeAnalyzer.FindDuplicate(photo, earlier, thresholds) is { } dup)
            return new(VisualChangeAnalyzer.Duplicate(claimId, photo, dup.EvidenceId, dup.Distance, thresholds), null);

        var claimedAt = state.RepairClaimedAt!.Value;
        var reference = state.Segmentations
            .Where(s => s.Matched && state.Photos.FirstOrDefault(p => p.EvidenceId == s.SourcePhotoEvidenceId)?.ObservedAt <= claimedAt)
            .LastOrDefault();
        if (reference is null)
            return new(VisualChangeAnalyzer.Unscorable(claimId, photo, null, null, "No matched pre-claim segmentation to compare against; manual review applies", thresholds), null);
        var referencePhoto = state.Photos.First(p => p.EvidenceId == reference.SourcePhotoEvidenceId);
        var before = LoadMask(state.InspectionId, reference.EvidenceId);
        if (before is null)
            return new(VisualChangeAnalyzer.Unscorable(claimId, photo, referencePhoto, reference.Concept, $"Reference segmentation {reference.EvidenceId} has no stored mask", thresholds), null);

        SegmentationOutcome outcome;
        try
        {
            var bytes = await File.ReadAllBytesAsync(photoPath, ct);
            outcome = await segmenter.SegmentAsync(bytes, EvidenceFileStore.DetectImageType(bytes) ?? "image/jpeg", reference.Concept, ct);
        }
        catch (Exception ex) when ((ex is HttpRequestException or TaskCanceledException or InvalidOperationException or TimeoutException) && !ct.IsCancellationRequested)
        {
            return new(VisualChangeAnalyzer.Unscorable(claimId, photo, referencePhoto, reference.Concept, "SAM unavailable for change evidence: " + ex.Message, thresholds), null);
        }

        var segId = "seg-" + Guid.NewGuid().ToString("N")[..10];
        var afterMask = outcome.Masks.OrderByDescending(m => m.Confidence ?? 0).FirstOrDefault();
        var afterSha = MaskDigest(outcome.Masks);
        await SaveGeometryAsync(state.InspectionId, segId, photo, outcome, afterSha, ct);
        var analysis = VisualChangeAnalyzer.Compare(claimId, referencePhoto, photo, reference.Concept,
            before, reference.MaskSha256, afterMask is null ? null : Place(afterMask), afterSha, thresholds);
        return new(analysis, new ChangeSegmentation(segId, reference.Concept, outcome.Matched, afterSha, outcome.ModelId));
    }

    /// <summary>Same digest the segment endpoint records: SHA-256 over the masks' RLE counts.</summary>
    public static string? MaskDigest(IReadOnlyList<MaskRecord> masks) =>
        masks.Count == 0 ? null : Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(string.Join("|", masks.Select(m => m.Rle.Counts)))));

    private static PlacedMask Place(MaskRecord m) =>
        new((int)Math.Round(m.Left), (int)Math.Round(m.Top), m.Rle.Size[1], m.Rle.Size[0], m.Rle.Counts);

    private PlacedMask? LoadMask(string inspectionId, string segmentationId)
    {
        var path = Path.Combine(files.DataDirectoryPath, "evidence", inspectionId, segmentationId + ".json");
        if (!File.Exists(path)) return null;
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        if (!doc.RootElement.TryGetProperty("masks", out var masks)) return null;
        PlacedMask? best = null; double bestConf = double.MinValue;
        foreach (var m in masks.EnumerateArray())
        {
            var conf = m.TryGetProperty("confidence", out var c) && c.ValueKind == JsonValueKind.Number ? c.GetDouble() : 0;
            if (best is not null && conf <= bestConf) continue;
            var rle = m.GetProperty("rle");
            var size = rle.GetProperty("size").EnumerateArray().Select(x => x.GetInt32()).ToArray();
            best = new PlacedMask((int)Math.Round(m.GetProperty("left").GetDouble()), (int)Math.Round(m.GetProperty("top").GetDouble()), size[1], size[0], rle.GetProperty("counts").GetString() ?? "");
            bestConf = conf;
        }
        return best;
    }

    private async Task SaveGeometryAsync(string inspectionId, string segId, PhotoEvidence photo, SegmentationOutcome o, string? maskSha, CancellationToken ct)
    {
        var dir = Path.Combine(files.DataDirectoryPath, "evidence", inspectionId);
        Directory.CreateDirectory(dir);
        var geometry = new
        {
            segmentationId = segId, photoEvidenceId = photo.EvidenceId, photoSha256 = photo.ArtifactSha256, concept = o.Concept,
            model = o.ModelId, responseId = o.ResponseId, rawSha256 = o.RawSha256, parser = o.ParserVersion, elapsedSeconds = o.ElapsedSeconds,
            matched = o.Matched, boxes = o.Boxes, masks = o.Masks, maskSha256 = maskSha, crop = (object?)null,
            purpose = "change-evidence", label = "LIVE_META", note = "Boxes and masks are model interpretations of the photo; change metrics are computed by code.",
        };
        await File.WriteAllTextAsync(Path.Combine(dir, segId + ".json"), JsonSerializer.Serialize(geometry, new JsonSerializerOptions(KnowledgeJson.Options) { WriteIndented = true }), ct);
    }
}
