using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Tests;

public class RoiCropTests
{
    [Fact]
    public void Pad_expands_by_fraction_with_minimum_and_clamps_to_image()
    {
        var box = new PixelBox(304, 585, 630, 968);
        var padded = RoiCrop.Pad(box, 1600, 1000, 0.25);
        Assert.Equal(new PixelBox(208, 489, 726, 1000), padded);        // 383*0.25 = 95.75 → 96; bottom clamped to 1000
        var tiny = RoiCrop.Pad(new PixelBox(10, 10, 20, 20), 100, 100);
        Assert.Equal(new PixelBox(0, 0, 36, 36), tiny);                   // minimum 16 px pad, clamped at 0
        Assert.True(RoiCrop.Pad(new PixelBox(5, 5, 5, 9), 100, 100).IsEmpty);
    }

    [Fact]
    public void Transform_maps_crop_pixels_back_to_original_at_scale_one()
    {
        var t = new CropTransform("photo-1", "abc", 1600, 1000, new PixelBox(208, 489, 726, 1000), 1.0);
        var (x, y) = RoiCrop.ToOriginal(t, 100, 50);
        Assert.Equal((308.0, 539.0), (x, y));
        Assert.Equal(new PixelBox(303, 585, 631, 969), RoiCrop.FromFloat(303.2, 585.0, 630.4, 968.1, 1600, 1000));
    }
}
