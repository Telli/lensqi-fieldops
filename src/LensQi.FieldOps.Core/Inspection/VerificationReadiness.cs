namespace LensQi.FieldOps.Core.Inspection;

/// <summary>What the technician should do next to reach a verification decision.</summary>
public enum NextVerifyAction { None, ClaimRepair, CapturePhoto, WaitForAnalysis, ReviewChecklist, Acknowledge, Verify }

/// <summary>
/// Whether verification can run now, the next step towards it and every reason it cannot. <see cref="Blocking"/> holds
/// the policy's own wording, so it matches the reasons a premature verify would record.
/// </summary>
public sealed record VerifyReadiness(bool Ready, NextVerifyAction Next, IReadOnlyList<string> Blocking);

/// <summary>
/// Derives readiness from the stage and <see cref="VerificationPolicyEvaluator.Blockers"/>, so clients show the server's
/// gate instead of rebuilding it. The policy checks the acknowledgement first; the next action follows the order the
/// work is done in (photo, analysis, review, acknowledgement), because a new photo or review clears an acknowledgement.
/// Ready never means verified: the fixture's diagnostics still run, and decide, when verify is called.
/// </summary>
public static class VerificationReadiness
{
    public static VerifyReadiness Of(InspectionState state, LoadedExpectedTwin loaded)
    {
        switch (state.Stage)
        {
            case InspectionStage.VerifiedComplete or InspectionStage.Inconclusive:
                return new(false, NextVerifyAction.None, [$"The inspection is closed ({state.Stage})"]);
            case InspectionStage.AwaitingRepair or InspectionStage.FaultConfirmed or InspectionStage.VerificationFailed:
                return new(false, NextVerifyAction.ClaimRepair, [state.Stage == InspectionStage.VerificationFailed
                    ? "Verification failed; record a new repair claim after fixing it again"
                    : "No repair claim has been recorded"]);
            case not (InspectionStage.ReadyToVerify or InspectionStage.NeedsEvidence):
                return new(false, NextVerifyAction.None, [$"Verification does not apply in stage {state.Stage}"]);
        }

        var blockers = VerificationPolicyEvaluator.Blockers(state, loaded).ToList();
        var reasons = blockers.Select(b => b.Decision.Reasons[0]).ToList();
        bool Has(params VerifyBlocker[] kinds) => blockers.Any(b => kinds.Contains(b.Blocker));

        if (Has(VerifyBlocker.FixtureMismatch, VerifyBlocker.NoClaim)) return new(false, NextVerifyAction.None, reasons);
        if (Has(VerifyBlocker.PhotoBeingCompared)) return new(false, NextVerifyAction.WaitForAnalysis, reasons);
        // NeedsEvidence with a claim: a photo was rejected or a decision sent the claim back; only a new photo reopens it.
        if (Has(VerifyBlocker.PhotoRequired) || state.Stage == InspectionStage.NeedsEvidence)
        {
            if (state.Stage == InspectionStage.NeedsEvidence && !Has(VerifyBlocker.PhotoRequired))
                reasons.Insert(0, state.LastDecision is { } d && d.Reasons.Count > 0
                    ? $"The last check sent the claim back for a new photo: {d.Reasons[0]}"
                    : "The latest photo was not accepted; take a new one");
            return new(false, NextVerifyAction.CapturePhoto, reasons);
        }
        if (Has(VerifyBlocker.ReviewCitesRejectedPhoto, VerifyBlocker.ReviewCitesPreClaimPhoto, VerifyBlocker.ReviewMissing, VerifyBlocker.ReviewConflicting,
                VerifyBlocker.ReviewUnknown, VerifyBlocker.ReviewNotSatisfied, VerifyBlocker.ReviewInvalid))
            return new(false, NextVerifyAction.ReviewChecklist, reasons);
        if (Has(VerifyBlocker.NotAcknowledged)) return new(false, NextVerifyAction.Acknowledge, reasons);
        return new(true, NextVerifyAction.Verify, []);
    }
}
