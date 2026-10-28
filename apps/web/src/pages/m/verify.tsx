import { useState } from "react";
import { Link } from "wouter";
import { Camera, Terminal, RotateCcw, FileCheck2, Wrench, Loader2, UserCheck, Glasses, Lock, ShieldCheck, SlidersHorizontal } from "lucide-react";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { AiCard, Progress } from "@/components/fo/ai";
import { ArFrame } from "@/components/fo/ar-frame";
import { EvidenceBadge, Expandable, SourceBadge, TechDetails } from "@/components/fo/primitives";
import { Button } from "@/components/ui/button";
import { api, type ChecklistStatus, type Decision, type Inspection, type Fixture, type Assessment, type Photo } from "@/lib/api";
import { aiSummary, verifyGateIndex, verifySteps, type VerifyGate } from "@/lib/ai";
import { arTags, usePhotoOutlines } from "@/lib/ar";
import { useAct } from "@/lib/hooks";
import { beforePhoto, latestDiagnostic, useInspectionBundle, verifications } from "@/lib/inspection";
import { changeLabel, checklistLabel, featureName, fmtTime, fmtWhen, plainReason } from "@/lib/labels";
import { cn } from "@/lib/utils";

const DIAG_FAILED = "Diagnostic failed after the repair claim";

/** How the server's decision splits into the two checks the technician sees. */
function split(d?: Decision | null): { visual: boolean | null; diagnostic: boolean | null } {
  if (!d) return { visual: null, diagnostic: null };
  if (d.result === "Verified") return { visual: true, diagnostic: true };
  if (d.reasons[0]?.startsWith(DIAG_FAILED) || d.reasons[0]?.includes("unavailable")) return { visual: true, diagnostic: false };
  return { visual: false, diagnostic: null };
}

/**
 * Repair verification, camera first: the repair photo with the server's change outline (or the before photo until
 * there is one), then one LensQi card at the gate you are at. The server enforces every gate and decides the outcome.
 */
export default function Verify({ id }: { id: string }) {
  const { i, ev, fixture, assessment, photoUrl } = useInspectionBundle(id);
  const [running, setRunning] = useState(false);
  const [retry, setRetry] = useState("");
  const review = useAct(id, (req: string, status: ChecklistStatus, photo: string) => api.review(id, req, status, photo));
  const ack = useAct(id, () => api.acknowledge(id));
  const verify = useAct(id, () => api.verify(id));
  const claim = useAct(id, (d: string) => api.claim(id, d), { success: "New repair attempt recorded" });

  if (!i) return <><TopBar title="Repair verification" back="/m" /><Page><Loader2 className="mx-auto animate-spin" /></Page></>;

  if (!i.currentClaimId)
    return (
      <>
        <TopBar title="Repair verification" back={`/m/i/${id}`} />
        <Page>
          <AiCard says="Nothing to verify yet." title="No repair claimed" hint="Finish the investigation and tell me what you fixed first.">
            <Button asChild size="lg" className="h-12 w-full rounded-2xl"><Link href={`/m/i/${id}`}>Back to the inspection</Link></Button>
          </AiCard>
        </Page>
      </>
    );

  const accepted = i.photos.find((p) => p.evidenceId === i.acceptedPostRepairPhotoId);
  const change = [...i.changeAnalyses].reverse().find((c) => c.claimId === i.currentClaimId);
  const required = fixture?.visualRequirements.filter((v) => v.mandatory) ?? [];
  const reviewed = (req: string) => i.checklist.find((c) => c.requirementId === req && (!c.reviewedByEvidenceId || c.reviewedByEvidenceId === accepted?.evidenceId));
  const allReviewed = required.every((r) => reviewed(r.requirementId));
  const decision = i.stage === "VerificationFailed" || i.stage === "VerifiedComplete" ? i.lastDecision : verify.data?.decision;
  const outcome = split(decision);
  const diag = latestDiagnostic(i);
  const attempts = verifications(ev);
  const ready = i.stage === "ReadyToVerify";
  const decided = !running && !!decision && !ready;
  const asked = i.evidenceRequests?.find((r) => !r.answeredByPhotoId);
  const gate: VerifyGate = !accepted ? "photo" : !allReviewed ? "review" : !i.technicianAcknowledged ? "confirm" : "run";
  const busy = review.isPending || ack.isPending || claim.isPending;
  const btn = "h-12 w-full rounded-2xl";
  const simulated = decision?.outcomeKind === "VerifiedCompleteSimulated" || i.outcomeKind === "VerifiedCompleteSimulated";
  const gateText = decision?.result === "Verified" ? (simulated ? "Complete · simulated check" : "Complete")
    : decision?.result === "NeedsHumanReview" ? "Waiting for a reviewer" : "Not complete";
  const gateTone = decision?.result === "Verified" ? "text-success" : decision?.result === "NeedsHumanReview" ? "text-warning" : "text-failure";

  const start = () => {
    setRunning(true);
    verify.mutate([], { onSettled: () => setTimeout(() => setRunning(false), 600) });
  };

  const reviewRows = (
    <div className="divide-y divide-border">
      {required.map((r) => {
        const cur = reviewed(r.requirementId)?.status;
        return (
          <div key={r.requirementId} className="space-y-2 py-2.5 first:pt-0 last:pb-0">
            <div className="text-sm">{featureName(r.requirementId)}</div>
            <div className="grid grid-cols-3 gap-1.5">
              {(["Satisfied", "NotSatisfied", "Unknown"] as ChecklistStatus[]).map((st) => (
                <button key={st} disabled={!accepted || busy || i.technicianAcknowledged}
                  onClick={() => accepted && review.mutate([r.requirementId, st, accepted.evidenceId])}
                  className={cn("h-9 rounded-xl border text-xs font-medium transition-colors disabled:cursor-default",
                    cur === st ? (st === "Satisfied" ? "border-success/50 bg-success/15 text-success" : st === "NotSatisfied" ? "border-failure/50 bg-failure/15 text-failure" : "border-warning/50 bg-warning/15 text-warning") : "border-border text-muted-foreground hover:border-primary/40")}>
                  {checklistLabel[st]}
                </button>
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );

  const card = (() => {
    if (running) return <AiCard says="I'm checking the new photo and your review, then the equipment gets a fresh check." title="Verifying…" />;
    if (decided && decision.result === "Verified")
      return (
        <AiCard tone="success" says={aiSummary(i, undefined)} title="Verified complete" hint="Every required check passed.">
          {decision.outcomeKind === "VerifiedCompleteSimulated" && (
            <p className="flex items-center gap-2 text-xs text-muted-foreground"><SourceBadge source="Simulated" /> The equipment check was simulated, not a live measurement.</p>
          )}
          <Button asChild size="lg" className={btn}><Link href={`/m/i/${id}/receipt`}><FileCheck2 /> View FieldProof receipt</Link></Button>
        </AiCard>
      );
    if (decided && decision.result === "Denied")
      return (
        <AiCard tone="failure" says={plainReason(decision.reasons[0] ?? "")} title="Verification failed" hint="This work can't be marked verified. Every attempt stays in the record.">
          <input value={retry} onChange={(e) => setRetry(e.target.value)} placeholder="What did you do this time?"
            className="h-12 w-full rounded-2xl border border-input bg-background px-4 text-sm outline-none focus:border-primary" />
          <Button size="lg" className={btn} disabled={!retry.trim() || claim.isPending} onClick={() => claim.mutate([retry.trim()], { onSuccess: () => { setRetry(""); verify.reset(); } })}>
            <RotateCcw /> Fixed it again — verify
          </Button>
          <Button asChild variant="ghost" className="h-10 w-full"><Link href={`/m/i/${id}/diagnostics`}><Terminal /> Review the equipment check</Link></Button>
        </AiCard>
      );
    if (decided && decision.result === "NeedsHumanReview")
      return <AiCard tone="warning" says={plainReason(decision.reasons[0] ?? "")} title="Needs a decision" hint="A supervisor decides this one; I can't." />;
    switch (gate) {
      case "photo":
        return (
          <AiCard says={aiSummary(i, undefined)} title={asked ? asked.note : "Photograph the repair"}
            hint={asked ? `Requested by the ${asked.requestedBy.toLowerCase()}. Your next photo answers it.` : "Same view as before. The same photo again won't count."}>
            <Button asChild size="lg" className={btn}><Link href={`/m/i/${id}/scan`}><Camera /> Open camera</Link></Button>
            <Button asChild variant="ghost" className="h-10 w-full"><Link href={`/m/i/${id}/glasses`}><Glasses /> Use the glasses</Link></Button>
          </AiCard>
        );
      case "review":
        return <AiCard says={aiSummary(i, undefined)} title="Review the new photo" hint="Mark what you can see yourself. My outline is a suggestion.">{reviewRows}</AiCard>;
      case "confirm":
        return (
          <AiCard says="Your review is in." title="Confirm it's your review" hint="The receipt records that you checked this evidence yourself.">
            <Button size="lg" variant="outline" className={btn} disabled={busy} onClick={() => ack.mutate([])}>{ack.isPending ? <Loader2 className="animate-spin" /> : <UserCheck />} I reviewed this evidence myself</Button>
          </AiCard>
        );
      case "run":
        return (
          <AiCard says="Photo accepted, reviewed and confirmed." title="Ready to verify" hint="I'll check the photo and run a fresh equipment check. Both have to pass.">
            <Button size="lg" className={btn} disabled={!ready} onClick={start}><Wrench /> Run verification</Button>
            {!ready && <p className="text-center text-xs text-muted-foreground">Waiting for the server to accept the evidence.</p>}
          </AiCard>
        );
    }
  })();

  return (
    <>
      <TopBar title="Repair verification" back={`/m/i/${id}`} right={diag && <SourceBadge source={diag.source} />} />
      <Page className="space-y-4">
        <VerifyHero id={id} i={i} fixture={fixture} assessment={assessment} photoUrl={photoUrl} camera={!decided || decision?.result !== "Verified"} />
        {card}
        <Progress step={decided && decision?.result === "Verified" ? 4 : verifyGateIndex[gate]} steps={verifySteps} />

        {(running || decided) && (
          <div className="space-y-2.5">
            <div className="panel p-3" data-dual-proof-gate>
              <div className="mb-2 flex items-center justify-between gap-2 text-[11px]">
                <span className="eyebrow">Both proofs must pass</span>
                <span className={cn("inline-flex items-center gap-1 font-medium", running ? "text-muted-foreground" : gateTone)}>
                  {decision?.result === "Verified" ? <ShieldCheck className="size-3.5" /> : <Lock className="size-3.5" />}{gateText}
                </span>
              </div>
              <div className="grid gap-2 sm:grid-cols-2">
                <ProofKey title="1 · Repair photo" pass={running ? "pending" : outcome.visual}
                  detail={running ? "Checking the new photo and your review…" : outcome.visual === false ? plainReason(decision!.reasons[0] ?? "") : "New photo accepted and every item reviewed."} />
                <ProofKey title="2 · Equipment check" pass={running ? "pending" : outcome.diagnostic} badge={diag && <SourceBadge source={diag.source} />}
                  detail={running ? "Fresh measurement of the approved endpoint…" : outcome.diagnostic === null ? "Not run: the repair photo has to pass first." : outcome.diagnostic ? `Endpoint reachable · ${fmtTime(diag?.result.completedAt)}` : `Endpoint still failing · ${fmtTime(diag?.result.completedAt)}`} />
              </div>
            </div>
          </div>
        )}

        <div className="space-y-2">
          {!decided && accepted && (gate === "confirm" || gate === "run") && <Expandable title={`Your review · ${required.length} items`}>{reviewRows}</Expandable>}
          {attempts.length > 0 && (
            <div className="flex items-center gap-2 px-1 text-xs text-muted-foreground">
              <span>Attempts</span>
              {attempts.map((a) => {
                const ok = (a["decision"] as Decision).result === "Verified";
                return <span key={a.sequence} title={fmtWhen(a.occurredAt)} className={cn("size-2 rounded-full", ok ? "bg-success" : "bg-failure")} />;
              })}
              <span className="ml-auto">all kept in the record</span>
            </div>
          )}
          {decision && (
            <TechDetails rows={[["Decision", decision.result], ["Reason", decision.reasons.join(" ")], ["Policy", decision.policyVersion], ["Outcome", decision.outcomeKind ?? "—"], ["Last attempt", attempts.length ? fmtWhen(attempts[attempts.length - 1]!.occurredAt) : "—"]]} />
          )}
        </div>
      </Page>
    </>
  );
}

/** Before and after on the same frame. "After" is the repair photo with the server's change outline pinned to it. */
function VerifyHero({ id, i, fixture, assessment, photoUrl, camera }: { id: string; i: Inspection; fixture?: Fixture | null; assessment?: Assessment | null; photoUrl: (e: string) => string | undefined; camera: boolean }) {
  const change = [...i.changeAnalyses].reverse().find((c) => c.claimId === i.currentClaimId);
  const after: Photo | undefined = i.photos.find((p) => p.evidenceId === i.acceptedPostRepairPhotoId) ?? (change && i.photos.find((p) => p.evidenceId === change.photoEvidenceId));
  const before = beforePhoto(i);
  const [side, setSide] = useState<"before" | "after" | "compare" | null>(null);
  const [split, setSplit] = useState(50);
  const showing = side ?? (after ? "after" : "before");
  const photo = showing === "after" ? after : before;
  const beforeOutlines = usePhotoOutlines(id, i, before?.evidenceId);
  const afterOutlines = usePhotoOutlines(id, i, after?.evidenceId);
  const outlines = showing === "after" ? afterOutlines : beforeOutlines;
  const afterChange = i.changeAnalyses.find((c) => c.photoEvidenceId === after?.evidenceId);
  const photoChange = i.changeAnalyses.find((c) => c.photoEvidenceId === photo?.evidenceId);
  const beforeTags = arTags(fixture, assessment, beforeOutlines);
  const afterTags = arTags(fixture, undefined, afterOutlines, afterChange);
  const tags = showing === "after" ? afterTags : beforeTags;
  const src = photo && photoUrl(photo.evidenceId);
  const beforeSrc = before && photoUrl(before.evidenceId);
  const afterSrc = after && photoUrl(after.evidenceId);

  return (
    <div className="relative overflow-hidden rounded-[26px] bg-overlay text-overlay-foreground shadow-lg shadow-black/15">
      {showing === "compare" && beforeSrc && afterSrc ? (
        <div className="relative select-none overflow-hidden" data-ar-compare>
          <ArFrame src={beforeSrc} outlines={beforeOutlines} tags={beforeTags} interactive={false} />
          <div className="pointer-events-none absolute inset-0 overflow-hidden" style={{ clipPath: `inset(0 0 0 ${split}%)` }}>
            <ArFrame src={afterSrc} outlines={afterOutlines} tags={afterTags} interactive={false} />
          </div>
          <div className="pointer-events-none absolute inset-y-0 w-0.5 bg-primary shadow-[0_0_12px_var(--primary)]" style={{ left: `${split}%` }}>
            <span className=" absolute left-1/2 top-1/2 grid size-8 -translate-x-1/2 -translate-y-1/2 place-items-center rounded-full border-2 border-primary bg-overlay text-primary shadow-lg">
              <SlidersHorizontal className="size-4" />
            </span>
          </div>
          <span className="glass pointer-events-none absolute bottom-3 left-3 rounded-full px-2.5 py-0.5 text-[10px] font-medium uppercase tracking-wider">Before</span>
          <span className="glass pointer-events-none absolute bottom-3 right-16 rounded-full px-2.5 py-0.5 text-[10px] font-medium uppercase tracking-wider text-primary">After</span>
          <input type="range" min={5} max={95} value={split} onChange={(e) => setSplit(Number(e.target.value))}
            aria-label="Compare before and after photo" className="absolute inset-0 size-full cursor-ew-resize opacity-0" />
        </div>
      ) : src ? (
        <ArFrame src={src} outlines={outlines} tags={tags} />
      ) : (
        <Link href={`/m/i/${id}/scan`} className="grid aspect-[4/3] place-items-center text-sm text-overlay-foreground/70"><span className="flex flex-col items-center gap-2"><Camera className="size-9" />Tap to open the camera</span></Link>
      )}
      {before && (
        <div className="glass absolute left-3 top-3 flex rounded-full p-0.5 text-[11px] font-medium">
          {(["before", "after", ...(after ? ["compare" as const] : [])] as const).map((s) => (
            <button key={s} disabled={s === "after" && !after} onClick={() => setSide(s)}
              className={cn("rounded-full px-3 py-1 capitalize transition-colors disabled:opacity-40", showing === s ? "bg-primary text-primary-foreground" : "opacity-80")}>
              {s}
            </button>
          ))}
        </div>
      )}
      {camera && <Link href={`/m/i/${id}/scan`} aria-label="New photo" className="glass absolute right-3 top-3 grid size-11 place-items-center rounded-full shadow-lg"><Camera className="size-5" /></Link>}
      {afterChange && (
        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-overlay-foreground/15 bg-overlay/95 px-3 py-2 text-[11px]" data-change-telemetry>
          <EvidenceBadge state={changeLabel[afterChange.verdict].state} label={changeLabel[afterChange.verdict].label} />
          <div className="flex flex-wrap items-center gap-3 font-mono text-[10px] text-overlay-foreground/75">
            {afterChange.perceptualHashDistance != null && <span>pHash Δ: {afterChange.perceptualHashDistance}</span>}
            {afterChange.targetRegionIoU != null && <span>IoU: {Math.round(afterChange.targetRegionIoU * 100)}%</span>}
            {afterChange.componentShiftNormalized != null && <span>Shift: {(afterChange.componentShiftNormalized * 100).toFixed(1)}%</span>}
          </div>
        </div>
      )}
    </div>
  );
}

/** One of the two proofs the server requires, with its own pass state and, for the check, where the measurement came from. */
function ProofKey({ title, detail, pass, badge }: { title: string; detail: string; pass: boolean | null | "pending"; badge?: React.ReactNode }) {
  return (
    <div className={cn("rounded-xl border p-3", pass === "pending" || pass === null ? "border-border" : pass ? "border-success/40 bg-success/5" : "border-failure/40 bg-failure/10")}>
      <div className="flex items-center justify-between gap-2 text-sm font-medium">
        <span className={cn(pass === true && "text-success", pass === false && "text-failure")}>{title}</span>
        {badge}
      </div>
      <p className="mt-1 text-xs text-muted-foreground">{detail}</p>
    </div>
  );
}
