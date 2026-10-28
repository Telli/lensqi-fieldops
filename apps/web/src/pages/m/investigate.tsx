import { useState } from "react";
import { Link, useLocation } from "wouter";
import { Camera, Terminal, Wrench, Loader2, BookOpen, ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { EvidenceBadge, Expandable, Citation, TechDetails } from "@/components/fo/primitives";
import { ArFrame } from "@/components/fo/ar-frame";
import { arTags, outlineOrder, targetConcept, usePhotoOutlines } from "@/lib/ar";
import { api } from "@/lib/api";
import { useAct, useKnowledge } from "@/lib/hooks";
import { lastPhoto, latestDiagnostic, useInspectionBundle } from "@/lib/inspection";
import { featureName, fmtWhen, isPostRepairItem, observationState, sentence, stageLabel, stageState } from "@/lib/labels";
import { cn } from "@/lib/utils";

type StepView = { title: string; done: boolean; body: React.ReactNode; meta?: string; extra?: React.ReactNode };

export default function Investigate({ id }: { id: string }) {
  const { i, ev, fixture, assessment, photoUrl } = useInspectionBundle(id);
  const knowledge = useKnowledge(id);
  const [, nav] = useLocation();
  const [plan, setPlan] = useState("");
  const [done, setDone] = useState("");
  const latest = lastPhoto(i);
  const outlines = usePhotoOutlines(id, i, latest?.evidenceId);
  const [selected, setSelected] = useState<string | null>(null);
  const diag = latestDiagnostic(i);
  const assessments = ev.filter((e) => e.kind === "AssessmentRecorded");
  // Outline every part the site expects, repair target last (later photos are compared against the last outline).
  const segment = useAct(id, async (concepts: string[]) => { let r; for (const c of concepts) r = await api.segment(id, c); return r!; });
  const workOrder = useAct(id, (s: string) => api.workOrder(id, s), { success: "Work order drafted" });
  const claim = useAct(id, (d: string) => api.claim(id, d));

  if (!i) return <><TopBar title="Investigation" back="/m" /><Page><Loader2 className="mx-auto animate-spin" /></Page></>;

  const asset = fixture?.assets.find((a) => a.assetId === i.assetId);
  const needsView = assessment?.nextAction === "RequestView" && i.stage === "NeedsEvidence";
  const relevant = assessment?.hypotheses.filter((h) => !(isPostRepairItem(h.featureId) && !i?.currentClaimId)) ?? [];
  const unclear = relevant.filter((h) => h.status !== "Observed");
  const order = outlineOrder(fixture, assessment);
  const target = needsView ? targetConcept(fixture, assessment) : undefined;
  const canRepair = i.stage === "FaultConfirmed" || i.stage === "FaultSuspected" || i.stage === "AwaitingRepair";

  const steps: StepView[] = [
    {
      title: "Initial observation",
      done: assessments.length > 0,
      body: assessments.length > 0
        ? `LensQi looked at ${i.photos.length} photo${i.photos.length === 1 ? "" : "s"}: ${relevant.length - unclear.length} part${relevant.length - unclear.length === 1 ? "" : "s"} visible${unclear.length ? `, ${unclear.length} unclear` : ""}.`
        : i.photos.length ? "Photo saved. The AI has not described it yet." : "Capture a wide photo of the equipment.",
      meta: assessment ? fmtWhen(assessment.at) : undefined,
      extra: i.photos.length === 0 && <Button asChild className="mt-3 h-11 w-full"><Link href={`/m/i/${id}/scan`}><Camera /> Open camera</Link></Button>,
    },
    {
      title: "Hypothesis",
      done: !!assessment,
      body: assessment ? (
        <ul className="mt-1 space-y-2">
          {relevant.map((h) => (
            <li key={h.featureId}>
              <div className="flex flex-wrap items-center gap-2"><span className="font-medium">{featureName(h.featureId)}</span><EvidenceBadge state={observationState[h.status].state} label={observationState[h.status].label} /></div>
              <div className="mt-0.5 text-xs text-muted-foreground">{h.reason}</div>
            </li>
          ))}
        </ul>
      ) : "Waiting for the first observation.",
      meta: assessment ? "AI suggestions, not evidence. Your review and the equipment check decide." : undefined,
    },
    {
      title: "Evidence requested",
      done: !!assessment && !needsView,
      body: needsView ? sentence(assessment!.requestedView) : assessment ? "No extra view needed right now." : "—",
      meta: needsView ? "Required to continue" : undefined,
      extra: needsView && (
        <div className="panel-elevated mt-3 overflow-hidden">
          {latest && photoUrl(latest.evidenceId) && <img src={photoUrl(latest.evidenceId)} alt="Latest photo" className="h-36 w-full object-cover" />}
          <div className="p-3">
            <div className="text-sm font-medium">Next photo</div>
            <div className="text-xs text-muted-foreground">{sentence(assessment!.requestedView)}</div>
            <Button asChild className="mt-3 h-11 w-full"><Link href={`/m/i/${id}/scan`}><Camera /> Capture another view</Link></Button>
          </div>
        </div>
      ),
    },
    {
      title: "Parts outlined",
      done: outlines.length > 0,
      body: outlines.length ? `AI outlines: ${outlines.map((g) => g.concept).join(", ")}. Tap a label for the AI's reason.` : "Outline the parts in the latest photo.",
      extra: latest && photoUrl(latest.evidenceId) ? (
        outlines.length
          ? <ArFrame src={photoUrl(latest.evidenceId)!} outlines={outlines} tags={arTags(fixture, assessment, outlines)} selected={selected} onSelect={setSelected}
              guide={target ? { concept: target, label: "Target region" } : null} className="panel mt-3" />
          : <Button variant="outline" className="mt-3 h-11 w-full" disabled={segment.isPending || !order.length} onClick={() => segment.mutate([order])}>
              {segment.isPending ? <Loader2 className="animate-spin" /> : <ShieldCheck />} Outline the parts
            </Button>
      ) : null,
    },
    {
      title: "Equipment check",
      done: !!diag,
      body: diag ? (diag.result.passed === false ? "The approved check failed. That matches a real fault." : diag.result.passed ? "The approved check passed." : "The check could not reach the endpoint.") : "Run the approved check to confirm the fault.",
      meta: diag ? fmtWhen(diag.result.completedAt) : undefined,
      extra: <Button asChild variant="outline" className="mt-3 h-11 w-full"><Link href={`/m/i/${id}/diagnostics`}><Terminal /> {diag ? "Review equipment check" : "Open approved checks"}</Link></Button>,
    },
    {
      title: "Repair",
      done: !!i.currentClaimId,
      body: i.currentClaimId ? "Repair claimed. Verify it with a new photo and a fresh check." : canRepair ? "Plan the fix, do it, then tell LensQi it's done." : "Available once the fault is confirmed.",
      extra: canRepair && !i.currentClaimId ? (
        <div className="mt-3 space-y-2">
          {i.stage !== "AwaitingRepair" && (
            <div className="flex gap-2">
              <input value={plan} onChange={(e) => setPlan(e.target.value)} placeholder="Plan, e.g. reseat the yellow cable in port 2"
                className="h-11 flex-1 rounded-lg border border-input bg-background px-3 text-sm outline-none focus:border-primary" />
              <Button className="h-11" disabled={!plan.trim() || workOrder.isPending} onClick={() => workOrder.mutate([plan.trim()])}>Save</Button>
            </div>
          )}
          {i.stage === "AwaitingRepair" && (
            <>
              <input value={done} onChange={(e) => setDone(e.target.value)} placeholder="What did you do?"
                className="h-11 w-full rounded-lg border border-input bg-background px-3 text-sm outline-none focus:border-primary" />
              <Button size="lg" className="h-12 w-full" disabled={!done.trim() || claim.isPending}
                onClick={() => claim.mutate([done.trim()], { onSuccess: () => nav(`/m/i/${id}/verify`) })}>
                {claim.isPending ? <Loader2 className="animate-spin" /> : <Wrench />} Repair completed — verify
              </Button>
            </>
          )}
        </div>
      ) : i.currentClaimId ? <Button asChild size="lg" className="mt-3 h-12 w-full"><Link href={`/m/i/${id}/verify`}><ShieldCheck /> Go to verification</Link></Button> : null,
    },
  ];
  const active = steps.findIndex((x) => !x.done);
  const citation = knowledge.data?.facts[0]?.citation;

  return (
    <>
      <TopBar title="Investigation" back="/m" right={<EvidenceBadge state={stageState[i.stage]} label={stageLabel[i.stage]} />} />
      <Page>
        <Link href={`/m/i/${id}/asset`} className="block text-xs text-muted-foreground hover:text-foreground">{asset?.displayName ?? i.assetId} · {i.fixtureId} ›</Link>
        <ol className="relative space-y-4">
          {steps.map((st, k) => (
            <li key={st.title} className="relative flex gap-3">
              <div className="flex flex-col items-center">
                <span className={cn("grid size-6 place-items-center rounded-full border text-[11px] font-mono",
                  st.done ? "border-primary bg-primary text-primary-foreground" : k === active ? "border-warning text-warning animate-ring" : "border-border text-muted-foreground")}>{k + 1}</span>
                {k < steps.length - 1 && <span className={cn("mt-1 w-px flex-1", st.done ? "bg-primary/50" : "bg-border")} />}
              </div>
              <div className="min-w-0 flex-1 pb-1">
                <div className="eyebrow">{st.title}</div>
                <div className="mt-1 text-sm">{st.body}</div>
                {st.meta && <div className="mt-0.5 text-xs text-muted-foreground">{st.meta}</div>}
                {st.extra}
              </div>
            </li>
          ))}
        </ol>

        {needsView && (
          <Expandable title="Why LensQi needs this photo">
            {unclear.map((h) => <p key={h.featureId} className="mb-1"><span className="text-foreground">{featureName(h.featureId)}:</span> {h.reason}</p>)}
            <p className="mt-2 text-xs">This is the AI's stated reason, not a transcript of model internals.</p>
          </Expandable>
        )}
        {citation && (
          <Link href={`/m/i/${id}/knowledge`}><Citation title={citation.documentTitle} section={citation.sourceLocator} /></Link>
        )}
        <Button asChild variant="outline" className="h-11 w-full"><Link href={`/m/i/${id}/knowledge`}><BookOpen /> Vendor knowledge</Link></Button>
        <TechDetails rows={[
          ["Inspection", i.inspectionId], ["Fixture", `${i.fixtureId} rev ${i.fixtureRevision}`], ["Fixture digest", i.fixtureDigest],
          ["AI model", assessment?.modelId ?? "—"], ["Assessment schema", assessment?.schemaVersion ?? "—"], ["Events", String(i.lastSequence)],
        ]} />
      </Page>
    </>
  );
}
