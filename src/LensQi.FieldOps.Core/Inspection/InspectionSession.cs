using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using LensQi.FieldOps.Core.Diagnostics;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Core.Inspection;

/// <summary>
/// The aggregate. Commands validate against the bound twin and current stage, append events, and return them.
/// Every command takes a client request ID; a replayed ID returns the earlier outcome and appends nothing.
/// Only this class changes InspectionStage; no model, client or probe does (design "Trust separation").
/// </summary>
public sealed class InspectionSession
{
    public const int MaxAdditionalViews = 2;

    private readonly List<InspectionEvent> _events;
    private readonly Dictionary<string, object?> _outcomes = new(StringComparer.Ordinal);
    private readonly LoadedExpectedTwin _twin;

    private InspectionSession(LoadedExpectedTwin twin, List<InspectionEvent> events)
    {
        _twin = twin;
        _events = events;
        State = InspectionState.Replay(events);
        // Outcomes come back from the log: each event carries the command that appended it.
        foreach (var batch in events.Where(e => e.CommandId is not null).GroupBy(e => e.CommandId!, StringComparer.Ordinal))
            _outcomes[batch.Key] = (IReadOnlyList<InspectionEvent>)batch.ToList();
    }

    public InspectionState State { get; private set; }
    public IReadOnlyList<InspectionEvent> Events => _events;
    public LoadedExpectedTwin Twin => _twin;

    public static InspectionSession Create(string inspectionId, LoadedExpectedTwin twin, string assetId, DateTimeOffset now, string? requestId = null)
    {
        var invariants = ExpectedTwinInvariants.Check(twin.Twin);
        if (invariants.Count > 0)
            throw new InspectionConflictException(InspectionErrorCodes.FixtureMismatch, string.Join("; ", invariants));
        if (twin.Twin.FindAsset(assetId) is null)
            throw new InspectionConflictException(InspectionErrorCodes.UnknownAsset, $"Asset '{assetId}' is not declared in fixture {twin.Twin.FixtureId}");
        var created = new InspectionCreated(1, inspectionId, now, twin.Sha256, twin.Twin.FixtureId, twin.Twin.FixtureRevision, assetId, requestId);
        return new InspectionSession(twin, [created]);
    }

    public static InspectionSession Rehydrate(LoadedExpectedTwin twin, IEnumerable<InspectionEvent> events) =>
        new(twin, events.ToList());

    // ---------------- commands ----------------

    public IReadOnlyList<InspectionEvent> RecordPhoto(string requestId, string evidenceId, EvidenceOrigin origin, string artifactSha256,
        DateTimeOffset observedAt, int width, int height, DateTimeOffset now, ImageQualitySignals? quality = null, string? perceptualHash = null,
        IReadOnlyList<Tasks.MeasuredPart>? measured = null, string? originalSha256 = null) =>
        Idempotent(requestId, () =>
        {
            Require(State.Stage is not (InspectionStage.VerifiedComplete or InspectionStage.Inconclusive), "Inspection is closed");
            if (State.Photos.Any(p => p.EvidenceId == evidenceId))
                throw new InspectionConflictException(InspectionErrorCodes.DuplicateEvidence, $"Evidence '{evidenceId}' already recorded");
            if (origin is EvidenceOrigin.Muse or EvidenceOrigin.Sam or EvidenceOrigin.LiveLocalProbe or EvidenceOrigin.SimulatedProbe or EvidenceOrigin.ReplayProbe)
                throw new InspectionConflictException(InspectionErrorCodes.InvalidModelOutput, "A photo cannot originate from a model or probe");
            var batch = new List<InspectionEvent> { new PhotoRecorded(Next(), State.InspectionId, now, State.FixtureDigest, evidenceId, origin, artifactSha256, observedAt, width, height, quality, perceptualHash,
                measured is { Count: > 0 } ? measured : null, originalSha256) };
            var target = State.Stage switch
            {
                InspectionStage.Created => InspectionStage.InitialScan,
                InspectionStage.NeedsEvidence when State.RepairClaimedAt is not null => InspectionStage.ReadyToVerify,
                InspectionStage.NeedsEvidence => InspectionStage.InitialScan,
                _ => State.Stage,
            };
            if (target != State.Stage) batch.Add(Stage(target, "photo recorded", now, batch.Count));
            return Commit(batch);
        });

    public IReadOnlyList<InspectionEvent> RecordSegmentation(string requestId, string evidenceId, string sourcePhotoEvidenceId, string concept,
        bool matched, string? maskSha256, string? modelId, DateTimeOffset now, string? geometrySha256 = null) =>
        Idempotent(requestId, () =>
        {
            Require(State.Photos.Any(p => p.EvidenceId == sourcePhotoEvidenceId), $"Photo '{sourcePhotoEvidenceId}' is not part of this inspection");
            if (matched && string.IsNullOrEmpty(maskSha256))
                throw new InspectionConflictException(InspectionErrorCodes.InvalidModelOutput, "A matched segmentation must carry a mask digest");
            if (!matched && maskSha256 is not null)
                throw new InspectionConflictException(InspectionErrorCodes.InvalidModelOutput, "A no-match segmentation cannot carry a mask");
            var batch = new List<InspectionEvent> { new SegmentationRecorded(Next(), State.InspectionId, now, State.FixtureDigest, evidenceId, sourcePhotoEvidenceId, concept, matched, maskSha256, modelId, geometrySha256) };
            // No-match never advances the stage; a mask is an interpretation, not evidence of state.
            if (!matched && State.Stage == InspectionStage.InitialScan) batch.Add(Stage(InspectionStage.NeedsEvidence, "segmentation found no region", now, 1));
            return Commit(batch);
        });

    /// <summary>Whether <see cref="RecordAssessment"/> would accept an assessment now; lets a caller refuse before paying for a model call.</summary>
    public bool AcceptsAssessment => State.Stage is InspectionStage.InitialScan or InspectionStage.NeedsEvidence or InspectionStage.FaultSuspected;

    /// <summary>The events an earlier command with this request ID appended, or null when the ID is new.</summary>
    public IReadOnlyList<InspectionEvent>? Recorded(string requestId) =>
        _outcomes.TryGetValue(requestId, out var earlier) ? (IReadOnlyList<InspectionEvent>?)earlier ?? [] : null;

    public IReadOnlyList<InspectionEvent> RecordAssessment(string requestId, string evidenceId, VisualAssessment assessment, DateTimeOffset now,
        string? promptDigest = null, string? responseDigest = null) =>
        Idempotent(requestId, () =>
        {
            Require(AcceptsAssessment, "Assessment is not accepted in the current stage");
            if (assessment.AssetId != State.AssetId)
                throw new InspectionConflictException(InspectionErrorCodes.InvalidModelOutput, $"Model named asset '{assessment.AssetId}' but the inspection is bound to '{State.AssetId}'");
            if (!Enum.IsDefined(assessment.NextAction))
                throw new InspectionConflictException(InspectionErrorCodes.InvalidModelOutput, "Unknown next action");
            var knownPhotos = State.Photos.Select(p => p.EvidenceId).ToHashSet(StringComparer.Ordinal);
            foreach (var h in assessment.Hypotheses)
            {
                if (string.IsNullOrWhiteSpace(h.FeatureId))
                    throw new InspectionConflictException(InspectionErrorCodes.InvalidModelOutput, "Hypothesis without featureId");
                if (h.EvidenceIds.Any(id => !knownPhotos.Contains(id)))
                    throw new InspectionConflictException(InspectionErrorCodes.InvalidModelOutput, $"Hypothesis '{h.FeatureId}' cites evidence not in this inspection");
            }
            if (assessment.NextAction == ProposedNextAction.RequestView && string.IsNullOrWhiteSpace(assessment.RequestedView))
                throw new InspectionConflictException(InspectionErrorCodes.InvalidModelOutput, "RequestView without a requested view");

            var batch = new List<InspectionEvent> { new AssessmentRecorded(Next(), State.InspectionId, now, State.FixtureDigest, evidenceId, assessment, promptDigest, responseDigest) };
            var anyUnknown = assessment.Hypotheses.Any(h => h.Status is ObservationStatus.Unknown or ObservationStatus.Conflicting);
            var anySuspected = assessment.Hypotheses.Any(h => h.Status is ObservationStatus.Suspected or ObservationStatus.Conflicting);
            InspectionStage? target = assessment.NextAction switch
            {
                ProposedNextAction.RequestView or ProposedNextAction.SegmentRegion => InspectionStage.NeedsEvidence,
                ProposedNextAction.StopInconclusive => InspectionStage.Inconclusive,
                ProposedNextAction.RequestHumanReview => InspectionStage.NeedsEvidence,
                ProposedNextAction.RunApprovedProbe or ProposedNextAction.PrepareWorkOrder => anySuspected ? InspectionStage.FaultSuspected : (anyUnknown ? InspectionStage.NeedsEvidence : State.Stage),
                _ => State.Stage,
            };
            if (assessment.NextAction == ProposedNextAction.RequestView)
            {
                var n = State.ViewRequests + 1;
                if (n > MaxAdditionalViews)
                {
                    batch.Add(Stage(InspectionStage.Inconclusive, $"view budget of {MaxAdditionalViews} exhausted", now, batch.Count));
                    return Commit(batch);
                }
                batch.Add(new ViewRequested(Next() + batch.Count - 1, State.InspectionId, now, State.FixtureDigest, n, assessment.RequestedView!, assessment.Hypotheses.FirstOrDefault(h => h.Status == ObservationStatus.Unknown)?.FeatureId));
            }
            if (target is { } t && t != State.Stage) batch.Add(Stage(t, $"model proposed {assessment.NextAction}", now, batch.Count));
            return Commit(batch);
        });

    public IReadOnlyList<InspectionEvent> DeclineView(string requestId, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            Require(State.Stage == InspectionStage.NeedsEvidence, "No view request is outstanding");
            var batch = new List<InspectionEvent>
            {
                new ViewDeclined(Next(), State.InspectionId, now, State.FixtureDigest, State.ViewRequests),
                Stage(InspectionStage.Inconclusive, "technician declined the requested view", now, 1),
            };
            return Commit(batch);
        });

    public IReadOnlyList<InspectionEvent> RegisterPrediction(string requestId, string predictionId, string diagnosticId, string predictedValue,
        IReadOnlyList<string> basedOnFactIds, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            AllowlistedDiagnostic(diagnosticId);
            if (PredictionIsLate(diagnosticId))
                throw new InspectionConflictException(InspectionErrorCodes.PredictionLate, $"Diagnostic {diagnosticId} already reported since the current claim; a prediction now would not be falsifiable");
            return Commit([NewPrediction(predictionId, diagnosticId, predictedValue, basedOnFactIds, null, now)]);
        });

    /// <summary>Knowledge proposals are refused once the inspection is closed; lets a caller refuse before paying for a model call.</summary>
    public bool AcceptsKnowledgeProposal => State.Stage is not (InspectionStage.VerifiedComplete or InspectionStage.Inconclusive);

    /// <summary>
    /// VK-T06/T25: records one grounded proposal call. <paramref name="proposal"/> is the validator-accepted proposal, or null
    /// with <paramref name="rejections"/> saying why. An accepted RunApprovedProbe registers its prediction in the same batch,
    /// so it is on the log before any probe for it can run; a late prediction turns the proposal into a rejection. Neither
    /// outcome changes the stage: the model never moves the inspection, evidence does.
    /// </summary>
    public IReadOnlyList<InspectionEvent> RecordKnowledgeProposal(string requestId, string proposalId, string modelId, string promptDigest, string responseDigest,
        VendorGroundedProposal? proposal, string? attemptedAction, IReadOnlyList<ProposalRejection> rejections, string predictionId, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            Require(AcceptsKnowledgeProposal, "Knowledge proposals are not accepted in the current stage");
            var refused = proposal is null ? rejections.ToList() : [];
            if (proposal is null && refused.Count == 0)
                refused.Add(new(ProposalErrorCodes.ActionNotAllowed, "No validated proposal was supplied.", []));
            var prediction = proposal?.NextAction == ProposedNextAction.RunApprovedProbe ? proposal.Prediction : null;
            if (prediction is not null)
            {
                if (_twin.Twin.FindDiagnostic(prediction.DiagnosticId) is null)
                    refused.Add(new(ProposalErrorCodes.ProbeNotAllowlisted, $"Diagnostic '{prediction.DiagnosticId}' is not approved by fixture {State.FixtureId}.", [prediction.DiagnosticId]));
                else if (PredictionIsLate(prediction.DiagnosticId))
                    refused.Add(new(InspectionErrorCodes.PredictionLate, $"Diagnostic {prediction.DiagnosticId} already reported since the current claim; the prediction would not be falsifiable.", [prediction.DiagnosticId]));
            }
            if (refused.Count > 0)
                return Commit([new KnowledgeProposalRejected(Next(), State.InspectionId, now, State.FixtureDigest, proposalId, modelId, promptDigest, responseDigest,
                    Clip(proposal?.NextAction.ToString() ?? attemptedAction, 40), refused.Take(10).Select(Bounded).ToList())]);
            var batch = new List<InspectionEvent> { new KnowledgeProposalAccepted(Next(), State.InspectionId, now, State.FixtureDigest, proposalId, modelId, promptDigest, responseDigest, proposal!) };
            if (prediction is not null)
                batch.Add(NewPrediction(predictionId, prediction.DiagnosticId, prediction.PredictedValue, prediction.BasedOnFactIds, proposalId, now));
            return Commit(batch);
        });

    /// <summary>Records a probe result produced by the Diagnostics layer. The caller resolved the alias; the model never chose it.</summary>
    public IReadOnlyList<InspectionEvent> RecordDiagnostic(string requestId, string evidenceId, DiagnosticResult result, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            var diag = AllowlistedDiagnostic(result.DiagnosticId);
            if (result.FixtureId != State.FixtureId)
                throw new InspectionConflictException(InspectionErrorCodes.FixtureMismatch, $"Diagnostic was run for fixture '{result.FixtureId}', not '{State.FixtureId}'");
            if (result.TargetAlias != diag.TargetAlias)
                throw new InspectionConflictException(InspectionErrorCodes.ProbeNotAllowlisted, $"Target alias '{result.TargetAlias}' is not the fixture's alias for {diag.DiagnosticId}");
            if (result.CompletedAt > now.AddSeconds(5))
                throw new InspectionConflictException(InspectionErrorCodes.InvalidModelOutput, "Diagnostic completed in the future");
            Require(State.Stage is not (InspectionStage.VerifiedComplete or InspectionStage.Inconclusive or InspectionStage.Created), "Diagnostics are not accepted in the current stage");

            var batch = new List<InspectionEvent> { new DiagnosticCompleted(Next(), State.InspectionId, now, State.FixtureDigest, evidenceId, result) };
            foreach (var p in State.PendingPredictions.Where(p => p.DiagnosticId == result.DiagnosticId))
                batch.Add(new PredictionScored(Next() + batch.Count - 1, State.InspectionId, now, State.FixtureDigest, p.PredictionId, evidenceId, result.NormalizedValue, PredictionScorer.Score(p.PredictedValue, result)));
            // A failed probe before any repair claim confirms the fault; verification itself is a separate command.
            if (result.Passed == false && State.Stage is InspectionStage.InitialScan or InspectionStage.FaultSuspected or InspectionStage.NeedsEvidence && State.RepairClaimedAt is null)
                batch.Add(Stage(InspectionStage.FaultConfirmed, $"diagnostic {result.DiagnosticId} failed", now, batch.Count));
            return Commit(batch);
        });

    public IReadOnlyList<InspectionEvent> DraftWorkOrder(string requestId, string summary, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            Require(State.Stage is InspectionStage.FaultConfirmed or InspectionStage.FaultSuspected, "A work order needs a suspected or confirmed fault");
            var batch = new List<InspectionEvent>
            {
                new WorkOrderDrafted(Next(), State.InspectionId, now, State.FixtureDigest, summary),
                Stage(InspectionStage.AwaitingRepair, "work order drafted", now, 1),
            };
            return Commit(batch);
        });

    public IReadOnlyList<InspectionEvent> ClaimRepair(string requestId, string claimId, string description, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            Require(State.Stage is InspectionStage.AwaitingRepair or InspectionStage.VerificationFailed or InspectionStage.FaultConfirmed, "A repair claim needs a confirmed fault or a failed verification");
            var batch = new List<InspectionEvent>
            {
                new RepairClaimed(Next(), State.InspectionId, now, State.FixtureDigest, claimId, description),
                Stage(InspectionStage.ReadyToVerify, "technician claimed repair; earlier post-repair evidence invalidated", now, 1),
            };
            return Commit(batch);
        });

    public IReadOnlyList<InspectionEvent> ReviewChecklist(string requestId, string requirementId, ChecklistStatus status, string reviewedByEvidenceId, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            Require(_twin.Twin.VisualRequirements.Any(v => v.RequirementId == requirementId), $"Unknown visual requirement '{requirementId}'");
            Require(State.RepairClaimedAt is not null, "Checklist review applies after a repair claim");
            Require(Enum.IsDefined(status), $"Unknown checklist status '{status}'");
            Require(State.Photos.Any(p => p.EvidenceId == reviewedByEvidenceId), "Review must cite a photo in this inspection");
            if (State.IsAwaitingAnalysis(reviewedByEvidenceId))
                throw new InspectionConflictException(InspectionErrorCodes.ChangeAnalysisPending,
                    $"Photo '{reviewedByEvidenceId}' is still being compared with the earlier view; review it once change analysis is recorded");
            if (State.IsRejectedForCurrentClaim(reviewedByEvidenceId))
                throw new InspectionConflictException(InspectionErrorCodes.ChangeEvidenceRejected,
                    $"Photo '{reviewedByEvidenceId}' was rejected by change analysis ({State.ChangeAnalyses.Last(a => a.PhotoEvidenceId == reviewedByEvidenceId).Verdict}); capture a new post-repair view");
            return Commit([new ChecklistReviewed(Next(), State.InspectionId, now, State.FixtureDigest, requirementId, status, reviewedByEvidenceId)]);
        });

    /// <summary>
    /// Records change evidence for a post-repair photo (child VK-R12). DuplicateImage and NoVisualChange withdraw the photo
    /// as post-repair evidence and return a ReadyToVerify session to NeedsEvidence, so no probe runs on a re-submitted or
    /// unchanged photo. Changed and Unscorable never advance the stage.
    /// </summary>
    public IReadOnlyList<InspectionEvent> RecordVisualChange(string requestId, VisualChangeAnalysis analysis, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            Require(State.CurrentClaimId is not null && analysis.ClaimId == State.CurrentClaimId, "Change analysis must belong to the current repair claim");
            var photo = State.Photos.FirstOrDefault(p => p.EvidenceId == analysis.PhotoEvidenceId);
            Require(photo is not null && State.IsPostClaim(photo), "Change analysis must cite a photo taken after the repair claim");
            Require(photo!.ArtifactSha256 == analysis.AfterImageDigest, "Change analysis digest does not match the photo");
            if (analysis.ReferencePhotoEvidenceId is { } refId)
                Require(State.Photos.Any(p => p.EvidenceId == refId), $"Reference photo '{refId}' is not part of this inspection");
            var batch = new List<InspectionEvent> { new VisualChangeAnalyzed(Next(), State.InspectionId, now, State.FixtureDigest, analysis) };
            if (analysis.RejectsPhoto && State.Stage == InspectionStage.ReadyToVerify)
                batch.Add(Stage(InspectionStage.NeedsEvidence, $"{analysis.Verdict}: {analysis.Reason}", now, 1));
            // An accepted analysis is the evidence arriving, as a new photo is: a verification attempted while it was
            // still pending must not strand the claim.
            else if (!analysis.RejectsPhoto && State.Stage == InspectionStage.NeedsEvidence)
                batch.Add(Stage(InspectionStage.ReadyToVerify, $"change analysis accepted {analysis.PhotoEvidenceId}", now, 1));
            return Commit(batch);
        });

    /// <summary>A reviewer asks for more evidence. Allowed on any open inspection; never changes the stage or a decision.</summary>
    public IReadOnlyList<InspectionEvent> RequestEvidence(string requestId, string note, string requestedBy, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            Require(State.Stage is not (InspectionStage.VerifiedComplete or InspectionStage.Inconclusive), "Inspection is closed");
            Require(!string.IsNullOrWhiteSpace(note), "Say what evidence is needed");
            return Commit([new EvidenceRequested(Next(), State.InspectionId, now, State.FixtureDigest, requestId, note.Trim(), requestedBy)]);
        });

    public IReadOnlyList<InspectionEvent> Acknowledge(string requestId, DateTimeOffset now) =>
        Idempotent(requestId, () =>
        {
            Require(State.CurrentClaimId is not null, "Nothing to acknowledge");
            return Commit([new TechnicianAcknowledged(Next(), State.InspectionId, now, State.FixtureDigest, State.CurrentClaimId!)]);
        });

    /// <summary>Server-owned deterministic gate. Idempotent per request ID: a duplicate returns the same decision, appends nothing.</summary>
    public VerificationDecision Verify(string requestId, DateTimeOffset now)
    {
        if (State.DecisionsByRequest.TryGetValue(requestId, out var earlier)) return earlier;
        Require(State.Stage == InspectionStage.ReadyToVerify, "Verification requires a pending repair claim");
        var decision = VerificationPolicyEvaluator.Evaluate(State, _twin, now);
        var batch = new List<InspectionEvent> { new VerificationEvaluated(Next(), State.InspectionId, now, State.FixtureDigest, requestId, decision) };
        var target = decision.Result switch
        {
            VerificationResult.Verified => InspectionStage.VerifiedComplete,
            VerificationResult.Denied when decision.Reasons.Any(r => r.StartsWith(VerificationPolicyEvaluator.DiagnosticFailedPrefix, StringComparison.Ordinal)) => InspectionStage.VerificationFailed,
            VerificationResult.Denied => InspectionStage.NeedsEvidence,
            _ => InspectionStage.NeedsEvidence,
        };
        batch.Add(Stage(target, decision.Reasons[0], now, 1));
        Commit(batch);
        return decision;
    }

    // ---------------- helpers ----------------

    private TwinDiagnostic AllowlistedDiagnostic(string diagnosticId) =>
        _twin.Twin.FindDiagnostic(diagnosticId)
        ?? throw new InspectionConflictException(InspectionErrorCodes.ProbeNotAllowlisted, $"Diagnostic '{diagnosticId}' is not approved by fixture {State.FixtureId}; no probe was run");

    /// <summary>Late once the probe has reported in the current claim period (or at all, before any claim): its outcome is already known.</summary>
    private bool PredictionIsLate(string diagnosticId) =>
        State.Diagnostics.Any(d => d.Result.DiagnosticId == diagnosticId && (State.RepairClaimedAt is not { } claimed || d.RecordedAt >= claimed));

    private PredictionRegistered NewPrediction(string predictionId, string diagnosticId, string predictedValue, IReadOnlyList<string> basedOnFactIds, string? proposalId, DateTimeOffset now)
    {
        var canonical = JsonSerializer.Serialize(new { diagnosticId, predictedValue, basedOnFactIds }, KnowledgeJson.Options);
        var digest = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(canonical)));
        return new PredictionRegistered(Next(), State.InspectionId, now, State.FixtureDigest, predictionId, diagnosticId, predictedValue, basedOnFactIds, digest, proposalId);
    }

    /// <summary>Model text kept in a rejection is bounded: it is a record of what was refused, not a place to store output.</summary>
    private static ProposalRejection Bounded(ProposalRejection r) =>
        new(r.Code, Clip(r.Message, 300)!, r.OffendingIds.Take(10).Select(id => Clip(id, 120)!).ToList());

    private static string? Clip(string? s, int max) => s is null || s.Length <= max ? s : s[..max];

    private void Require(bool condition, string message)
    {
        if (!condition) throw new InspectionConflictException(InspectionErrorCodes.InvalidState, $"{message} (stage {State.Stage})");
    }

    private long Next() => State.LastSequence + 1;

    private StageChanged Stage(InspectionStage to, string reason, DateTimeOffset now, int offset) =>
        new(Next() + offset, State.InspectionId, now, State.FixtureDigest, State.Stage, to, reason);

    private IReadOnlyList<InspectionEvent> Commit(List<InspectionEvent> batch)
    {
        // Renumber sequentially so helpers can be composed without caring about offsets.
        var seq = State.LastSequence;
        var fixedBatch = batch.Select(e => e with { Sequence = ++seq, CommandId = _commandId }).ToList();
        foreach (var e in fixedBatch)
        {
            _events.Add(e);
            State = State.Apply(e);
        }
        return fixedBatch;
    }

    private string? _commandId;

    private IReadOnlyList<InspectionEvent> Idempotent(string requestId, Func<IReadOnlyList<InspectionEvent>> action)
    {
        if (_outcomes.TryGetValue(requestId, out var earlier))
            return (IReadOnlyList<InspectionEvent>?)earlier ?? [];
        _commandId = requestId;
        try
        {
            var result = action();
            _outcomes[requestId] = result;
            return result;
        }
        finally { _commandId = null; }
    }
}
