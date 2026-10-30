import { useEffect, useState } from "react";
import { Link } from "wouter";
import { useQueryClient } from "@tanstack/react-query";
import { Camera, Terminal, RotateCcw, FileCheck2, Wrench, Loader2, UserCheck, Glasses, ShieldCheck, SlidersHorizontal, Sparkles, Eye, ClipboardCheck, Gauge, Lock } from "lucide-react";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { AiCard, Progress } from "@/components/fo/ai";
import { ArFrame } from "@/components/fo/ar-frame";
import { VerifyCamera } from "@/components/fo/verify-camera";
import { EvidenceBadge, Expandable, SourceBadge, TechDetails } from "@/components/fo/primitives";
import { Button } from "@/components/ui/button";
import { api, type ChecklistStatus, type Decision, type EventRecord, type Inspection, type Fixture, type Assessment, type Photo } from "@/lib/api";
import { aiSummary, verifySteps } from "@/lib/ai";
import { arTags, usePhotoOutlines } from "@/lib/ar";
import { qk, useAct } from "@/lib/hooks";
import { beforePhoto, useInspectionBundle, verifications } from "@/lib/inspection";
import { changeLabel, checklistLabel, featureName, fmtTime, fmtWhen, plainReason, predictionOutcome, probeValue } from "@/lib/labels";
import { COMPARE_GIVE_UP_MS, COMPARE_POLL_MS, currentAttempt, verifyCard, verifyCardStep, type Attempt } from "@/lib/verify";
import { cn } from "@/lib/utils";

/**
 * Asks the server about a photo still being compared every couple of seconds while the screen is visible, and stops
 * when the comparison is recorded, the screen is left or hidden, or it has taken too long (then a retake is offered).
 * It reads real server state; nothing here simulates progress.
 */
function useComparePolling(id: string, comparing: boolean) {
  const qc = useQueryClient();
  const [tooLong, setTooLong] = useState(false);
  useEffect(() => {
    setTooLong(false);
    if (!comparing) return;
    const start = Date.now();
    const t = setInterval(() => {
      if (Date.now() - start > COMPARE_GIVE_UP_MS) { setTooLong(true); clearInterval(t); return; }
      if (document.visibilityState === "visible") qc.invalidateQueries({ queryKey: qk.inspection(id) });
    }, COMPARE_POLL_MS);
    return () => {
      clearInterval(t);
      qc.invalidateQueries({ queryKey: qk.events(id) });
      qc.invalidateQueries({ queryKey: qk.artifacts(id) });
    };
  }, [comparing, id, qc]);
  return tooLong;
}

/**
 * Repair verification, camera first. The server's readiness picks the card: take the photo here, wait while it is
 * compared, review it, confirm, then verify, which measures the equipment again. After a decision the screen shows what
 * the photo showed, what you confirmed and what was measured, separately, then the decision and its reasons.
 */
export default function Verify({ id }: { id: string }) {
  const { i, ev, fixture, assessment, photoUrl } = useInspectionBundle(id);
  const [cameraOpen, setCameraOpen] = useState(false);
  const [retry, setRetry] = useState("");
  const review = useAct(id, (req: string, status: ChecklistStatus, photo: string) => api.review(id, req, status, photo));
  const ack = useAct(id, () => api.acknowledge(id));
  const verify = useAct(id, () => api.verify(id));
  const claim = useAct(id, (d: string) => api.claim(id, d), { success: "New repair attempt recorded" });
  const card = i ? verifyCard(i) : "photo";
  const tooLong = useComparePolling(id, !!i?.currentClaimId && card === "comparing");

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
  const change = accepted && [...i.changeAnalyses].reverse().find((c) => c.claimId === i.currentClaimId && c.photoEvidenceId === accepted.evidenceId);
  const required = fixture?.visualRequirements.filter((v) => v.mandatory) ?? [];
  // The server's own answer for each item under this claim; the policy counts it whichever post-repair photo it cited.
  const reviewed = (req: string) => i.checklist.find((c) => c.requirementId === req);
  const attempt = verify.isPending ? null : currentAttempt(ev);
  const attempts = verifications(ev);
  const asked = i.evidenceRequests?.find((r) => !r.answeredByPhotoId);
  const busy = review.isPending || ack.isPending || claim.isPending;
  const btn = "h-12 w-full rounded-2xl";
  const before = beforePhoto(i);
  const blocking = i.readiness?.blocking ?? [];
  const check = i.readiness?.checks[0];
  const target = fixture?.diagnostics[0]?.targetAlias;
  // The camera opens by itself when a photo is the next step and no decision is waiting to be read.
  const showCamera = cameraOpen || (card === "photo" && !attempt);
  const openCamera = () => setCameraOpen(true);

  const reviewRows = (
    <div className="divide-y divide-border">
      {required.map((r) => {
        const entry = reviewed(r.requirementId);
        const cur = entry?.status;
        const earlier = !!entry?.reviewedByEvidenceId && entry.reviewedByEvidenceId !== accepted?.evidenceId;
        return (
          <div key={r.requirementId} className="space-y-2 py-2.5 first:pt-0 last:pb-0">
            <div className="flex items-baseline justify-between gap-2 text-sm">{featureName(r.requirementId)}{earlier && <span className="text-[11px] text-muted-foreground">answered on an earlier photo</span>}</div>
            <div className="grid grid-cols-3 gap-1.5">
              {(["Satisfied", "NotSatisfied", "Unknown"] as ChecklistStatus[]).map((st) => (
                <button key={st} disabled={!accepted || busy || card !== "review" && card !== "confirm"}
                  onClick={() => accepted && review.mutate([r.requirementId, st, accepted.evidenceId])}
                  className={cn("h-11 rounded-xl border text-xs font-medium transition-colors disabled:cursor-default",
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

  const claimAgain = (
    <>
      <input value={retry} onChange={(e) => setRetry(e.target.value)} placeholder="What did you do this time?" aria-label="What you fixed this time"
        className="h-12 w-full rounded-2xl border border-input bg-background px-4 text-sm outline-none focus:border-primary" />
      <Button size="lg" className={btn} disabled={!retry.trim() || claim.isPending} onClick={() => claim.mutate([retry.trim()], { onSuccess: () => { setRetry(""); verify.reset(); setCameraOpen(false); } })}>
        <RotateCcw /> Fixed it again
      </Button>
      <Button asChild variant="ghost" className="h-10 w-full"><Link href={`/m/i/${id}/diagnostics`}><Terminal /> Review the equipment check</Link></Button>
    </>
  );

  const gateCard = (() => {
    if (verify.isPending)
      return <AiCard says="Your photo, review and confirmation are in." title="Verifying…" hint={`Measuring ${target ?? "the equipment"} now. The photo and the measurement both have to pass.`} />;
    switch (card) {
      case "comparing":
        return (
          <AiCard says="I'm comparing the new photo with the earlier view." title="Comparing the new photo…"
            hint={tooLong ? "This is taking longer than it should. The comparison may have been cut off; take the photo again." : "Verification waits for this. This screen updates when the server finishes."}>
            {tooLong && <Button size="lg" variant="outline" className={btn} onClick={openCamera}><Camera /> Take it again</Button>}
          </AiCard>
        );
      case "review":
        return (
          <AiCard says={aiSummary(i, undefined)} title="Review the new photo" hint="Mark what you can see yourself. My outline is a suggestion.">
            {required.some((r) => reviewed(r.requirementId)?.status === "NotSatisfied") ? (
              <p className="rounded-xl border border-failure/40 bg-failure/10 p-2.5 text-xs">“Not right” means the repair isn't finished. Fix it, then retake the photo.</p>
            ) : required.some((r) => { const st = reviewed(r.requirementId)?.status; return st === "Unknown" || st === "Conflicting"; }) && (
              <p className="rounded-xl border border-warning/40 bg-warning/10 p-2.5 text-xs">“Can't tell” holds verification. Retake a clearer photo, or change the answer if you can see it.</p>
            )}
            {reviewRows}
            <Button variant="ghost" className="h-10 w-full" onClick={openCamera}><Camera /> Retake the photo</Button>
          </AiCard>
        );
      case "confirm":
        return (
          <AiCard says="Your review is in." title="Confirm it's your review" hint="The receipt records that you checked this evidence yourself. A new photo or review needs confirming again.">
            <Button size="lg" variant="outline" className={btn} disabled={busy} onClick={() => ack.mutate([])}>{ack.isPending ? <Loader2 className="animate-spin" /> : <UserCheck />} I reviewed this evidence myself</Button>
          </AiCard>
        );
      case "run":
        return (
          <AiCard says="Photo accepted, reviewed and confirmed." title="Ready to verify"
            hint={`Verify takes a fresh measurement of ${target ?? "the equipment"} now${check?.afterClaim && check.lastRunAt ? `; the one from ${fmtTime(check.lastRunAt)} isn't reused` : ""}. Both proofs have to pass.`}>
            <Button size="lg" className={btn} onClick={() => verify.mutate([])}><Wrench /> Verify the repair</Button>
          </AiCard>
        );
      case "claim":
        return <AiCard tone="failure" says="This repair attempt didn't pass." title="Fix it, then claim again" hint="Every attempt stays in the record.">{claimAgain}</AiCard>;
      case "closed":
        return <AiCard says={plainReason(blocking[0] ?? "")} title="Inspection closed" />;
      case "photo":
        return (
          <AiCard says={aiSummary(i, undefined)} title={asked ? asked.note : "Photograph the repair"}
            hint={asked ? `Requested by the ${asked.requestedBy.toLowerCase()}. Your next photo answers it.` : "Same view as before. The same photo again won't count."}>
            <Button size="lg" className={btn} onClick={openCamera}><Camera /> Take a new photo</Button>
          </AiCard>
        );
    }
  })();

  return (
    <>
      <TopBar title="Repair verification" back={`/m/i/${id}`} right={check?.source && <SourceBadge source={check.source} />} />
      <Page className="space-y-4">
        {showCamera ? (
          <VerifyCamera key={i.currentClaimId} id={id} guideSrc={before && photoUrl(before.evidenceId)}
            prompt={asked ? `Requested: ${asked.note}` : "Frame the repaired connection the same way as before"}
            onAccepted={() => setCameraOpen(false)} onClose={card === "photo" && !attempt ? undefined : () => setCameraOpen(false)} />
        ) : (
          <VerifyHero id={id} i={i} fixture={fixture} assessment={assessment} photoUrl={photoUrl} onCamera={card !== "closed" ? openCamera : undefined} />
        )}
        {showCamera && card === "photo" && (
          <Link href={`/m/i/${id}/glasses`} className="flex min-h-11 items-center justify-center gap-2 text-xs text-muted-foreground"><Glasses className="size-4" /> Use the glasses instead</Link>
        )}

        {attempt ? (
          <AttemptResult id={id} i={i} a={attempt} fixture={fixture} card={card} onRetake={openCamera} claimAgain={claimAgain} />
        ) : (
          !showCamera && gateCard
        )}

        {!attempt && !verify.isPending && blocking.length > 1 && card !== "closed" && (
          <Expandable title={`Why verify can't run yet · ${blocking.length}`}>
            <ul className="space-y-1.5 text-xs text-muted-foreground">{blocking.map((b) => <li key={b}>· {plainReason(b)}</li>)}</ul>
          </Expandable>
        )}
        {!verify.isPending && <PredictionCard id={id} i={i} ev={ev} canAsk={!attempt && card === "run"} />}
        <Progress step={attempt?.decision.result === "Verified" ? 4 : verifyCardStep[card]} steps={verifySteps} />

        <div className="space-y-2">
          {!attempt && accepted && (card === "confirm" || card === "run") && <Expandable title={`Your review · ${required.length} items`}>{reviewRows}</Expandable>}
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
          {attempt && (
            <TechDetails rows={[["Decision", attempt.decision.result], ["Reason", attempt.decision.reasons.join(" ")], ["Policy", attempt.decision.policyVersion], ["Outcome", attempt.decision.outcomeKind ?? "—"], ["Decided", fmtWhen(attempt.at)]]} />
          )}
        </div>
      </Page>
    </>
  );
}

/**
 * One decision, kept in three separate parts: what the photo showed (the server's change check), what the technician
 * confirmed, and what the equipment check measured for this attempt, then the deterministic decision with every reason.
 */
function AttemptResult({ id, i, a, fixture, card, onRetake, claimAgain }: {
  id: string; i: Inspection; a: Attempt; fixture?: Fixture | null; card: string; onRetake: () => void; claimAgain: React.ReactNode;
}) {
  const d = a.decision;
  const simulated = d.outcomeKind === "VerifiedCompleteSimulated" || a.measured.some((m) => m.source !== "LiveLocal");
  const photoId = a.review[0]?.photoEvidenceId ?? i.acceptedPostRepairPhotoId;
  const change = photoId ? [...i.changeAnalyses].reverse().find((c) => c.photoEvidenceId === photoId) : undefined;
  const tone = d.result === "Verified" ? "success" : d.result === "NeedsHumanReview" ? "warning" : "failure";
  const title = d.result === "Verified" ? (d.outcomeKind === "VerifiedCompleteSimulated" ? "Verified complete · simulated check" : "Verified complete")
    : d.result === "NeedsHumanReview" ? "Held: needs new evidence" : "Not verified";
  const target = (diagnosticId: string) => fixture?.diagnostics.find((x) => x.diagnosticId === diagnosticId)?.targetAlias;

  return (
    <div className="space-y-2.5" data-attempt-result>
      <AiCard tone={tone} says={d.result === "Verified" ? aiSummary(i, undefined) : plainReason(d.reasons[0] ?? "")} title={title}
        hint={d.result === "Verified" ? "Every required check passed." : card === "photo" ? "Take a new photo to try again. This attempt stays in the record." : "This attempt stays in the record."}>
        {simulated && a.measured.length > 0 && (
          <p className="flex items-center gap-2 text-xs text-muted-foreground"><SourceBadge source={a.measured[0]!.source} /> The equipment check was simulated, not a live measurement.</p>
        )}
        {d.result === "Verified" && <Button asChild size="lg" className="h-12 w-full rounded-2xl"><Link href={`/m/i/${id}/receipt`}><FileCheck2 /> View FieldProof receipt</Link></Button>}
        {card === "photo" && <Button size="lg" className="h-12 w-full rounded-2xl" onClick={onRetake}><Camera /> Take a new photo</Button>}
        {card === "claim" && claimAgain}
      </AiCard>

      <div className="grid gap-2 sm:grid-cols-3">
        <Part icon={<Eye className="size-4" />} title="What the photo showed" kind="Change check · inferred"
          pass={!change ? null : change.verdict === "Changed" ? true : change.verdict === "Unscorable" ? null : false}>
          {change ? (
            <>
              <EvidenceBadge state={changeLabel[change.verdict].state} label={changeLabel[change.verdict].label} />
              <p>{change.verdict === "Unscorable" ? "No visual change could be established; the visual part rests on your review." : change.reason}</p>
            </>
          ) : <p>No accepted photo for this claim.</p>}
        </Part>
        <Part icon={<ClipboardCheck className="size-4" />} title="What you confirmed" kind="Your review"
          pass={a.review.length === 0 || !a.acknowledgedAt ? false : a.review.every((r) => r.status === "Satisfied") ? true : null}>
          {a.review.length ? (
            <ul className="space-y-0.5">{a.review.map((r) => <li key={r.requirementId}>{featureName(r.requirementId)}: <span className="font-medium">{checklistLabel[r.status]}</span></li>)}</ul>
          ) : <p>Nothing reviewed for this claim.</p>}
          <p>{a.acknowledgedAt ? `Confirmed by you at ${fmtTime(a.acknowledgedAt)}` : "Not confirmed by you."}</p>
        </Part>
        <Part icon={<Gauge className="size-4" />} title="What was measured" kind="Equipment check · measured"
          pass={a.measured.length === 0 ? null : a.measured.every((m) => m.passed === true)}>
          {a.measured.length ? a.measured.map((m) => (
            <div key={m.traceId} className="space-y-0.5">
              <div className="flex flex-wrap items-center gap-1.5"><span className="font-medium">{target(m.diagnosticId) ?? m.targetAlias}</span><SourceBadge source={m.source} /></div>
              <p>{m.passed === null ? "Unavailable: no measurement" : `${probeValue(m.normalizedValue)} · ${m.passed ? "passed" : "failed"}`} · {fmtTime(m.completedAt)}</p>
            </div>
          )) : <p>No check ran: the photo, review or confirmation decided first.</p>}
        </Part>
      </div>

      <div className="panel space-y-1.5 p-3 text-xs" data-decision>
        <div className="flex items-center justify-between gap-2">
          <span className="eyebrow">Decision · {d.policyVersion}</span>
          <span className={cn("inline-flex items-center gap-1 font-medium", tone === "success" ? "text-success" : tone === "warning" ? "text-warning" : "text-failure")}>
            {d.result === "Verified" ? <ShieldCheck className="size-3.5" /> : <Lock className="size-3.5" />}{d.result === "NeedsHumanReview" ? "Held" : d.result}
          </span>
        </div>
        <ul className="space-y-0.5 text-muted-foreground">{d.reasons.map((r) => <li key={r}>· {r}</li>)}</ul>
      </div>
    </div>
  );
}

/** One of the three kinds of proof, labelled with where it came from. */
function Part({ icon, title, kind, pass, children }: { icon: React.ReactNode; title: string; kind: string; pass: boolean | null; children: React.ReactNode }) {
  return (
    <div className={cn("space-y-1.5 rounded-xl border p-3 text-xs", pass === null ? "border-border" : pass ? "border-success/40 bg-success/5" : "border-failure/40 bg-failure/10")}>
      <div className="flex items-center gap-1.5 text-sm font-medium">{icon}{title}</div>
      <div className="text-[10px] uppercase tracking-wider text-muted-foreground">{kind}</div>
      <div className="space-y-1 text-muted-foreground">{children}</div>
    </div>
  );
}

/** Before and after on the same frame. "After" is the repair photo with the server's change outline pinned to it. */
function VerifyHero({ id, i, fixture, assessment, photoUrl, onCamera }: { id: string; i: Inspection; fixture?: Fixture | null; assessment?: Assessment | null; photoUrl: (e: string) => string | undefined; onCamera?: () => void }) {
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
        <button type="button" onClick={onCamera} disabled={!onCamera} className="grid aspect-[4/3] w-full place-items-center text-sm text-overlay-foreground/70"><span className="flex flex-col items-center gap-2"><Camera className="size-9" />Tap to open the camera</span></button>
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
      {onCamera && <button type="button" onClick={onCamera} aria-label="New photo" className="glass absolute right-3 top-3 grid size-11 place-items-center rounded-full shadow-lg"><Camera className="size-5" /></button>}
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

/**
 * VK-T27: the model says what the equipment check will report before it runs (inferred), then the check measures it.
 * Core scores the prediction; it never decides verification. Everything here is read from the event log.
 */
function PredictionCard({ id, i, ev, canAsk }: { id: string; i: Inspection; ev: EventRecord[]; canAsk: boolean }) {
  const since = (e: EventRecord) => !!i.repairClaimedAt && new Date(e.occurredAt) >= new Date(i.repairClaimedAt);
  const claim = [...ev].reverse().find((e) => e.kind === "RepairClaimed");
  const registered = [...ev].reverse().find((e) => e.kind === "PredictionRegistered" && since(e));
  const scored = registered && ev.find((e) => e.kind === "PredictionScored" && e["predictionId"] === registered["predictionId"]);
  const refused = [...ev].reverse().find((e) => (e.kind === "KnowledgeProposalRejected" || e.kind === "KnowledgeProposalAccepted") && since(e));
  const ask = useAct(id, () => api.reason(id, `Technician's repair claim: ${String(claim?.["description"] ?? "")}. Predict what the approved equipment check will report.`), {
    success: (r) => r.accepted ? (r.prediction ? "Prediction recorded before the check" : "LensQi didn't propose a check to predict") : "The server refused LensQi's suggestion and recorded why",
  });
  if (!registered && !canAsk) return null;

  const outcome = scored ? String(scored["outcome"]) : "Pending";
  const label = predictionOutcome[outcome] ?? predictionOutcome.Pending!;
  return (
    <div className="panel space-y-2 p-3" data-prediction-card>
      <div className="flex items-center justify-between gap-2">
        <span className="eyebrow flex items-center gap-1.5"><Sparkles className="size-3.5 text-primary" /> Prediction before the check</span>
        {registered && <EvidenceBadge state={label.state} label={label.label} />}
      </div>
      {registered ? (
        <div className="grid grid-cols-2 gap-2 text-sm">
          <div className="rounded-xl border border-primary/30 bg-primary/5 p-2.5">
            <div className="text-[11px] text-muted-foreground">LensQi predicted · inferred</div>
            <div className="font-medium">{probeValue(String(registered["predictedValue"]))}</div>
          </div>
          <div className={cn("rounded-xl border p-2.5", !scored ? "border-dashed border-border" : outcome === "Confirmed" ? "border-success/40 bg-success/5" : outcome === "Falsified" ? "border-failure/40 bg-failure/10" : "border-border")}>
            <div className="text-[11px] text-muted-foreground">Check measured · measured</div>
            <div className="font-medium">{scored ? probeValue(scored["measuredValue"] as string | null) : "not run yet"}</div>
          </div>
        </div>
      ) : (
        <>
          <p className="text-xs text-muted-foreground">
            {refused?.kind === "KnowledgeProposalRejected"
              ? `The server refused LensQi's last suggestion (${(refused["rejections"] as { code: string }[]).map((x) => x.code).join(", ")}). It ran nothing and the refusal is in the record.`
              : "Ask LensQi to say what the equipment check will report, before it runs. It's recorded first, then scored against the measurement."}
          </p>
          <Button variant="outline" className="h-10 w-full rounded-xl" disabled={ask.isPending} onClick={() => ask.mutate([])}>
            {ask.isPending ? <Loader2 className="animate-spin" /> : <Sparkles />} {ask.isPending ? "Asking…" : "Ask LensQi to predict the check"}
          </Button>
        </>
      )}
      <p className="text-[11px] text-muted-foreground">A prediction is model calibration. The measurement decides verification, never the prediction.</p>
    </div>
  );
}
