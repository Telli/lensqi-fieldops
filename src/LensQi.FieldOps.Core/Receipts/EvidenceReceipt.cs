using System.Text.Json.Nodes;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Core.Receipts;

public sealed record ReceiptArtifact(string EvidenceId, string Kind, string Path, string Sha256, long Bytes, string MediaType, string Origin, DateTimeOffset ObservedAt);

public sealed record ReceiptKnowledge(string PackId, string PackSha256, int PackRevision, string Status, IReadOnlyList<ReceiptCitation> Citations, IReadOnlyList<ExcludedFact> Excluded);

public sealed record ReceiptCitation(string FactId, string ClaimKind, string Provenance, string Statement, string DocumentTitle, string Publisher, string? SourceUrl, string SourceLocator, DateOnly AccessedOn);

public sealed record ReceiptModelCall(string EvidenceId, string ModelId, string SchemaVersion, DateTimeOffset At);

/// <summary>One pre-registered prediction and, once its probe ran, the measurement Core scored it against.</summary>
public sealed record ReceiptPrediction(string PredictionId, string? ProposalId, string DiagnosticId, string PredictedValue, IReadOnlyList<string> BasedOnFactIds,
    string? PredictionDigest, DateTimeOffset RegisteredAt, string? MeasuredValue, string Outcome, string? DiagnosticEvidenceId, DateTimeOffset? ScoredAt);

public sealed record ReceiptPredictionTally(int Confirmed, int Falsified, int Unscorable, int Pending);

/// <summary>VK-R11.5: model calibration, labelled as such. The verification decision never reads it.</summary>
public sealed record ReceiptPredictions(string Label, ReceiptPredictionTally Tally, IReadOnlyList<ReceiptPrediction> Items);

/// <summary>The change verdict that counted for one repair claim: the latest analysis of that claim's photos, or none.</summary>
public sealed record ReceiptClaimChange(string ClaimId, string Description, DateTimeOffset ClaimedAt, string Verdict, string? PhotoEvidenceId, int Analyses);

/// <summary>VK-R12: one verdict per repair claim, every analysis behind them, and a tally by verdict.</summary>
public sealed record ReceiptVisualChange(string Label, IReadOnlyDictionary<string, int> Tally, IReadOnlyList<ReceiptClaimChange> Claims, IReadOnlyList<VisualChangeAnalysis> Analyses);

public sealed record ReceiptRejectedProposal(string ProposalId, string ModelId, string? NextAction, IReadOnlyList<string> Codes, DateTimeOffset At);

/// <summary>VK-R04.2: how many model proposals the server accepted and which it refused, so the boundary is visible per inspection.</summary>
public sealed record ReceiptProposals(string Label, int Accepted, IReadOnlyList<ReceiptRejectedProposal> Rejected);

/// <summary>
/// The FieldProof receipt (PRD FR-09, child VK-R09.2, VK-R11.5, VK-R12). Everything except <see cref="ManifestSha256"/> is
/// covered by the manifest digest, which is the SHA-256 of the canonical JSON of the receipt with that field null.
/// </summary>
public sealed record EvidenceReceipt(
    string ReceiptVersion,
    DateTimeOffset GeneratedAt,
    ReceiptInspection Inspection,
    ReceiptVersions Versions,
    IReadOnlyList<ReceiptArtifact> Artifacts,
    IReadOnlyList<ReceiptModelCall> ModelCalls,
    IReadOnlyList<DiagnosticEvidence> Diagnostics,
    ReceiptPredictions Predictions,
    ReceiptVisualChange VisualChange,
    ReceiptProposals Proposals,
    IReadOnlyList<ChecklistEntry> Checklist,
    VerificationDecision? Decision,
    ReceiptKnowledge? Knowledge,
    IReadOnlyList<InspectionEvent> Events,
    IReadOnlyList<string> Disclaimers,
    string? ManifestSha256)
{
    public const string Version = "fieldproof-receipt/1.2";

    public static readonly IReadOnlyList<string> StandardDisclaimers =
    [
        "This receipt verifies that the specified fixture checks passed or failed at the recorded times. It is not a safety, compliance or electrical certification.",
        "SHA-256 digests detect alteration of this bundle after it was issued. They do not prove who captured the images, nor prevent replacement of the whole bundle. Signing is future work.",
        "Diagnostics labelled Simulated or RecordedReplay are not live measurements and never yield VerifiedCompleteLive.",
        "A passing endpoint check verifies endpoint reachability only; on an unmanaged switch it does not establish which physical port was used.",
        "Model observations and predictions are annotations of photographs, not evidence of equipment state. Verification decisions are computed by code from measured evidence.",
        "Vendor documentation citations guide the investigation; they are never evidence that a repair succeeded.",
    ];

    public EvidenceReceipt WithManifest()
    {
        var canonical = CanonicalJson.Serialize(this with { ManifestSha256 = null });
        return this with { ManifestSha256 = CanonicalJson.Sha256(canonical) };
    }
}

public sealed record ReceiptInspection(string InspectionId, string FixtureId, int FixtureRevision, string FixtureDigest, string AssetId, string Stage, string? OutcomeKind, string? RepairClaimId, DateTimeOffset? RepairClaimedAt, bool TechnicianAcknowledged);

public sealed record ReceiptVersions(string PolicyVersion, string ReceiptVersion, string ProposalSchema, string AssessmentSchema);

public static class ReceiptBuilder
{
    public static EvidenceReceipt Build(InspectionSession session, IReadOnlyList<ReceiptArtifact> artifacts, ReceiptKnowledge? knowledge, DateTimeOffset now, string assessmentSchemaVersion)
    {
        var st = session.State;
        var modelCalls = session.Events.OfType<AssessmentRecorded>()
            .Select(a => new ReceiptModelCall(a.EvidenceId, a.Assessment.ModelId, a.Assessment.SchemaVersion, a.OccurredAt)).ToList();
        var receipt = new EvidenceReceipt(
            EvidenceReceipt.Version, now,
            new ReceiptInspection(st.InspectionId, st.FixtureId, st.FixtureRevision, st.FixtureDigest, st.AssetId, st.Stage.ToString(),
                st.LastDecision?.OutcomeKind?.ToString(), st.CurrentClaimId, st.RepairClaimedAt, st.TechnicianAcknowledged),
            new ReceiptVersions(VerificationPolicyEvaluator.PolicyVersion, EvidenceReceipt.Version, "vendor-grounded-proposal/1.2", assessmentSchemaVersion),
            artifacts, modelCalls, st.Diagnostics, Predictions(session), VisualChange(session), Proposals(session), st.Checklist.Values.OrderBy(c => c.RequirementId, StringComparer.Ordinal).ToList(),
            st.LastDecision, knowledge, session.Events, EvidenceReceipt.StandardDisclaimers, null);
        return receipt.WithManifest();
    }

    private static ReceiptPredictions Predictions(InspectionSession session)
    {
        var scores = session.State.PredictionScores.ToDictionary(p => p.PredictionId, StringComparer.Ordinal);
        var items = session.Events.OfType<PredictionRegistered>().Select(p =>
        {
            var score = scores.GetValueOrDefault(p.PredictionId);
            return new ReceiptPrediction(p.PredictionId, p.ProposalId, p.DiagnosticId, p.PredictedValue, p.BasedOnFactIds, p.PredictionDigest, p.OccurredAt,
                score?.MeasuredValue, score?.Outcome.ToString() ?? "Pending", score?.DiagnosticEvidenceId, score?.OccurredAt);
        }).ToList();
        int Count(string outcome) => items.Count(i => i.Outcome == outcome);
        return new("Model calibration: what the model predicted before the probe ran, scored by code. Never a verification input.",
            new(Count("Confirmed"), Count("Falsified"), Count("Unscorable"), Count("Pending")), items);
    }

    private static ReceiptVisualChange VisualChange(InspectionSession session)
    {
        var analyses = session.Events.OfType<VisualChangeAnalyzed>().Select(e => e.Analysis).ToList();
        var claims = session.Events.OfType<RepairClaimed>().Select(c =>
        {
            var mine = analyses.Where(a => a.ClaimId == c.ClaimId).ToList();
            return new ReceiptClaimChange(c.ClaimId, c.Description, c.OccurredAt, mine.LastOrDefault()?.Verdict.ToString() ?? "NotAnalysed", mine.LastOrDefault()?.PhotoEvidenceId, mine.Count);
        }).ToList();
        var tally = analyses.GroupBy(a => a.Verdict.ToString()).OrderBy(g => g.Key, StringComparer.Ordinal).ToDictionary(g => g.Key, g => g.Count());
        return new("Measured visual change between the pre-repair and post-repair photos. Evidence that something changed, never that the repair worked.", tally, claims, analyses);
    }

    private static ReceiptProposals Proposals(InspectionSession session) => new(
        "Model proposals checked by the server before anything was acted on. Rejected proposals ran nothing.",
        session.Events.OfType<KnowledgeProposalAccepted>().Count(),
        session.Events.OfType<KnowledgeProposalRejected>().Select(r => new ReceiptRejectedProposal(r.ProposalId, r.ModelId, r.NextAction, r.Rejections.Select(x => x.Code).Distinct().ToList(), r.OccurredAt)).ToList());

    public static ReceiptKnowledge FromResolution(KnowledgeResolution r) => new(
        r.PackId ?? "", r.PackSha256 ?? "", r.PackRevision ?? 0, r.Status.ToString(),
        r.Facts.Select(f => new ReceiptCitation(f.FactId, f.ClaimKind.ToString(), f.Provenance.ToString(), f.Statement, f.Citation.DocumentTitle, f.Citation.Publisher, f.Citation.SourceUrl?.ToString(), f.Citation.SourceLocator, f.Citation.AccessedOn)).ToList(),
        r.Excluded);
}

public static class ReceiptHtml
{
    public static string Render(EvidenceReceipt r)
    {
        static string E(string? s) => System.Net.WebUtility.HtmlEncode(s ?? "");
        var sb = new System.Text.StringBuilder();
        var outcome = r.Inspection.OutcomeKind ?? (r.Decision is null ? "No verification decision" : r.Decision.Result.ToString());
        var badge = outcome.Contains("Live") ? "LIVE" : outcome.Contains("Simulated") ? "SIMULATED" : "NOT VERIFIED";
        sb.Append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>FieldProof receipt ").Append(E(r.Inspection.InspectionId)).Append("</title>");
        sb.Append("<style>body{font:14px/1.45 system-ui,sans-serif;margin:2rem;color:#111}h1,h2{margin:.6em 0 .3em}table{border-collapse:collapse;width:100%;margin:.5em 0}td,th{border:1px solid #bbb;padding:4px 6px;text-align:left;vertical-align:top;font-size:13px}code{font-size:12px;word-break:break-all}.badge{display:inline-block;padding:2px 8px;border:2px solid #111;font-weight:700}.sim{background:#ffe9a8}.live{background:#c9f2c9}.no{background:#f6c4c4}.muted{color:#555}.disc{font-size:12px;color:#444}@media print{body{margin:1cm}}</style></head><body>");
        sb.Append("<h1>FieldProof evidence receipt</h1>");
        sb.Append("<p><span class=\"badge ").Append(badge == "LIVE" ? "live" : badge == "SIMULATED" ? "sim" : "no").Append("\">").Append(E(badge)).Append("</span> &nbsp; <b>").Append(E(outcome)).Append("</b></p>");
        sb.Append("<table><tr><th>Inspection</th><td>").Append(E(r.Inspection.InspectionId)).Append("</td><th>Stage</th><td>").Append(E(r.Inspection.Stage)).Append("</td></tr>");
        sb.Append("<tr><th>Fixture</th><td>").Append(E(r.Inspection.FixtureId)).Append(" rev ").Append(r.Inspection.FixtureRevision).Append("</td><th>Fixture digest</th><td><code>").Append(E(r.Inspection.FixtureDigest)).Append("</code></td></tr>");
        sb.Append("<tr><th>Asset</th><td>").Append(E(r.Inspection.AssetId)).Append("</td><th>Generated</th><td>").Append(r.GeneratedAt.ToString("u")).Append("</td></tr>");
        sb.Append("<tr><th>Policy</th><td>").Append(E(r.Versions.PolicyVersion)).Append("</td><th>Manifest SHA-256</th><td><code>").Append(E(r.ManifestSha256)).Append("</code></td></tr></table>");
        if (r.Decision is { } d)
        {
            sb.Append("<h2>Verification decision (computed by code)</h2><p><b>").Append(E(d.Result.ToString())).Append("</b>");
            foreach (var reason in d.Reasons) sb.Append(" — ").Append(E(reason));
            sb.Append("</p>");
            if (d.VerifiedClaims.Count > 0) { sb.Append("<p><b>Exactly what was verified:</b></p><ul>"); foreach (var c in d.VerifiedClaims) sb.Append("<li>").Append(E(c)).Append("</li>"); sb.Append("</ul>"); }
        }
        sb.Append("<h2>Diagnostics (measured)</h2><table><tr><th>Diagnostic</th><th>Source</th><th>Passed</th><th>Completed</th><th>Target alias</th><th>Raw digest</th></tr>");
        foreach (var x in r.Diagnostics)
            sb.Append("<tr><td>").Append(E(x.Result.DiagnosticId)).Append("</td><td><b>").Append(E(x.Result.Source.ToString())).Append("</b></td><td>").Append(x.Result.Passed?.ToString() ?? "Unavailable").Append("</td><td>").Append(x.Result.CompletedAt.ToString("u")).Append("</td><td>").Append(E(x.Result.TargetAlias)).Append("</td><td><code>").Append(E(x.Result.RawDigest)).Append("</code></td></tr>");
        sb.Append("</table>");
        sb.Append("<h2>Visual evidence (photographs, SAM masks) and checklist (human reviewed)</h2><table><tr><th>Evidence</th><th>Kind</th><th>Origin</th><th>Observed</th><th>SHA-256</th><th>Path</th></tr>");
        foreach (var a in r.Artifacts)
            sb.Append("<tr><td>").Append(E(a.EvidenceId)).Append("</td><td>").Append(E(a.Kind == "segmentation" ? "SAM mask (inferred)" : a.Kind)).Append("</td><td>").Append(E(a.Origin)).Append("</td><td>").Append(a.ObservedAt.ToString("u")).Append("</td><td><code>").Append(E(a.Sha256)).Append("</code></td><td>").Append(E(a.Path)).Append("</td></tr>");
        sb.Append("</table>");
        if (r.Checklist.Count > 0) { sb.Append("<ul>"); foreach (var c in r.Checklist) sb.Append("<li>").Append(E(c.RequirementId)).Append(": <b>").Append(E(c.Status.ToString())).Append("</b> at ").Append(c.ReviewedAt.ToString("u")).Append("</li>"); sb.Append("</ul>"); }
        if (r.VisualChange.Claims.Count > 0)
        {
            sb.Append("<h2>Change evidence (measured, visual; never a verification)</h2><p class=\"muted\">").Append(E(r.VisualChange.Label)).Append("</p>");
            sb.Append("<table><tr><th>Repair claim</th><th>Verdict</th><th>Photo</th><th>Analyses</th></tr>");
            foreach (var c in r.VisualChange.Claims)
                sb.Append("<tr><td>").Append(E(c.Description)).Append(" <span class=\"muted\">(").Append(E(c.ClaimId)).Append(")</span></td><td><b>").Append(E(c.Verdict)).Append("</b></td><td>").Append(E(c.PhotoEvidenceId)).Append("</td><td>").Append(c.Analyses).Append("</td></tr>");
            sb.Append("</table>");
            if (r.VisualChange.Analyses.Count > 0)
            {
                sb.Append("<table><tr><th>Claim</th><th>Photo</th><th>Verdict</th><th>Compared with</th><th>dHash distance</th><th>IoU</th><th>Shift</th><th>Reason</th><th>Thresholds</th></tr>");
                foreach (var c in r.VisualChange.Analyses)
                    sb.Append("<tr><td>").Append(E(c.ClaimId)).Append("</td><td>").Append(E(c.PhotoEvidenceId)).Append("</td><td><b>").Append(E(c.Verdict.ToString())).Append("</b></td><td>").Append(E(c.DuplicateOfEvidenceId ?? c.ReferencePhotoEvidenceId))
                      .Append("</td><td>").Append(c.PerceptualHashDistance?.ToString() ?? "").Append("</td><td>").Append(c.TargetRegionIoU?.ToString("0.000") ?? "").Append("</td><td>").Append(c.ComponentShiftNormalized?.ToString("0.000") ?? "")
                      .Append("</td><td>").Append(E(c.Reason)).Append("</td><td>").Append(E(c.ThresholdsVersion)).Append("</td></tr>");
                sb.Append("</table>");
            }
        }
        sb.Append("<h2>Model calls (inferred, annotations only)</h2><table><tr><th>Evidence</th><th>Model</th><th>Schema</th><th>At</th></tr>");
        foreach (var m in r.ModelCalls) sb.Append("<tr><td>").Append(E(m.EvidenceId)).Append("</td><td>").Append(E(m.ModelId)).Append("</td><td>").Append(E(m.SchemaVersion)).Append("</td><td>").Append(m.At.ToString("u")).Append("</td></tr>");
        sb.Append("</table>");
        if (r.Predictions.Items.Count > 0)
        {
            var t = r.Predictions.Tally;
            sb.Append("<h2>Pre-registered predictions (model calibration, not evidence)</h2><p class=\"muted\">").Append(E(r.Predictions.Label)).Append("</p><p><b>")
              .Append(t.Confirmed).Append(" confirmed · ").Append(t.Falsified).Append(" falsified · ").Append(t.Unscorable).Append(" unscorable · ").Append(t.Pending).Append(" pending</b></p>");
            sb.Append("<table><tr><th>Prediction</th><th>Diagnostic</th><th>Predicted (inferred)</th><th>Measured</th><th>Outcome</th><th>Cited facts</th><th>Registered</th></tr>");
            foreach (var p in r.Predictions.Items)
                sb.Append("<tr><td>").Append(E(p.PredictionId)).Append("</td><td>").Append(E(p.DiagnosticId)).Append("</td><td>").Append(E(p.PredictedValue)).Append("</td><td>").Append(E(p.MeasuredValue ?? "—"))
                  .Append("</td><td><b>").Append(E(p.Outcome)).Append("</b></td><td>").Append(E(string.Join(", ", p.BasedOnFactIds))).Append("</td><td>").Append(p.RegisteredAt.ToString("u")).Append("</td></tr>");
            sb.Append("</table>");
        }
        if (r.Proposals.Accepted + r.Proposals.Rejected.Count > 0)
        {
            sb.Append("<h2>Model proposals (checked by the server)</h2><p class=\"muted\">").Append(E(r.Proposals.Label)).Append("</p><p><b>").Append(r.Proposals.Accepted).Append(" accepted · ").Append(r.Proposals.Rejected.Count).Append(" rejected</b></p>");
            if (r.Proposals.Rejected.Count > 0)
            {
                sb.Append("<table><tr><th>Proposal</th><th>Asked for</th><th>Refused because</th><th>Model</th><th>At</th></tr>");
                foreach (var x in r.Proposals.Rejected)
                    sb.Append("<tr><td>").Append(E(x.ProposalId)).Append("</td><td>").Append(E(x.NextAction ?? "unreadable output")).Append("</td><td>").Append(E(string.Join(", ", x.Codes))).Append("</td><td>").Append(E(x.ModelId)).Append("</td><td>").Append(x.At.ToString("u")).Append("</td></tr>");
                sb.Append("</table>");
            }
        }
        if (r.Knowledge is { } k)
        {
            sb.Append("<h2>Documentation (guidance, not evidence)</h2><p>Pack <code>").Append(E(k.PackId)).Append("</code> rev ").Append(k.PackRevision).Append(" sha256 <code>").Append(E(k.PackSha256)).Append("</code>, status ").Append(E(k.Status)).Append("</p><table><tr><th>Fact</th><th>Provenance</th><th>Statement</th><th>Source</th></tr>");
            foreach (var c in k.Citations)
                sb.Append("<tr><td>").Append(E(c.FactId)).Append("</td><td>").Append(E(c.Provenance)).Append("</td><td>").Append(E(c.Statement)).Append("</td><td>").Append(E(c.DocumentTitle)).Append(" — ").Append(E(c.SourceLocator)).Append(c.SourceUrl is null ? "" : $" (<a href=\"{E(c.SourceUrl)}\">link</a>)").Append(", accessed ").Append(c.AccessedOn.ToString("yyyy-MM-dd")).Append("</td></tr>");
            sb.Append("</table>");
        }
        sb.Append("<h2>Event trail (append-only)</h2><table><tr><th>#</th><th>Event</th><th>At</th></tr>");
        foreach (var ev in r.Events) sb.Append("<tr><td>").Append(ev.Sequence).Append("</td><td>").Append(E(ev.EventKind)).Append(ev is StageChanged sc ? $" {E(sc.From.ToString())} → {E(sc.To.ToString())}: {E(sc.Reason)}" : "").Append("</td><td>").Append(ev.OccurredAt.ToString("u")).Append("</td></tr>");
        sb.Append("</table><h2>Scope and limits</h2>");
        foreach (var dsc in r.Disclaimers) sb.Append("<p class=\"disc\">").Append(E(dsc)).Append("</p>");
        sb.Append("</body></html>");
        return sb.ToString();
    }
}
