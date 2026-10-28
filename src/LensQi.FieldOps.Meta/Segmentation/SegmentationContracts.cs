using System.Text.Json.Serialization;
using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Meta.Segmentation;

public sealed class SamOptions
{
    public const string SectionName = "Sam";
    public string BaseUrl { get; set; } = "https://api.meta.ai/v1/";
    public string Model { get; set; } = "sam-3.1";
    public string MaskEncoding { get; set; } = "one_bit";
    public bool IncludeConfidence { get; set; } = true;
    public int TimeoutSeconds { get; set; } = 60;
    /// <summary>Python executable with meta-sam-parser and Pillow installed. FIELDOPS_PYTHON overrides; a repo-root .venv is auto-detected.</summary>
    public string PythonExecutable { get; set; } = "python3";
    public string SidecarDirectory { get; set; } = "tools/sam-parser";
    public double CropPadFraction { get; set; } = 0.25;
}

/// <summary>COCO-style compressed RLE as emitted by meta-sam-parser: size [height, width], column-major counts.</summary>
public sealed record RleMask([property: JsonPropertyName("size")] IReadOnlyList<int> Size, [property: JsonPropertyName("counts")] string Counts);

public sealed record MaskRecord(string ObjectId, int Width, int Height, double Left, double Top, double Right, double Bottom, RleMask Rle, double? Confidence);

public sealed record BoxRecord(string ObjectId, double Left, double Top, double Right, double Bottom, double? Confidence);

/// <summary>Typed, normalized output of one SAM call. Boxes are half-open original-image pixels (parser convention).</summary>
public sealed record SegmentationOutcome(
    string Concept,
    string ModelId,
    string? ResponseId,
    string RawOutputText,
    string RawSha256,
    IReadOnlyList<BoxRecord> Boxes,
    IReadOnlyList<MaskRecord> Masks,
    string ParserVersion,
    double ElapsedSeconds)
{
    public bool Matched => Boxes.Count > 0;
    public BoxRecord? Best => Boxes.OrderByDescending(b => b.Confidence ?? 0).ThenBy(b => b.ObjectId, StringComparer.Ordinal).FirstOrDefault();
}

public interface ISegmenter
{
    /// <summary>One concept per request (parent FR-03). Never fabricates: an empty output is an honest no-match.</summary>
    Task<SegmentationOutcome> SegmentAsync(byte[] image, string mediaType, string concept, CancellationToken ct);
}

public sealed record CropResult(string Path, CropTransform Transform);

public interface IRoiCropper
{
    Task<CropResult> CropAsync(string sourcePath, string sourceEvidenceId, string sourceSha256, PixelBox box, string outputPath, CancellationToken ct);
}
