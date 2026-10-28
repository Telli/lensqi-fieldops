namespace LensQi.FieldOps.Core.Inspection;

/// <summary>Half-open box in original-image pixels.</summary>
public sealed record PixelBox(int Left, int Top, int Right, int Bottom)
{
    public int Width => Right - Left;
    public int Height => Bottom - Top;
    public bool IsEmpty => Width <= 0 || Height <= 0;
}

/// <summary>
/// The transform from a crop back to the original image (parent FR-03, R5.3): crop pixel (x, y) maps to original
/// (x + OffsetX, y + OffsetY) at scale 1. Recorded with every crop so the crop never becomes "new" evidence.
/// </summary>
public sealed record CropTransform(string SourceEvidenceId, string SourceSha256, int SourceWidth, int SourceHeight, PixelBox Crop, double Scale)
{
    public int OffsetX => Crop.Left;
    public int OffsetY => Crop.Top;
}

public static class RoiCrop
{
    /// <summary>Pads a detected box by a fraction of its larger side (min 16 px), clamps to the image, and rounds outward.</summary>
    public static PixelBox Pad(PixelBox box, int imageWidth, int imageHeight, double padFraction = 0.25)
    {
        if (box.IsEmpty) return box;
        var pad = (int)Math.Ceiling(Math.Max(16, Math.Max(box.Width, box.Height) * padFraction));
        return new PixelBox(
            Math.Max(0, box.Left - pad),
            Math.Max(0, box.Top - pad),
            Math.Min(imageWidth, box.Right + pad),
            Math.Min(imageHeight, box.Bottom + pad));
    }

    /// <summary>Converts a point in crop pixels back to original-image pixels.</summary>
    public static (double X, double Y) ToOriginal(CropTransform t, double cropX, double cropY) =>
        (cropX / t.Scale + t.OffsetX, cropY / t.Scale + t.OffsetY);

    /// <summary>Rounds a parser box (floating, half-open) to integer pixels, clamped to the image.</summary>
    public static PixelBox FromFloat(double left, double top, double right, double bottom, int imageWidth, int imageHeight) =>
        new(Math.Clamp((int)Math.Floor(left), 0, imageWidth), Math.Clamp((int)Math.Floor(top), 0, imageHeight),
            Math.Clamp((int)Math.Ceiling(right), 0, imageWidth), Math.Clamp((int)Math.Ceiling(bottom), 0, imageHeight));
}
