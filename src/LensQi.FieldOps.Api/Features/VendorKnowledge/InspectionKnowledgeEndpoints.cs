using LensQi.FieldOps.Api.Infrastructure.Hardening;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Features.Inspections;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Meta.Knowledge;

namespace LensQi.FieldOps.Api.Features.VendorKnowledge;

/// <summary>Child design §8: resolve applicable facts for an inspection and ask Muse for a bounded, validated proposal.</summary>
public static class InspectionKnowledgeEndpoints
{
    public sealed record ReasonRequest(string Observation);

    public static IEndpointRouteBuilder MapInspectionKnowledge(this IEndpointRouteBuilder app)
    {
        app.MapGet("/v1/inspections/{id}/knowledge", (string id, InspectionStore store, IReadOnlyList<FixtureKnowledgeBinding> bindings, IVendorKnowledgeResolver resolver, IKnowledgePackStore packs) =>
        {
            var s = store.Find(id);
            if (s is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            var binding = bindings.FirstOrDefault(b => b.FixtureId == s.State.FixtureId && b.AssetId == s.State.AssetId);
            if (binding is null) return Results.Ok(new { inspectionId = id, status = KnowledgeResolutionStatus.NoPack.ToString(), facts = Array.Empty<object>(), excluded = Array.Empty<object>(), warnings = new[] { "No vendor-knowledge binding for this asset" } });
            var r = resolver.Resolve(binding, binding.ToAssetIdentity(), []);
            return Results.Ok(new
            {
                inspectionId = id, status = r.Status.ToString(), r.PackId, r.PackSha256, r.PackRevision,
                originKind = r.PackId is { } packId ? packs.Find(packId)?.Pack.OriginKind.ToString() : null,
                facts = r.Facts.Select(f => new { f.FactId, f.FactKey, f.AssertedValue, f.Statement, claimKind = f.ClaimKind.ToString(), provenance = f.Provenance.ToString(), safety = f.Safety.ToString(), f.Tags,
                    citation = new { f.Citation.DocumentTitle, f.Citation.Publisher, sourceUrl = f.Citation.SourceUrl?.ToString(), f.Citation.SourceLocator, f.Citation.AccessedOn } }),
                excluded = r.Excluded, r.Warnings, approvedDiagnostics = binding.ApprovedDiagnostics,
            });
        });

        // VK-T06/T25: every proposal call lands on the inspection log, accepted or rejected. An accepted RunApprovedProbe
        // registers its prediction in the same batch, before any probe for it can run. A retried key returns what was
        // recorded, and a closed inspection is refused before the paid call.
        app.MapPost("/v1/inspections/{id}/knowledge/reason", async (string id, ReasonRequest req, HttpRequest http, InspectionStore store, IReadOnlyList<FixtureKnowledgeBinding> bindings, IVendorKnowledgeResolver resolver, GroundedProposalService service, KeyedGate gate, TimeProvider clock, CancellationToken ct) =>
        {
            var s = store.Find(id);
            if (s is null) return ApiResults.NotFound($"Inspection '{id}' not found");
            var key = InspectionEndpoints.Key(http);
            using var _ = await gate.EnterAsync(id, key, ct);   // an overlapping retry waits, then replays
            if (key is not null && s.Recorded(key) is { } done)
                return Outcome(done) is { } earlier ? Reply(earlier, s, null) : ApiResults.Conflict(InspectionErrorCodes.InvalidState, "This Idempotency-Key was used for a different command");
            if (!s.AcceptsKnowledgeProposal) return ApiResults.Conflict(InspectionErrorCodes.InvalidState, $"Knowledge proposals are not accepted in the current stage (stage {s.State.Stage})");
            var binding = bindings.FirstOrDefault(b => b.FixtureId == s.State.FixtureId && b.AssetId == s.State.AssetId);
            if (binding is null) return ApiResults.Conflict(KnowledgeResolutionStatus.NoPack.ToString(), "No vendor-knowledge binding for this asset");
            if (string.IsNullOrWhiteSpace(req.Observation) || req.Observation.Length > 2000) return ApiResults.BadRequest("observation must be 1-2000 characters");
            var resolution = resolver.Resolve(binding, binding.ToAssetIdentity(), []);
            GroundedProposalResult result;
            try { result = await service.ProposeAsync(binding, binding.ToAssetIdentity(), resolution, new Observation(req.Observation), ct); }
            catch (Exception ex) when ((ex is HttpRequestException or TaskCanceledException or InvalidOperationException) && !ct.IsCancellationRequested) { return ApiResults.Conflict("provider_unavailable", ex.Message); }

            var proposalId = "prop-" + Guid.NewGuid().ToString("N")[..10];
            var predictionId = "pred-" + Guid.NewGuid().ToString("N")[..10];
            try
            {
                return await store.ExecuteAsync(id, session =>
                {
                    var events = session.RecordKnowledgeProposal(key ?? "reason:" + proposalId, proposalId, result.ModelId, result.PromptDigest, result.ResponseDigest,
                        result.Proposal, result.AttemptedAction, result.Validation.Rejections, predictionId, clock.GetUtcNow());
                    return Task.FromResult(Outcome(events) is { } o ? Reply(o, session, result) : ApiResults.Conflict(InspectionErrorCodes.InvalidState, "This Idempotency-Key was used for a different command"));
                }, ct);
            }
            catch (InspectionConflictException ex) { return ApiResults.Conflict(ex.Code, ex.Message); }
        }).AsModelCall();

        // Design §6a: registered predictions with their Core-owned scores. Read-only; calibration, never a verification input.
        app.MapGet("/v1/inspections/{id}/predictions", (string id, InspectionStore store) =>
        {
            if (store.Find(id) is not { } s) return ApiResults.NotFound($"Inspection '{id}' not found");
            var scores = s.State.PredictionScores.ToDictionary(p => p.PredictionId, StringComparer.Ordinal);
            var rows = s.Events.OfType<PredictionRegistered>().Select(p =>
            {
                var score = scores.GetValueOrDefault(p.PredictionId);
                return new
                {
                    p.PredictionId, p.DiagnosticId, p.PredictedValue, p.BasedOnFactIds, p.PredictionDigest, p.ProposalId, registeredAt = p.OccurredAt,
                    measuredValue = score?.MeasuredValue, outcome = score?.Outcome.ToString() ?? "Pending", diagnosticEvidenceId = score?.DiagnosticEvidenceId, scoredAt = score?.OccurredAt,
                };
            }).ToList();
            int Count(string outcome) => rows.Count(r => r.outcome == outcome);
            return Results.Ok(new
            {
                inspectionId = id, predictions = rows,
                tally = new { confirmed = Count("Confirmed"), falsified = Count("Falsified"), unscorable = Count("Unscorable"), pending = Count("Pending") },
                note = "Model calibration. The probe measurement decides verification; a prediction never does.",
            });
        });
        return app;
    }

    private sealed record ReasonOutcome(bool Accepted, string ProposalId, string ModelId, string PromptDigest, string ResponseDigest,
        VendorGroundedProposal? Proposal, IReadOnlyList<ProposalRejection> Rejections, object? Prediction);

    private static ReasonOutcome? Outcome(IReadOnlyList<InspectionEvent> events)
    {
        if (events.OfType<KnowledgeProposalAccepted>().FirstOrDefault() is { } a)
        {
            var p = events.OfType<PredictionRegistered>().FirstOrDefault();
            return new(true, a.ProposalId, a.ModelId, a.PromptDigest, a.ResponseDigest, a.Proposal, [],
                p is null ? null : new { p.PredictionId, p.DiagnosticId, p.PredictedValue, p.BasedOnFactIds, p.PredictionDigest });
        }
        return events.OfType<KnowledgeProposalRejected>().FirstOrDefault() is { } r
            ? new(false, r.ProposalId, r.ModelId, r.PromptDigest, r.ResponseDigest, null, r.Rejections, null)
            : null;
    }

    /// <summary>The recorded outcome; the raw response, usage and parse error exist only on the call that produced them.</summary>
    private static IResult Reply(ReasonOutcome o, InspectionSession s, GroundedProposalResult? fresh) => Results.Ok(new
    {
        o.Accepted, o.ProposalId, o.ModelId, o.PromptDigest, o.ResponseDigest, o.Proposal, o.Rejections, o.Prediction,
        usage = fresh?.Usage, parseError = fresh?.ParseError, raw = fresh?.RawResponse, replayed = fresh is null, state = InspectionEndpoints.View(s),
    });
}
