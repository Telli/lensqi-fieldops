import { useState } from "react";
import { Link, useLocation } from "wouter";
import { Camera, Terminal, Wrench, Loader2, BookOpen, ShieldCheck, Sparkles, FileCheck2, RotateCcw, Images } from "lucide-react";
import { Button } from "@/components/ui/button";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { EvidenceBadge, Expandable, TechDetails } from "@/components/fo/primitives";
import { ArFrame } from "@/components/fo/ar-frame";
import { AiCard, Progress } from "@/components/fo/ai";
import { api } from "@/lib/api";
import { aiSummary, nextMove, progress } from "@/lib/ai";
import { arTags, outlineOrder, targetConcept, usePhotoOutlines } from "@/lib/ar";
import { useAct } from "@/lib/hooks";
import { lastPhoto, latestDiagnostic, useInspectionBundle } from "@/lib/inspection";
import { featureName, fmtWhen, isPostRepairItem, observationState, stageLabel, stageState } from "@/lib/labels";

/**
 * The inspection hub: the latest photo with LensQi's outlines first, then one AI card that says what it sees and offers
 * the single next move. Everything else (findings, check, knowledge, ids) is folded away below.
 */
export default function Investigate({ id }: { id: string }) {
  const { i, fixture, assessment, photoUrl } = useInspectionBundle(id);
  const [, nav] = useLocation();
  const [text, setText] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const latest = lastPhoto(i);
  const outlines = usePhotoOutlines(id, i, latest?.evidenceId);
  const diag = latestDiagnostic(i);
  const look = useAct(id, async () => {
    const inv = await api.investigate(id);
    let state = inv.state;
    for (const c of outlineOrder(fixture, inv.assessment)) state = (await api.segment(id, c)).state;
    return state;
  });
  const workOrder = useAct(id, (s: string) => api.workOrder(id, s), { success: "Plan saved" });
  const claim = useAct(id, (d: string) => api.claim(id, d));

  if (!i) return <><TopBar title="Inspection" back="/m" /><Page><Loader2 className="mx-auto animate-spin" /></Page></>;

  const asset = fixture?.assets.find((a) => a.assetId === i.assetId);
  const shown = i.currentClaimId ? undefined : assessment;
  const move = nextMove(i, assessment);
  const target = targetConcept(fixture, shown);
  const findings = shown?.hypotheses.filter((h) => !isPostRepairItem(h.featureId)) ?? [];
  const busy = look.isPending || workOrder.isPending || claim.isPending;

  const action = (() => {
    switch (move?.kind) {
      case "scan": return <Button asChild size="lg" className="h-12 w-full rounded-2xl"><Link href={`/m/i/${id}/scan`}><Camera /> Open camera</Link></Button>;
      case "assess": return <Button size="lg" className="h-12 w-full rounded-2xl" disabled={busy} onClick={() => look.mutate([])}>{look.isPending ? <Loader2 className="animate-spin" /> : <Sparkles />} {look.isPending ? "Looking…" : "Look at the photo"}</Button>;
      case "check": return <Button asChild size="lg" className="h-12 w-full rounded-2xl"><Link href={`/m/i/${id}/diagnostics`}><Terminal /> Run the check</Link></Button>;
      case "plan": case "repair": return (
        <>
          <input value={text} onChange={(e) => setText(e.target.value)} placeholder={move.kind === "plan" ? "e.g. Reseat the yellow cable in port 2" : "What did you do?"}
            className="h-12 w-full rounded-2xl border border-input bg-background px-4 text-sm outline-none focus:border-primary" />
          <Button size="lg" className="h-12 w-full rounded-2xl" disabled={!text.trim() || busy}
            onClick={() => move.kind === "plan"
              ? workOrder.mutate([text.trim()], { onSuccess: () => setText("") })
              : claim.mutate([text.trim()], { onSuccess: () => nav(`/m/i/${id}/verify`) })}>
            {busy ? <Loader2 className="animate-spin" /> : <Wrench />} {move.kind === "plan" ? "Save plan" : "Repair done — verify"}
          </Button>
        </>
      );
      case "verify": case "retry": return <Button asChild size="lg" className="h-12 w-full rounded-2xl"><Link href={`/m/i/${id}/verify`}>{move.kind === "retry" ? <RotateCcw /> : <ShieldCheck />} {move.kind === "retry" ? "Try again" : "Go to verification"}</Link></Button>;
      case "receipt": return <Button asChild size="lg" className="h-12 w-full rounded-2xl"><Link href={`/m/i/${id}/receipt`}><FileCheck2 /> View receipt</Link></Button>;
      default: return null;
    }
  })();

  return (
    <>
      <TopBar title={asset?.displayName ?? i.assetId} back="/m" right={<EvidenceBadge state={stageState[i.stage]} label={stageLabel[i.stage]} />} />
      <Page className="space-y-4">
        <div className="relative overflow-hidden rounded-[26px] bg-overlay shadow-lg shadow-black/15">
          {latest && photoUrl(latest.evidenceId)
            ? <ArFrame src={photoUrl(latest.evidenceId)!} outlines={outlines} tags={arTags(fixture, shown, outlines)} selected={selected} onSelect={setSelected}
                guide={target ? { concept: target, label: "Target region" } : null} />
            : <Link href={`/m/i/${id}/scan`} className="grid aspect-[4/3] place-items-center text-overlay-foreground/70"><span className="flex flex-col items-center gap-2 text-sm"><Camera className="size-9" />Tap to open the camera</span></Link>}
          {latest && (
            <Link href={`/m/i/${id}/scan`} aria-label="New photo" className="glass absolute bottom-3 right-3 grid size-11 place-items-center rounded-full shadow-lg"><Camera className="size-5" /></Link>
          )}
          {i.photos.length > 1 && <span className="glass pointer-events-none absolute left-3 top-3 flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px]"><Images className="size-3" />{i.photos.length}</span>}
        </div>

        <AiCard says={aiSummary(i, shown)} title={move?.title} hint={move?.hint}>{action}</AiCard>

        <Progress step={progress(i, assessment)} />

        <div className="space-y-2 pt-1">
          {findings.length > 0 && (
            <Expandable title="What LensQi found">
              <ul className="space-y-2.5">
                {findings.map((h) => (
                  <li key={h.featureId}>
                    <div className="flex flex-wrap items-center gap-2"><span className="font-medium text-foreground">{featureName(h.featureId)}</span><EvidenceBadge state={observationState[h.status].state} label={observationState[h.status].label} /></div>
                    <p className="mt-0.5 text-xs">{h.reason}</p>
                  </li>
                ))}
              </ul>
              <p className="mt-3 text-[11px]">Suggestions, not evidence. Your review and the equipment check decide.</p>
            </Expandable>
          )}
          <Expandable title={<span className="flex items-center gap-2">Equipment check{diag && <span className="text-xs font-normal text-muted-foreground">· {diag.result.passed === true ? "passing" : diag.result.passed === false ? "failing" : "no answer"} · {fmtWhen(diag.result.completedAt)}</span>}</span>}>
            <Link href={`/m/i/${id}/diagnostics`} className="inline-flex items-center gap-1.5 text-primary"><Terminal className="size-4" /> {diag ? "Open the approved check" : "Run the approved check"}</Link>
          </Expandable>
          <Expandable title="Equipment and documentation">
            <div className="flex flex-col gap-2">
              <Link href={`/m/i/${id}/asset`} className="text-primary">Expected installation state</Link>
              <Link href={`/m/i/${id}/knowledge`} className="inline-flex items-center gap-1.5 text-primary"><BookOpen className="size-4" /> Vendor knowledge</Link>
            </div>
          </Expandable>
          <TechDetails rows={[
            ["Inspection", i.inspectionId], ["Fixture", `${i.fixtureId} rev ${i.fixtureRevision}`], ["Fixture digest", i.fixtureDigest],
            ["AI model", assessment?.modelId ?? "—"], ["Events", String(i.lastSequence)],
          ]} />
        </div>
      </Page>
    </>
  );
}
