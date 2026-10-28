import { useState } from "react";
import { Link } from "wouter";
import { OpsPage } from "@/components/fo/ops-shell";
import { EvidenceBadge, Section, CheckRow, TechDetails, SourceBadge } from "@/components/fo/primitives";
import { EvidencePhoto } from "@/components/fo/evidence-photo";
import { FieldProofReceipt } from "@/pages/m/receipt";
import { useFixtures, useInspections } from "@/lib/hooks";
import { lastPhoto, latestDiagnostic, useGeometry, useInspectionBundle, verifications } from "@/lib/inspection";
import { changeLabel, featureName, fmtWhen, isPostRepairItem, observationState, stageLabel, stageState } from "@/lib/labels";
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

export function InspectionWorkspace({ id }: { id: string }) {
  const { i, ev, fixture, assessment, photoUrl } = useInspectionBundle(id);
  const photo = lastPhoto(i);
  const geometry = useGeometry(id, i, photo?.evidenceId);
  const diag = latestDiagnostic(i);
  const last = verifications(ev).at(-1)?.["decision"] as Decision | undefined;
  const asset = fixture?.assets.find((a) => a.assetId === i?.assetId);
  if (!i) return <OpsPage title="Inspection">Loading…</OpsPage>;
  const timeline = ev.filter((e) => ["PhotoRecorded", "AssessmentRecorded", "SegmentationRecorded", "DiagnosticCompleted", "WorkOrderDrafted", "RepairClaimed", "VisualChangeAnalyzed", "VerificationEvaluated"].includes(e.kind));
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
      default: return e.kind;
    }
  };
  return (
    <OpsPage title={asset?.displayName ?? i.assetId} sub={`${i.fixtureId} · ${stageLabel[i.stage]}`} actions={<EvidenceBadge state={stageState[i.stage]} label={stageLabel[i.stage]} />}>
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="panel relative overflow-hidden">
          {photo && photoUrl(photo.evidenceId) ? <EvidencePhoto src={photoUrl(photo.evidenceId)!} geometry={geometry.data} className="bg-overlay" imgClassName="h-[420px] object-contain" /> : <div className="grid h-[420px] place-items-center text-sm text-muted-foreground">No photo yet</div>}
          <div className="glass absolute bottom-3 left-3 rounded-md px-2 py-1 text-xs">{i.photos.length} photos{geometry.data?.matched ? ` · AI outline: ${geometry.data.concept}` : ""}</div>
        </div>
        <div className="space-y-4">
          <Section title="AI observations">
            {assessment ? assessment.hypotheses.filter((h) => !isPostRepairItem(h.featureId)).map((h) => (
              <div key={h.featureId} className="panel p-4">
                <div className="flex justify-between gap-2"><span className="text-sm font-medium">{featureName(h.featureId)}</span><EvidenceBadge state={observationState[h.status].state} label={observationState[h.status].label} /></div>
                <p className="mt-1 text-xs text-muted-foreground">{h.reason}</p>
              </div>
            )) : <p className="text-sm text-muted-foreground">No AI observation yet.</p>}
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
