using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Core.Inspection;

/// <summary>
/// Deterministic dual-proof gate (PRD FR-08, §7 pseudocode, §17). Pure function of state, twin, server time and the
/// resolved documentation requirement. No model output, confidence score or prediction outcome is consulted. Every denial
/// carries a reason.
/// </summary>
/// <summary>A verification prerequisite that is not met, as <see cref="VerificationPolicyEvaluator.Blockers"/> reports it.</summary>
public enum VerifyBlocker
{
    FixtureMismatch, NoClaim, NotAcknowledged, PhotoBeingCompared, PhotoRequired, ReviewCitesRejectedPhoto, ReviewCitesPreClaimPhoto,
    ReviewMissing, ReviewConflicting, ReviewUnknown, ReviewNotSatisfied, ReviewInvalid,
}

public static class VerificationPolicyEvaluator
{
    public const string PolicyVersion = "fieldproof-policy/1.7";

    /// <summary>Every denial caused by a fresh failing diagnostic starts with this; the session maps it to VerificationFailed.</summary>
    public const string DiagnosticFailedPrefix = "Diagnostic failed after the repair claim";

    /// <summary>A decision held because documentation the site requires is not confirmed for this equipment starts with this.</summary>
    public const string KnowledgeUnconfirmedPrefix = "Required vendor facts are not confirmed for this equipment";

    /// <summary>
    /// Everything the gate checks before diagnostics: fixture, claim, acknowledgement, photo and checklist. A non-null
    /// result decides the outcome whatever a probe would measure, so the caller need not run one.
    /// </summary>
    public static VerificationDecision? Prerequisites(InspectionState state, LoadedExpectedTwin loaded) =>
        Blockers(state, loaded).Select(b => b.Decision).FirstOrDefault();

    /// <summary>
    /// Every prerequisite that is not met, in the order the gate checks them; the first one is the decision
    /// <see cref="Prerequisites"/> returns. A missing fixture match or claim ends the list, since nothing after it applies.
    /// </summary>
    public static IEnumerable<(VerifyBlocker Blocker, VerificationDecision Decision)> Blockers(InspectionState state, LoadedExpectedTwin loaded)
    {
        var twin = loaded.Twin;
        var policy = twin.VerificationPolicy;

        if (!string.Equals(state.FixtureDigest, loaded.Sha256, StringComparison.Ordinal) || state.FixtureId != twin.FixtureId)
        {
            yield return (VerifyBlocker.FixtureMismatch, Deny(loaded, "Expected-state version mismatch: inspection is bound to a different fixture digest"));
            yield break;
        }

        if (state.RepairClaimedAt is null)
        {
            yield return (VerifyBlocker.NoClaim, Deny(loaded, "No repair claim has been recorded"));
            yield break;
        }

        if (policy.RequireTechnicianAcknowledgement && !state.TechnicianAcknowledged)
            yield return (VerifyBlocker.NotAcknowledged, Deny(loaded, "Repair has not been acknowledged by the technician"));

        // 1.4: a post-repair photo still being compared holds the decision when it is newer than the accepted one, even
        // though an older photo was accepted; its verdict may contradict the evidence the review relied on.
        if (state.PhotosBeingCompared.LastOrDefault() is { } waiting)
            yield return (VerifyBlocker.PhotoBeingCompared, Review($"The post-repair photo {waiting.EvidenceId} is still being compared with the earlier view"));

        // 1.3: a post-repair photo counts only once its change analysis is recorded, and only Satisfied meets a requirement.
        if (policy.RequirePostRepairPhoto && state.LatestPostRepairPhoto is null)
            yield return (VerifyBlocker.PhotoRequired, Deny(loaded, "A new post-repair image is required"));

        // 1.2: a checklist review that cites a photo change analysis rejected is not post-repair evidence.
        foreach (var entry in state.Checklist.Values)
            if (entry.ReviewedByEvidenceId is { } cited && state.IsRejectedForCurrentClaim(cited))
                yield return (VerifyBlocker.ReviewCitesRejectedPhoto, Deny(loaded, $"Visual requirement '{entry.RequirementId}' was reviewed against {cited}, which change analysis rejected"));

        // 1.5: a review is post-repair evidence only when the photo it cites is. A review made before the claim is merely stale
        // (ReviewMissing below asks for a new one); one made after the claim against a pre-claim photo would verify the repair
        // on a view of the fault, so it is denied.
        foreach (var entry in state.Checklist.Values)
            if (entry.ReviewedAt > state.RepairClaimedAt && entry.ReviewedByEvidenceId is { } cited && state.Photos.FirstOrDefault(p => p.EvidenceId == cited) is { } photo && !state.IsPostClaim(photo))
                yield return (VerifyBlocker.ReviewCitesPreClaimPhoto, Deny(loaded, $"Visual requirement '{entry.RequirementId}' was reviewed against {cited}, which was taken before the repair claim"));

        if (policy.RequireHumanReviewedVisualChecklist)
        {
            foreach (var req in twin.MandatoryRequirements)
            {
                if (!state.Checklist.TryGetValue(req.RequirementId, out var entry) || entry.ReviewedAt <= state.RepairClaimedAt)
                    yield return (VerifyBlocker.ReviewMissing, Review($"Visual requirement '{req.RequirementId}' has not been reviewed after the repair claim"));
                else if (entry.Status == ChecklistStatus.Conflicting)
                    yield return (VerifyBlocker.ReviewConflicting, Review($"Visual requirement '{req.RequirementId}' is marked conflicting"));
                else if (entry.Status == ChecklistStatus.Unknown)
                    yield return (VerifyBlocker.ReviewUnknown, Review($"Visual requirement '{req.RequirementId}' remains unknown"));
                else if (entry.Status == ChecklistStatus.NotSatisfied)
                    yield return (VerifyBlocker.ReviewNotSatisfied, Deny(loaded, $"Visual requirement '{req.RequirementId}' is not satisfied"));
                // Only an explicit Satisfied meets the requirement; any other value, including an undefined one, does not.
                else if (entry.Status != ChecklistStatus.Satisfied)
                    yield return (VerifyBlocker.ReviewInvalid, Deny(loaded, $"Visual requirement '{req.RequirementId}' has an unrecognised review status"));
            }
        }

        VerificationDecision Review(string reason) => new(VerificationResult.NeedsHumanReview, null, [reason], [], PolicyVersion, loaded.Sha256);
    }

    private static VerificationDecision Deny(LoadedExpectedTwin loaded, string reason) => new(VerificationResult.Denied, null, [reason], [], PolicyVersion, loaded.Sha256);

    public static VerificationDecision Evaluate(InspectionState state, LoadedExpectedTwin loaded, DateTimeOffset now, RequiredKnowledgeCheck? requiredKnowledge = null)
    {
        var twin = loaded.Twin;
        var policy = twin.VerificationPolicy;
        var reasons = new List<string>();

        if (Prerequisites(state, loaded) is { } early) return early;

        var claims = new List<string>();
        DiagnosticSource? weakestSource = null;
        // 1.7: every verified outcome rests on a fresh measurement. A policy without one cannot verify anything, and it must
        // never fall through to the live outcome with nothing measured.
        if (!policy.RequireFreshDiagnostic)
            return Deny("The fixture policy does not require a fresh equipment check, so there is no measurement to verify against");
        if (twin.Diagnostics.Count == 0)
            return Deny("Fixture declares no diagnostic but the policy requires one");
        foreach (var diag in twin.Diagnostics)
        {
            var latest = state.LatestDiagnosticFor(diag.DiagnosticId);
            if (latest is null || latest.Result.CompletedAt <= state.RepairClaimedAt)
                return Deny($"Fresh post-repair diagnostic '{diag.DiagnosticId}' is required");
            var r = latest.Result;
            if (r.FixtureId != twin.FixtureId)
                return Deny($"Diagnostic '{diag.DiagnosticId}' was run for fixture '{r.FixtureId}', not '{twin.FixtureId}'");
            if (!policy.AllowedDiagnosticSources.Contains(r.Source))
                return Deny($"Diagnostic source '{r.Source}' is not allowed by the fixture policy");
            if ((now - r.CompletedAt).TotalSeconds > policy.DiagnosticMaxAgeSeconds)
                return Deny($"Diagnostic '{diag.DiagnosticId}' is older than {policy.DiagnosticMaxAgeSeconds}s");
            if (r.Passed is null)
                return Deny($"Diagnostic '{diag.DiagnosticId}' was unavailable; no measurement to verify against");
            if (r.Passed == false)
                return Deny($"{DiagnosticFailedPrefix}: {diag.DiagnosticId} measured {(r.HttpStatus is int status ? $"HTTP {status}" : r.NormalizedValue ?? "a failure")} ({r.Source} probe). " +
                    $"Still required: “{diag.ClaimVerified ?? $"{diag.DiagnosticId} passes"}”");
            claims.Add(diag.ClaimVerified ?? $"Diagnostic {diag.DiagnosticId} passed at test time");
            if (r.Source != DiagnosticSource.LiveLocal || r.Source != policy.VerifiedLiveRequiresSource) weakestSource = r.Source;
        }

        // 1.6 (VK-R08): documentation adds a gate and never replaces one. It comes after the probes, so a failing measurement
        // still decides VerificationFailed; unconfirmed documentation only holds an otherwise passing inspection for review.
        if (requiredKnowledge is { Confirmed: false })
            return new VerificationDecision(VerificationResult.NeedsHumanReview, null,
                [$"{KnowledgeUnconfirmedPrefix} ({requiredKnowledge.Status}): {string.Join(", ", requiredKnowledge.UnconfirmedFactIds)}"], [], PolicyVersion, loaded.Sha256, requiredKnowledge);

        var kind = weakestSource is null ? VerificationOutcomeKind.VerifiedCompleteLive : VerificationOutcomeKind.VerifiedCompleteSimulated;
        reasons.Add(kind == VerificationOutcomeKind.VerifiedCompleteLive
            ? "Specified live fixture checks passed"
            : $"Qualifying {weakestSource} fixture checks passed; this is not a live measurement");
        return new VerificationDecision(VerificationResult.Verified, kind, reasons, claims, PolicyVersion, loaded.Sha256, requiredKnowledge);

        VerificationDecision Deny(string reason) => VerificationPolicyEvaluator.Deny(loaded, reason);
    }
}
