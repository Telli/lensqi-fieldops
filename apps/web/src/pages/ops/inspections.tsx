import { useState } from "react";
import { Link } from "wouter";
import { OpsPage } from "@/components/fo/ops-shell";
import { EvidenceBadge, Section, CheckRow, TechDetails, SourceBadge } from "@/components/fo/primitives";
import { ArFrame } from "@/components/fo/ar-frame";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { arTags, usePhotoOutlines } from "@/lib/ar";
import { useAct } from "@/lib/hooks";
import { FieldProofReceipt } from "@/pages/m/receipt";
import { useFixtures, useInspections } from "@/lib/hooks";
import { lastPhoto, latestDiagnostic, useInspectionBundle, verifications } from "@/lib/inspection";
import { changeLabel, checklistLabel, featureName, fmtWhen, isPostRepairItem, observationState, stageLabel, stageState } from "@/lib/labels";
import type { Decision, Stage } from "@/lib/api";
import { cn } from "@/lib/utils";

const filters: { label: string; stages?: Stage[] }[] = [
  { label: "All" },
  { label: "Open", stages: ["Created", "InitialScan", "NeedsEvidence", "FaultSuspected", "FaultConfirmed", "AwaitingRepair"] },
  { label: "To verify", stages: ["ReadyToVerify", "VerificationFailed"] },
  { label: "Verified", stages: ["VerifiedComplete"] },
];

export function InspectionsList() {
  const list = useInspections();
  const fixtures = useFixtures();
  const [f, setF] = useState(0);
  const nameOf = (id: string) => fixtures.data?.flatMap((x) => x.assets).find((a) => a.assetId === id)?.displayName ?? id;
  const rows = (list.data ?? []).filter((s) => !filters[f]!.stages || filters[f]!.stages!.includes(s.stage));
  return (
    <OpsPage title="Inspections" sub={`${list.data?.length ?? 0} recorded`}>
      <div className="flex gap-1.5">
        {filters.map((x, k) => (
          <button key={x.label} onClick={() => setF(k)} className={cn("rounded-full border px-3 py-1 text-xs", k === f ? "border-primary/50 bg-primary/10 text-primary" : "border-border text-muted-foreground")}>{x.label}</button>
        ))}
      </div>
      <div className="panel overflow-x-auto">
        <table className="w-full text-sm">
          <thead><tr className="border-b border-border text-left text-xs text-muted-foreground">
            {["Asset", "Work", "State", "Photos", "Checks", "Change evidence", "Updated"].map((h) => <th key={h} className="px-4 py-2.5 font-medium">{h}</th>)}
          </tr></thead>
          <tbody>
            {rows.map((s) => (
              <tr key={s.inspectionId} className="border-b border-border/60 last:border-0 hover:bg-muted/40">
                <td className="px-4 py-2.5"><Link href={`/ops/inspections/${s.inspectionId}`} className="font-medium hover:text-primary">{nameOf(s.assetId)}</Link></td>
                <td className="max-w-[260px] truncate px-4 py-2.5 text-muted-foreground">{s.workOrder ?? "—"}</td>
                <td className="px-4 py-2.5"><EvidenceBadge state={stageState[s.stage]} label={stageLabel[s.stage]} /></td>
                <td className="px-4 py-2.5 tabular-nums">{s.photos}</td>
                <td className="px-4 py-2.5 tabular-nums">{s.diagnostics}</td>
                <td className="px-4 py-2.5 text-xs">{s.latestChange ? changeLabel[s.latestChange].label : "—"}</td>
                <td className="px-4 py-2.5 text-xs text-muted-foreground">{fmtWhen(s.updatedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </OpsPage>
  );
}

const originName: Record<string, string> = { Phone: "Camera", Glasses: "Glasses", MockWearables: "Mock glasses (simulated)", RecordedFixture: "Imported" };

export function InspectionWorkspace({ id }: { id: string }) {
  const { i, ev, fixture, assessment, photoUrl } = useInspectionBundle(id);
  const [picked, setPicked] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [note, setNote] = useState("");
  const photo = i?.photos.find((p) => p.evidenceId === picked) ?? lastPhoto(i);
  const outlines = usePhotoOutlines(id, i, photo?.evidenceId);
  const request = useAct(id, (n: string) => api.requestEvidence(id, n), { success: "Request sent to the technician" });
  const diag = latestDiagnostic(i);
  const last = verifications(ev).at(-1)?.["decision"] as Decision | undefined;
  const asset = fixture?.assets.find((a) => a.assetId === i?.assetId);
  if (!i) return <OpsPage title="Inspection">Loading…</OpsPage>;
  const change = i.changeAnalyses.find((c) => c.photoEvidenceId === photo?.evidenceId);
  const preClaim = !i.repairClaimedAt || (photo && new Date(photo.observedAt) <= new Date(i.repairClaimedAt));
  const tags = arTags(fixture, preClaim ? assessment : undefined, outlines, change);
  const open = i.evidenceRequests?.filter((r) => !r.answeredByPhotoId) ?? [];
  const closed = i.stage === "VerifiedComplete" || i.stage === "Inconclusive";
  const timeline = ev.filter((e) => ["PhotoRecorded", "AssessmentRecorded", "SegmentationRecorded", "DiagnosticCompleted", "WorkOrderDrafted", "RepairClaimed", "VisualChangeAnalyzed", "VerificationEvaluated", "EvidenceRequested"].includes(e.kind));
  const describe = (e: (typeof ev)[number]): string => {
    switch (e.kind) {
      case "PhotoRecorded": return `Photo · ${({ Phone: "camera", Glasses: "glasses", MockWearables: "mock glasses (simulated)" } as Record<string, string>)[String(e["origin"])] ?? "imported"}`;
      case "AssessmentRecorded": return "AI observation";
      case "SegmentationRecorded": return e["matched"] ? `Outlined ${e["concept"]}` : `No ${e["concept"]} found`;
      case "DiagnosticCompleted": { const r = e["result"] as { passed: boolean | null; source: string }; return `Check ${r.passed === true ? "passed" : r.passed === false ? "failed" : "unavailable"} · ${r.source}`; }
      case "WorkOrderDrafted": return `Work order: ${e["summary"]}`;
      case "RepairClaimed": return `Repair claimed: ${e["description"]}`;
      case "VisualChangeAnalyzed": return `Change evidence: ${changeLabel[(e["analysis"] as { verdict: keyof typeof changeLabel }).verdict].label}`;
      case "VerificationEvaluated": return (e["decision"] as Decision).result === "Verified" ? "Verification accepted" : "Verification rejected";
      case "EvidenceRequested": return `Evidence requested: ${e["note"]}`;
      default: return e.kind;
    }
  };
  return (
    <OpsPage title={asset?.displayName ?? i.assetId} sub={`${i.fixtureId} · ${stageLabel[i.stage]}`}
      actions={<div className="flex items-center gap-2"><Link href={`/m/i/${id}`} className="text-xs text-primary">Technician view</Link><EvidenceBadge state={stageState[i.stage]} label={stageLabel[i.stage]} /></div>}>
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="space-y-3">
          <div className="panel relative overflow-hidden bg-overlay">
            {photo && photoUrl(photo.evidenceId)
              ? <ArFrame src={photoUrl(photo.evidenceId)!} outlines={outlines} tags={tags} selected={selected} onSelect={setSelected} imgClassName="max-h-[420px] object-contain" />
              : <div className="grid h-[420px] place-items-center text-sm text-muted-foreground">No photo yet</div>}
          </div>
          {/* Evidence strip: every photo on the record, with the hash that the receipt seals */}
          <div className="flex gap-2 overflow-x-auto pb-1">
            {i.photos.map((p, k) => {
              const c = i.changeAnalyses.find((x) => x.photoEvidenceId === p.evidenceId);
              return (
                <button key={p.evidenceId} onClick={() => { setPicked(p.evidenceId); setSelected(null); }}
                  className={cn("panel w-36 shrink-0 overflow-hidden text-left", p.evidenceId === photo?.evidenceId ? "border-primary/60 ring-1 ring-primary/40" : "hover:border-primary/30")}>
                  {photoUrl(p.evidenceId) ? <img src={photoUrl(p.evidenceId)} alt="" className="h-20 w-full object-cover" /> : <div className="h-20" />}
                  <div className="space-y-0.5 p-2 text-[11px]">
                    <div className="font-medium">#{k + 1} · {originName[p.origin] ?? p.origin}</div>
                    <div className="font-mono text-muted-foreground">{p.artifactSha256.slice(0, 12)}…</div>
                    {c && <div className={c.verdict === "Changed" ? "text-success" : c.verdict === "Unscorable" ? "text-warning" : "text-failure"}>{changeLabel[c.verdict].label}</div>}
                  </div>
                </button>
              );
            })}
          </div>
          {photo && (
            <dl className="panel grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 p-3 text-xs">
              <dt className="text-muted-foreground">Photo</dt><dd>{photo.evidenceId} · {originName[photo.origin] ?? photo.origin}</dd>
              <dt className="text-muted-foreground">Taken</dt><dd>{fmtWhen(photo.observedAt)}</dd>
              <dt className="text-muted-foreground">SHA-256</dt><dd className="break-all font-mono">{photo.artifactSha256}</dd>
              {change && <><dt className="text-muted-foreground">Change check</dt><dd>{changeLabel[change.verdict].label} · {change.reason}</dd></>}
            </dl>
          )}
        </div>
        <div className="space-y-4">
          <Section title="Ask the technician">
            <div className="panel space-y-2.5 p-4">
              {open.map((r) => <div key={r.requestId} className="rounded-lg border border-warning/40 bg-warning/10 p-2.5 text-sm"><div className="text-xs text-warning">Waiting for a photo · sent {fmtWhen(r.requestedAt)}</div>{r.note}</div>)}
              {(i.evidenceRequests ?? []).filter((r) => r.answeredByPhotoId).slice(-2).map((r) => (
                <button key={r.requestId} onClick={() => setPicked(r.answeredByPhotoId!)} className="block w-full rounded-lg border border-border p-2.5 text-left text-sm hover:border-primary/40">
                  <div className="text-xs text-success">Answered {fmtWhen(r.answeredAt)} · show photo</div>{r.note}
                </button>
              ))}
              {closed ? <p className="text-xs text-muted-foreground">This inspection is closed; requests are no longer accepted.</p> : (
                <>
                  <textarea value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} rows={2} placeholder="e.g. Show the label on port 2 up close"
                    className="w-full resize-none rounded-lg border border-input bg-background px-3 py-2 text-sm outline-none focus:border-primary" />
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-[11px] text-muted-foreground">Shown on the technician's phone; the next photo answers it. It changes no decision.</span>
                    <Button size="sm" disabled={!note.trim() || request.isPending} onClick={() => request.mutate([note.trim()], { onSuccess: () => setNote("") })}>Request evidence</Button>
                  </div>
                </>
              )}
            </div>
          </Section>
          <Section title="AI observations">
            {assessment ? assessment.hypotheses.filter((h) => !isPostRepairItem(h.featureId)).map((h) => (
              <div key={h.featureId} className="panel p-4">
                <div className="flex justify-between gap-2"><span className="text-sm font-medium">{featureName(h.featureId)}</span><EvidenceBadge state={observationState[h.status].state} label={observationState[h.status].label} /></div>
                <p className="mt-1 text-xs text-muted-foreground">{h.reason}</p>
              </div>
            )) : <p className="text-sm text-muted-foreground">No AI observation yet.</p>}
          </Section>
          <Section title="Required evidence">
            <div className="panel divide-y divide-border">
              {(fixture?.visualRequirements ?? []).map((r) => {
                const c = i.checklist.find((x) => x.requirementId === r.requirementId);
                return (
                  <div key={r.requirementId} className="flex items-center justify-between gap-3 px-4 py-2.5 text-sm">
                    <span>{featureName(r.requirementId)}{r.mandatory && <span className="ml-1.5 text-[11px] text-muted-foreground">required</span>}</span>
                    {c ? <span className={cn("text-xs", c.status === "Satisfied" ? "text-success" : c.status === "NotSatisfied" ? "text-failure" : "text-warning")}>
                          {checklistLabel[c.status]}{c.reviewedByEvidenceId ? <button className="ml-1.5 underline" onClick={() => setPicked(c.reviewedByEvidenceId!)}>photo</button> : null}
                        </span>
                      : <span className="text-xs text-muted-foreground">{i.currentClaimId ? "Not reviewed" : "Reviewed after a repair claim"}</span>}
                  </div>
                );
              })}
            </div>
          </Section>
          <Section title="Verification state">
            <CheckRow label="Visual verification" detail={i.acceptedPostRepairPhotoId ? "Post-repair photo accepted" : i.currentClaimId ? "Waiting for an accepted post-repair photo" : "No repair claimed"} pass={last ? last.result === "Verified" || !!last.reasons[0]?.startsWith("Diagnostic failed") : null} />
            <CheckRow label="Equipment check" detail={diag ? <span className="inline-flex items-center gap-2">{diag.result.passed === true ? "Passing" : diag.result.passed === false ? "Failing" : "Unavailable"} · {fmtWhen(diag.result.completedAt)} <SourceBadge source={diag.source} /></span> : "Not run"} pass={diag ? diag.result.passed : null} />
          </Section>
        </div>
      </div>
      <Section title="Evidence timeline">
        <div className="panel flex gap-4 overflow-x-auto p-4">
          {timeline.map((e) => (
            <div key={e.sequence} className="min-w-[190px] border-l-2 border-primary/50 pl-3 text-sm">
              <div className="text-[11px] text-muted-foreground">{fmtWhen(e.occurredAt)}</div>{describe(e)}
            </div>
          ))}
        </div>
      </Section>
      {i.currentClaimId && <Section title="FieldProof receipt"><FieldProofReceipt id={id} wide /></Section>}
      <TechDetails rows={[["Inspection", i.inspectionId], ["Fixture digest", i.fixtureDigest], ["Events", String(i.lastSequence)]]} />
    </OpsPage>
  );
}
