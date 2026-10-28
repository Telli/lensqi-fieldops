using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Core.Inspection;

public sealed record PhotoEvidence(string EvidenceId, EvidenceOrigin Origin, string ArtifactSha256, DateTimeOffset ObservedAt, ImageQualitySignals? Quality = null, string? PerceptualHash = null);
public sealed record DiagnosticEvidence(string EvidenceId, DiagnosticResult Result, DateTimeOffset RecordedAt);
public sealed record ChecklistEntry(string RequirementId, ChecklistStatus Status, DateTimeOffset ReviewedAt, string? ReviewedByEvidenceId = null);
public sealed record SegmentationEvidence(string EvidenceId, string SourcePhotoEvidenceId, string Concept, bool Matched, string? MaskSha256, DateTimeOffset RecordedAt);
public sealed record PendingPrediction(string PredictionId, string DiagnosticId, string PredictedValue, DateTimeOffset RegisteredAt);

/// <summary>Materialized view of an inspection, rebuilt by replaying the event list. Never mutated directly.</summary>
public sealed record InspectionState
{
    public required string InspectionId { get; init; }
    public required string FixtureId { get; init; }
    public required int FixtureRevision { get; init; }
    public required string FixtureDigest { get; init; }
    public required string AssetId { get; init; }
    public InspectionStage Stage { get; init; } = InspectionStage.Created;
    public long LastSequence { get; init; }
    public IReadOnlyList<PhotoEvidence> Photos { get; init; } = [];
    public IReadOnlyList<DiagnosticEvidence> Diagnostics { get; init; } = [];
    public IReadOnlyList<VisualAssessment> Assessments { get; init; } = [];
    public IReadOnlyList<SegmentationEvidence> Segmentations { get; init; } = [];
    public IReadOnlyList<VisualChangeAnalysis> ChangeAnalyses { get; init; } = [];
    public IReadOnlyDictionary<string, ChecklistEntry> Checklist { get; init; } = new Dictionary<string, ChecklistEntry>();
    public IReadOnlyList<PendingPrediction> PendingPredictions { get; init; } = [];
    public IReadOnlyList<PredictionScored> PredictionScores { get; init; } = [];
    public int ViewRequests { get; init; }
    public string? CurrentClaimId { get; init; }
    public DateTimeOffset? RepairClaimedAt { get; init; }
    public bool TechnicianAcknowledged { get; init; }
    public VerificationDecision? LastDecision { get; init; }
    public IReadOnlyDictionary<string, VerificationDecision> DecisionsByRequest { get; init; } = new Dictionary<string, VerificationDecision>();

    public static InspectionState Replay(IEnumerable<InspectionEvent> events)
    {
        InspectionState? s = null;
        foreach (var e in events)
            s = s is null ? Start(e) : s.Apply(e);
        return s ?? throw new InvalidOperationException("No events to replay.");
    }

    private static InspectionState Start(InspectionEvent e)
    {
        if (e is not InspectionCreated c)
            throw new InvalidOperationException($"First event must be InspectionCreated, got {e.EventKind}.");
        return new InspectionState
        {
            InspectionId = c.InspectionId, FixtureId = c.FixtureId, FixtureRevision = c.FixtureRevision,
            FixtureDigest = c.FixtureDigest, AssetId = c.AssetId, LastSequence = c.Sequence,
        };
    }

    public InspectionState Apply(InspectionEvent e)
    {
        var next = e switch
        {
            PhotoRecorded p => this with { Photos = [.. Photos, new PhotoEvidence(p.EvidenceId, p.Origin, p.ArtifactSha256, p.ObservedAt, p.Quality, p.PerceptualHash)] },
            SegmentationRecorded sr => this with { Segmentations = [.. Segmentations, new SegmentationEvidence(sr.EvidenceId, sr.SourcePhotoEvidenceId, sr.Concept, sr.Matched, sr.MaskSha256, sr.OccurredAt)] },
            VisualChangeAnalyzed vc => this with { ChangeAnalyses = [.. ChangeAnalyses, vc.Analysis] },
            AssessmentRecorded a => this with { Assessments = [.. Assessments, a.Assessment] },
            ViewRequested v => this with { ViewRequests = v.RequestNumber },
            PredictionRegistered pr => this with { PendingPredictions = [.. PendingPredictions, new PendingPrediction(pr.PredictionId, pr.DiagnosticId, pr.PredictedValue, pr.OccurredAt)] },
            DiagnosticCompleted d => this with { Diagnostics = [.. Diagnostics, new DiagnosticEvidence(d.EvidenceId, d.Result, d.OccurredAt)] },
            PredictionScored ps => this with
            {
                PredictionScores = [.. PredictionScores, ps],
                PendingPredictions = PendingPredictions.Where(p => p.PredictionId != ps.PredictionId).ToList(),
            },
            RepairClaimed r => this with
            {
                CurrentClaimId = r.ClaimId, RepairClaimedAt = r.OccurredAt, TechnicianAcknowledged = false,
                Checklist = new Dictionary<string, ChecklistEntry>(), // a new claim invalidates earlier post-repair review
            },
            ChecklistReviewed c => this with { Checklist = new Dictionary<string, ChecklistEntry>(Checklist) { [c.RequirementId] = new(c.RequirementId, c.Status, c.OccurredAt, c.ReviewedByEvidenceId) } },
            TechnicianAcknowledged t => this with { TechnicianAcknowledged = t.ClaimId == CurrentClaimId },
            VerificationEvaluated v => this with
            {
                LastDecision = v.Decision,
                DecisionsByRequest = new Dictionary<string, VerificationDecision>(DecisionsByRequest) { [v.RequestId] = v.Decision },
            },
            StageChanged sc => this with { Stage = sc.To },
            _ => this,
        };
        return next with { LastSequence = e.Sequence };
    }

    /// <summary>Newest photo taken after the current claim that change analysis has not rejected (DuplicateImage, NoVisualChange).</summary>
    public PhotoEvidence? LatestPostRepairPhoto =>
        RepairClaimedAt is null ? null : Photos.Where(p => p.ObservedAt > RepairClaimedAt && !IsRejectedForCurrentClaim(p.EvidenceId)).OrderByDescending(p => p.ObservedAt).FirstOrDefault();

    /// <summary>The latest change analysis of this photo under the current claim rejected it as post-repair evidence.</summary>
    public bool IsRejectedForCurrentClaim(string photoEvidenceId) =>
        CurrentClaimId is not null && ChangeAnalyses.LastOrDefault(a => a.ClaimId == CurrentClaimId && a.PhotoEvidenceId == photoEvidenceId)?.RejectsPhoto == true;

    public DiagnosticEvidence? LatestDiagnosticFor(string diagnosticId) =>
        Diagnostics.Where(d => d.Result.DiagnosticId == diagnosticId).OrderByDescending(d => d.Result.CompletedAt).FirstOrDefault();
}
