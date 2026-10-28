namespace LensQi.FieldOps.Core.Inspection;

/// <summary>
/// Deterministic dual-proof gate (PRD FR-08, §7 pseudocode, §17). Pure function of state, twin and server time.
/// No model output, confidence score or prediction outcome is consulted. Every denial carries a reason.
/// </summary>
public static class VerificationPolicyEvaluator
{
    public const string PolicyVersion = "fieldproof-policy/1.1";

    public static VerificationDecision Evaluate(InspectionState state, LoadedExpectedTwin loaded, DateTimeOffset now)
    {
        var twin = loaded.Twin;
        var policy = twin.VerificationPolicy;
        var reasons = new List<string>();

        if (!string.Equals(state.FixtureDigest, loaded.Sha256, StringComparison.Ordinal) || state.FixtureId != twin.FixtureId)
            return Deny("Expected-state version mismatch: inspection is bound to a different fixture digest");

        if (state.RepairClaimedAt is null)
            return Deny("No repair claim has been recorded");

        if (policy.RequireTechnicianAcknowledgement && !state.TechnicianAcknowledged)
            return Deny("Repair has not been acknowledged by the technician");

        if (policy.RequirePostRepairPhoto && state.LatestPostRepairPhoto is null)
            return Deny("A new post-repair image is required");

        if (policy.RequireHumanReviewedVisualChecklist)
        {
            foreach (var req in twin.MandatoryRequirements)
            {
                if (!state.Checklist.TryGetValue(req.RequirementId, out var entry) || entry.ReviewedAt <= state.RepairClaimedAt)
                    return Review($"Visual requirement '{req.RequirementId}' has not been reviewed after the repair claim");
                if (entry.Status == ChecklistStatus.Conflicting)
                    return Review($"Visual requirement '{req.RequirementId}' is marked conflicting");
                if (entry.Status == ChecklistStatus.Unknown)
                    return Review($"Visual requirement '{req.RequirementId}' remains unknown");
                if (entry.Status == ChecklistStatus.NotSatisfied)
                    return Deny($"Visual requirement '{req.RequirementId}' is not satisfied");
            }
        }

        var claims = new List<string>();
        DiagnosticSource? weakestSource = null;
        if (policy.RequireFreshDiagnostic)
        {
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
                    return Deny($"Diagnostic '{diag.DiagnosticId}' still fails: {diag.ClaimVerified ?? "approved check"} not satisfied");
                claims.Add(diag.ClaimVerified ?? $"Diagnostic {diag.DiagnosticId} passed at test time");
                if (r.Source != policy.VerifiedLiveRequiresSource) weakestSource = r.Source;
            }
        }

        var kind = weakestSource is null ? VerificationOutcomeKind.VerifiedCompleteLive : VerificationOutcomeKind.VerifiedCompleteSimulated;
        reasons.Add(kind == VerificationOutcomeKind.VerifiedCompleteLive
            ? "Specified live fixture checks passed"
            : $"Qualifying {weakestSource} fixture checks passed; this is not a live measurement");
        return new VerificationDecision(VerificationResult.Verified, kind, reasons, claims, PolicyVersion, loaded.Sha256);

        VerificationDecision Deny(string reason) => new(VerificationResult.Denied, null, [reason], [], PolicyVersion, loaded.Sha256);
        VerificationDecision Review(string reason) => new(VerificationResult.NeedsHumanReview, null, [reason], [], PolicyVersion, loaded.Sha256);
    }
}
