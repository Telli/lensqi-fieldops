using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Tests;

/// <summary>A reviewer's evidence request is a note on the record: it changes no stage, the next photo answers it.</summary>
public sealed class EvidenceRequestTests
{
    [Fact]
    public void Request_is_recorded_without_changing_the_stage_and_the_next_photo_answers_it()
    {
        var k = new InspectionTestKit();
        var first = k.Photo();
        var stage = k.Session.State.Stage;
        k.Tick(); k.Session.RequestEvidence("rq-1", "  Show the label on port 2  ", "Operations desk", k.Now);

        var open = Assert.Single(k.Session.State.EvidenceRequests);
        Assert.Equal("Show the label on port 2", open.Note);
        Assert.Null(open.AnsweredByPhotoId);
        Assert.Equal(stage, k.Session.State.Stage);

        k.Tick(); var answer = k.Photo();
        var answered = Assert.Single(k.Session.State.EvidenceRequests);
        Assert.Equal(answer, answered.AnsweredByPhotoId);
        Assert.NotEqual(first, answered.AnsweredByPhotoId);

        var replayed = InspectionState.Replay(k.Session.Events);
        Assert.Equal(k.Session.State.EvidenceRequests, replayed.EvidenceRequests);
    }

    [Fact]
    public void Same_request_id_appends_once()
    {
        var k = new InspectionTestKit();
        k.Photo();
        k.Session.RequestEvidence("rq-1", "Closer view", "Operations desk", k.Now);
        var events = k.Session.Events.Count;
        k.Session.RequestEvidence("rq-1", "Closer view", "Operations desk", k.Now);
        Assert.Equal(events, k.Session.Events.Count);
        Assert.Single(k.Session.State.EvidenceRequests);
    }

    [Fact]
    public void Closed_inspection_and_empty_note_are_refused()
    {
        var k = new InspectionTestKit();
        Assert.Throws<InspectionConflictException>(() => k.Session.RequestEvidence("rq-0", "   ", "Operations desk", k.Now));
        k.ReachRepairClaim(); k.PostRepairEvidence(); k.Tick(); k.RecordProbe(k.Probe(true));
        Assert.Equal(VerificationResult.Verified, k.Verify().Result);
        Assert.Throws<InspectionConflictException>(() => k.Session.RequestEvidence("rq-1", "One more photo", "Operations desk", k.Now));
    }
}
