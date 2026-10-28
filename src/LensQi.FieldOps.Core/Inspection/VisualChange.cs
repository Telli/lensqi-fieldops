using System.Numerics;

namespace LensQi.FieldOps.Core.Inspection;

/// <summary>Outcome of comparing a post-repair photo with the inspection's earlier evidence (child VK-R12).</summary>
public enum VisualChangeVerdict { Changed, NoVisualChange, DuplicateImage, Unscorable }

/// <summary>
/// Server-configured thresholds. The version string is recorded in every analysis so a receipt can be re-evaluated
/// under the same rules. Distances are Hamming distances between 64-bit dHashes; IoU and shift are over target masks.
/// </summary>
public sealed record VisualChangeThresholds(
    string Version,
    int DuplicateMaxHashDistance,
    int ChangeMinHashDistance,
    double ChangeMaxIoU,
    double ChangeMinCentroidShift,
    int MinMaskPixels)
{
    public static VisualChangeThresholds Default { get; } = new("visual-change/1", 3, 12, 0.80, 0.10, 200);
}

/// <summary>A target mask from SAM placed in original-image pixels: box-local COCO RLE plus the box's top-left corner.</summary>
public sealed record PlacedMask(int Left, int Top, int Width, int Height, string Counts);

/// <summary>Deterministic metrics and verdict for one post-repair photo. Computed by code; no model sees or sets them.</summary>
public sealed record VisualChangeAnalysis(
    string ClaimId,
    string PhotoEvidenceId,
    string AfterImageDigest,
    string? ReferencePhotoEvidenceId,
    string? BeforeImageDigest,
    string? TargetPrompt,
    string? BeforeMaskDigest,
    string? AfterMaskDigest,
    int? PerceptualHashDistance,
    string? DuplicateOfEvidenceId,
    double? TargetRegionIoU,
    double? ComponentShiftNormalized,
    VisualChangeVerdict Verdict,
    string Reason,
    string ThresholdsVersion)
{
    /// <summary>DuplicateImage and NoVisualChange leave the post-repair visual gate unsatisfied for the claim.</summary>
    public bool RejectsPhoto => Verdict is VisualChangeVerdict.DuplicateImage or VisualChangeVerdict.NoVisualChange;
}

/// <summary>
/// Pure change metrics (child design §6b). The Python sidecar measures each image's dHash; this class compares hashes,
/// decodes masks, and decides. A Changed verdict only says something in the target region changed; it never verifies
/// a repair and never substitutes for the diagnostic.
/// </summary>
public static class VisualChangeAnalyzer
{
    public static int HammingDistance(string hexA, string hexB) =>
        BitOperations.PopCount(Convert.ToUInt64(hexA, 16) ^ Convert.ToUInt64(hexB, 16));

    /// <summary>The closest earlier photo within the duplicate threshold, byte-identical photos first.</summary>
    public static (string EvidenceId, int Distance)? FindDuplicate(PhotoEvidence photo, IEnumerable<PhotoEvidence> earlier, VisualChangeThresholds t)
    {
        (string, int)? best = null;
        foreach (var p in earlier)
        {
            if (p.EvidenceId == photo.EvidenceId) continue;
            int? d = p.ArtifactSha256 == photo.ArtifactSha256 ? 0
                : p.PerceptualHash is { } a && photo.PerceptualHash is { } b ? HammingDistance(a, b) : null;
            if (d is { } dist && dist <= t.DuplicateMaxHashDistance && (best is null || dist < best.Value.Item2)) best = (p.EvidenceId, dist);
        }
        return best;
    }

    public static VisualChangeAnalysis Duplicate(string claimId, PhotoEvidence photo, string duplicateOf, int distance, VisualChangeThresholds t) =>
        new(claimId, photo.EvidenceId, photo.ArtifactSha256, duplicateOf, null, null, null, null, distance, duplicateOf, null, null,
            VisualChangeVerdict.DuplicateImage,
            distance == 0 ? $"Post-repair photo is the same image as {duplicateOf}" : $"Post-repair photo is perceptually identical to {duplicateOf} (dHash distance {distance} ≤ {t.DuplicateMaxHashDistance})",
            t.Version);

    public static VisualChangeAnalysis Unscorable(string claimId, PhotoEvidence photo, PhotoEvidence? reference, string? prompt, string reason, VisualChangeThresholds t) =>
        new(claimId, photo.EvidenceId, photo.ArtifactSha256, reference?.EvidenceId, reference?.ArtifactSha256, prompt, null, null,
            reference?.PerceptualHash is { } a && photo.PerceptualHash is { } b ? HammingDistance(a, b) : null, null, null, null,
            VisualChangeVerdict.Unscorable, reason, t.Version);

    /// <summary>Compares the target masks of the reference (pre-claim) and post-repair photos.</summary>
    public static VisualChangeAnalysis Compare(string claimId, PhotoEvidence reference, PhotoEvidence photo, string prompt,
        PlacedMask? before, string? beforeDigest, PlacedMask? after, string? afterDigest, VisualChangeThresholds t)
    {
        int? hash = reference.PerceptualHash is { } a && photo.PerceptualHash is { } b ? HammingDistance(a, b) : null;
        var beforePx = before is null ? null : Pixels(before);
        var afterPx = after is null ? null : Pixels(after);
        if (beforePx is null || afterPx is null || beforePx.Count < t.MinMaskPixels || afterPx.Count < t.MinMaskPixels)
        {
            var which = beforePx is null || beforePx.Count < t.MinMaskPixels ? "reference" : "post-repair";
            return new(claimId, photo.EvidenceId, photo.ArtifactSha256, reference.EvidenceId, reference.ArtifactSha256, prompt, beforeDigest, afterDigest,
                hash, null, null, null, VisualChangeVerdict.Unscorable, $"No usable '{prompt}' mask in the {which} photo; manual review applies", t.Version);
        }

        var inter = beforePx.Count < afterPx.Count ? beforePx.Count(afterPx.Contains) : afterPx.Count(beforePx.Contains);
        var iou = (double)inter / (beforePx.Count + afterPx.Count - inter);
        var (bx, by) = Centroid(beforePx);
        var (ax, ay) = Centroid(afterPx);
        var diagonal = Math.Sqrt((double)before!.Width * before.Width + (double)before.Height * before.Height);
        var shift = Math.Sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by)) / diagonal;

        var reasons = new List<string>();
        if (hash >= t.ChangeMinHashDistance) reasons.Add($"image dHash distance {hash} ≥ {t.ChangeMinHashDistance}");
        if (iou <= t.ChangeMaxIoU) reasons.Add($"'{prompt}' mask IoU {iou:0.000} ≤ {t.ChangeMaxIoU}");
        if (shift >= t.ChangeMinCentroidShift) reasons.Add($"'{prompt}' centroid moved {shift:0.000} of its box diagonal ≥ {t.ChangeMinCentroidShift}");
        var verdict = reasons.Count > 0 ? VisualChangeVerdict.Changed : VisualChangeVerdict.NoVisualChange;
        var reason = verdict == VisualChangeVerdict.Changed
            ? "Visual change in the target region: " + string.Join("; ", reasons)
            : $"No visual change in '{prompt}': IoU {iou:0.000}, shift {shift:0.000}, dHash distance {hash?.ToString() ?? "n/a"}";
        return new(claimId, photo.EvidenceId, photo.ArtifactSha256, reference.EvidenceId, reference.ArtifactSha256, prompt, beforeDigest, afterDigest,
            hash, null, Math.Round(iou, 4), Math.Round(shift, 4), verdict, reason, t.Version);
    }

    /// <summary>Object pixels of a placed mask as packed image coordinates (y * 2^20 + x).</summary>
    public static HashSet<long> Pixels(PlacedMask m)
    {
        var bits = CocoRle.Decode(m.Counts, m.Height, m.Width);
        var set = new HashSet<long>();
        for (var i = 0; i < bits.Length; i++)
            if (bits[i]) set.Add(((long)(m.Top + i / m.Width) << 20) | (uint)(m.Left + i % m.Width));
        return set;
    }

    private static (double X, double Y) Centroid(HashSet<long> px)
    {
        double sx = 0, sy = 0;
        foreach (var p in px) { sx += p & 0xFFFFF; sy += p >> 20; }
        return (sx / px.Count, sy / px.Count);
    }
}

/// <summary>
/// COCO compressed RLE as emitted by meta-sam-parser: size [height, width], runs alternate 0 and 1 in column-major
/// order, each run 6-bit characters offset by 48, delta-coded against the run two back. Reimplemented from the format.
/// </summary>
public static class CocoRle
{
    public static IReadOnlyList<int> Counts(string encoded)
    {
        var runs = new List<int>();
        var p = 0;
        while (p < encoded.Length)
        {
            long x = 0; var k = 0; var more = true;
            while (more)
            {
                var c = encoded[p] - 48;
                x |= (long)(c & 0x1f) << (5 * k);
                more = (c & 0x20) != 0;
                p++; k++;
                if (!more && (c & 0x10) != 0) x |= -1L << (5 * k);
            }
            if (runs.Count > 2) x += runs[^2];
            runs.Add((int)x);
        }
        return runs;
    }

    /// <summary>Row-major mask of height*width: true where the object is.</summary>
    public static bool[] Decode(string encoded, int height, int width)
    {
        var columnMajor = new bool[height * width];
        int pos = 0; var value = false;
        foreach (var run in Counts(encoded))
        {
            var end = Math.Min(pos + run, columnMajor.Length);
            if (value) Array.Fill(columnMajor, true, pos, end - pos);
            pos = end; value = !value;
        }
        var rowMajor = new bool[height * width];
        for (var i = 0; i < rowMajor.Length; i++) rowMajor[i] = columnMajor[(i % width) * height + i / width];
        return rowMajor;
    }

    /// <summary>Inverse of <see cref="Counts"/>; used by tests to build masks.</summary>
    public static string Encode(IReadOnlyList<int> runs)
    {
        var sb = new System.Text.StringBuilder();
        for (var i = 0; i < runs.Count; i++)
        {
            long x = runs[i];
            if (i > 2) x -= runs[i - 2];
            var more = true;
            while (more)
            {
                var c = (int)(x & 0x1f);
                x >>= 5;
                more = (c & 0x10) != 0 ? x != -1 : x != 0;
                if (more) c |= 0x20;
                sb.Append((char)(c + 48));
            }
        }
        return sb.ToString();
    }
}
