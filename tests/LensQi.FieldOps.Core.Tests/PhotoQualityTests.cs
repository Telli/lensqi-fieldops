using System.Text.Json;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Tests;

/// <summary>T6.1: blur and luminance ride on PhotoRecorded as signals; they survive serialization and replay and never move the stage.</summary>
public class PhotoQualityTests
{
    [Fact]
    public void Quality_signals_are_recorded_replayed_and_do_not_change_the_stage()
    {
        var blurry = new InspectionTestKit();
        var sharp = new InspectionTestKit();
        var q = new ImageQualitySignals(3.2, 12.5, "image-quality/1");
        blurry.Session.RecordPhoto("r1", "photo-1", EvidenceOrigin.Phone, "sha", blurry.Now, 1600, 1000, blurry.Now, q);
        sharp.Session.RecordPhoto("r1", "photo-1", EvidenceOrigin.Phone, "sha", sharp.Now, 1600, 1000, sharp.Now);

        Assert.Equal(sharp.Session.State.Stage, blurry.Session.State.Stage);
        Assert.Equal(q, blurry.Session.State.Photos.Single().Quality);
        Assert.Null(sharp.Session.State.Photos.Single().Quality);

        var json = JsonSerializer.Serialize<InspectionEvent>(blurry.Session.Events.OfType<PhotoRecorded>().Single(), KnowledgeJson.Options);
        var back = (PhotoRecorded)JsonSerializer.Deserialize<InspectionEvent>(json, KnowledgeJson.Options)!;
        Assert.Equal(q, back.Quality);
        Assert.Equal(q, InspectionState.Replay(blurry.Session.Events).Photos.Single().Quality);
    }

    [Fact]
    public void Photo_events_written_before_quality_signals_existed_still_load()
    {
        var k = new InspectionTestKit();
        k.Photo();
        var json = JsonSerializer.Serialize<InspectionEvent>(k.Session.Events.OfType<PhotoRecorded>().Single(), KnowledgeJson.Options);
        var legacy = System.Text.Json.Nodes.JsonNode.Parse(json)!.AsObject();
        Assert.True(legacy.Remove("quality"));
        var back = (PhotoRecorded)JsonSerializer.Deserialize<InspectionEvent>(legacy.ToJsonString(), KnowledgeJson.Options)!;
        Assert.Null(back.Quality);
        Assert.Equal(1600, back.Width);
    }
}
