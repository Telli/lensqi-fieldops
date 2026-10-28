using LensQi.FieldOps.Core.Diagnostics;

namespace LensQi.FieldOps.Core.Knowledge;

public sealed record ProposalRejection(string Code, string Message, IReadOnlyList<string> OffendingIds);

public sealed record ProposalValidationResult(bool IsValid, IReadOnlyList<ProposalRejection> Rejections)
{
    public static readonly ProposalValidationResult Valid = new(true, []);
}

/// <summary>Stable error codes for proposal rejection (design.md §8 Errors).</summary>
public static class ProposalErrorCodes
{
    public const string AssetMismatch = "asset_mismatch";
    public const string InvalidFactReference = "invalid_fact_reference";
    public const string ProbeNotAllowlisted = "probe_not_allowlisted";
    public const string ActionNotAllowed = "action_not_allowed";
    public const string PredictionMissing = "prediction_missing";
    public const string PredictionValueInvalid = "prediction_value_invalid";
}

/// <summary>
/// Server-side validation of a model proposal (VK-R04.2, VK-R11.1, design.md §5 mapping table). Never repairs or
/// approximates: anything outside the resolved facts, the fixture allowlist, the probe registry or the closed
/// prediction sets is rejected, and every rejection is returned so it can be persisted as KnowledgeProposalRejected.
/// </summary>
public static class KnowledgeActionValidator
{
    public static ProposalValidationResult Validate(
        VendorGroundedProposal proposal,
        KnowledgeResolution resolution,
        FixtureKnowledgeBinding binding,
        IDiagnosticRegistry registry)
    {
        var rejections = new List<ProposalRejection>();

        if (!string.Equals(proposal.AssetId, binding.AssetId, StringComparison.Ordinal))
            rejections.Add(new(ProposalErrorCodes.AssetMismatch,
                $"Proposal names asset '{proposal.AssetId}' but the inspection is bound to '{binding.AssetId}'.", [proposal.AssetId]));

        var applicable = resolution.Facts.Select(f => f.FactId).ToHashSet(StringComparer.Ordinal);
        var unknownFacts = proposal.UsedFactIds.Where(id => !applicable.Contains(id)).Distinct(StringComparer.Ordinal).ToList();
        if (unknownFacts.Count > 0)
            rejections.Add(new(ProposalErrorCodes.InvalidFactReference,
                "Proposal cites fact IDs that are not in the resolved applicable set.", unknownFacts));

        switch (proposal.NextAction)
        {
            case ProposedNextAction.RequestView:
                if (string.IsNullOrWhiteSpace(proposal.RequestedVisualEvidence))
                    rejections.Add(new(ProposalErrorCodes.ActionNotAllowed, "RequestView requires requestedVisualEvidence.", []));
                break;

            case ProposedNextAction.RunApprovedProbe:
                ValidateDiagnostic(proposal, resolution, binding, registry, rejections);
                break;

            case ProposedNextAction.PrepareWorkOrder:
                var humanFacts = resolution.Facts
                    .Where(f => f.Safety == KnowledgeSafetyClass.HumanActionRequired && proposal.UsedFactIds.Contains(f.FactId, StringComparer.Ordinal))
                    .ToList();
                if (humanFacts.Count == 0)
                    rejections.Add(new(ProposalErrorCodes.ActionNotAllowed,
                        "PrepareWorkOrder requires citing at least one applicable fact with safetyClass HumanActionRequired.", []));
                break;

            case ProposedNextAction.SegmentRegion:
                rejections.Add(new(ProposalErrorCodes.ActionNotAllowed,
                    "SegmentRegion is not available to knowledge proposals; segmentation targets come from the fixture's visual requirements.", []));
                break;

            case ProposedNextAction.RequestHumanReview:
            case ProposedNextAction.StopInconclusive:
                break;

            default:
                rejections.Add(new(ProposalErrorCodes.ActionNotAllowed, $"Unknown action '{proposal.NextAction}'.", []));
                break;
        }

        // A diagnostic ID offered with any other action is still checked, so a disallowed target never slips through unlabeled.
        if (proposal.NextAction != ProposedNextAction.RunApprovedProbe && proposal.ProposedDiagnosticId is not null)
            CheckDiagnosticAllowlisted(proposal.ProposedDiagnosticId, binding, registry, rejections);

        return rejections.Count == 0 ? ProposalValidationResult.Valid : new ProposalValidationResult(false, rejections);
    }

    private static void ValidateDiagnostic(VendorGroundedProposal proposal, KnowledgeResolution resolution, FixtureKnowledgeBinding binding,
        IDiagnosticRegistry registry, List<ProposalRejection> rejections)
    {
        if (string.IsNullOrWhiteSpace(proposal.ProposedDiagnosticId))
        {
            rejections.Add(new(ProposalErrorCodes.ActionNotAllowed, "RunApprovedProbe requires proposedDiagnosticId.", []));
            return;
        }

        var registration = CheckDiagnosticAllowlisted(proposal.ProposedDiagnosticId, binding, registry, rejections);
        if (registration is null) return;

        if (proposal.Prediction is null)
        {
            rejections.Add(new(ProposalErrorCodes.PredictionMissing,
                "RunApprovedProbe requires a pre-registered prediction (VK-R11.1).", [proposal.ProposedDiagnosticId]));
            return;
        }

        var prediction = proposal.Prediction;
        if (!string.Equals(prediction.DiagnosticId, proposal.ProposedDiagnosticId, StringComparison.Ordinal))
            rejections.Add(new(ProposalErrorCodes.PredictionValueInvalid,
                "Prediction must name the proposed diagnostic.", [prediction.DiagnosticId]));

        var allowed = PredictionValueSets.For(registration.ObservationKind);
        if (!allowed.Contains(prediction.PredictedValue, StringComparer.Ordinal))
            rejections.Add(new(ProposalErrorCodes.PredictionValueInvalid,
                $"Predicted value must be one of [{string.Join(", ", allowed)}] for {registration.ObservationKind}.", [prediction.PredictedValue]));

        var used = proposal.UsedFactIds.ToHashSet(StringComparer.Ordinal);
        var strayBasis = prediction.BasedOnFactIds.Where(id => !used.Contains(id)).ToList();
        if (strayBasis.Count > 0)
            rejections.Add(new(ProposalErrorCodes.InvalidFactReference,
                "Prediction basedOnFactIds must be a subset of usedFactIds.", strayBasis));
    }

    private static DiagnosticRegistration? CheckDiagnosticAllowlisted(string diagnosticId, FixtureKnowledgeBinding binding,
        IDiagnosticRegistry registry, List<ProposalRejection> rejections)
    {
        var inFixture = binding.ApprovedDiagnostics.Contains(diagnosticId, StringComparer.Ordinal);
        var registration = registry.Find(diagnosticId);
        if (inFixture && registration is not null) return registration;

        rejections.Add(new(ProposalErrorCodes.ProbeNotAllowlisted,
            $"Diagnostic '{diagnosticId}' is not approved by the fixture and registered on the server. No network call was made.",
            [diagnosticId]));
        return null;
    }
}
