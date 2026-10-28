import { useState } from "react";
import { Link } from "wouter";
import { Camera, Terminal, RotateCcw, ShieldCheck, ShieldX, FileCheck2, Wrench, Loader2, UserCheck, Scale } from "lucide-react";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { CheckRow, EvidenceBadge, Section, SourceBadge, TechDetails } from "@/components/fo/primitives";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { api, type ChecklistStatus, type Decision } from "@/lib/api";
import { useAct } from "@/lib/hooks";
import { latestDiagnostic, useInspectionBundle, verifications } from "@/lib/inspection";
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

export default function Verify({ id }: { id: string }) {
  const { i, ev, fixture, photoUrl } = useInspectionBundle(id);
  const [running, setRunning] = useState(false);
  const [retry, setRetry] = useState("");
  const review = useAct(id, (req: string, status: ChecklistStatus, photo: string) => api.review(id, req, status, photo));
  const ack = useAct(id, () => api.acknowledge(id));
  const verify = useAct(id, () => api.verify(id));
  const claim = useAct(id, (d: string) => api.claim(id, d), { success: "New repair attempt recorded" });

  if (!i) return <><TopBar title="Repair verification" back="/m" /><Page><Loader2 className="mx-auto animate-spin" /></Page></>;

  const accepted = i.photos.find((p) => p.evidenceId === i.acceptedPostRepairPhotoId);
  const change = [...i.changeAnalyses].reverse().find((c) => c.claimId === i.currentClaimId);
  const acceptedChange = accepted && [...i.changeAnalyses].reverse().find((c) => c.photoEvidenceId === accepted.evidenceId);
  const required = fixture?.visualRequirements.filter((v) => v.mandatory) ?? [];
  const reviewed = (req: string) => i.checklist.find((c) => c.requirementId === req && (!c.reviewedByEvidenceId || c.reviewedByEvidenceId === accepted?.evidenceId));
  const allReviewed = required.every((r) => reviewed(r.requirementId));
  const decision = i.stage === "VerificationFailed" || i.stage === "VerifiedComplete" ? i.lastDecision : verify.data?.decision;
  const outcome = split(decision);
  const diag = latestDiagnostic(i);
  const attempts = verifications(ev);
  const ready = i.stage === "ReadyToVerify";

  const start = () => {
    setRunning(true);
    verify.mutate([], { onSettled: () => setTimeout(() => setRunning(false), 600) });
  };

  if (!i.currentClaimId)
    return (
      <>
        <TopBar title="Repair verification" back={`/m/i/${id}`} />
        <Page>
          <div className="panel p-4 text-sm">No repair has been claimed for this inspection yet.</div>
          <Button asChild className="h-11 w-full"><Link href={`/m/i/${id}`}>Back to investigation</Link></Button>
        </Page>
      </>
    );

  return (
    <>
      <TopBar title="Repair verification" back={`/m/i/${id}`} right={diag && <SourceBadge source={diag.source} />} />
      <Page>
        {!decision && !running && (
          <div className="panel space-y-1 p-4">
            <div className="text-base font-semibold">FieldProof runs two independent checks</div>
            <p className="text-sm text-muted-foreground">A new photo that you've reviewed, and a fresh equipment check. Both must pass; a photo alone can't mark this work complete.</p>
          </div>
        )}

        {(!decision || i.stage === "ReadyToVerify") && (
          <>
            <Section title="1 · New photo after the repair">
              {accepted && photoUrl(accepted.evidenceId) ? (
                <div className="panel flex items-center gap-3 p-2.5">
                  <img src={photoUrl(accepted.evidenceId)} alt="Post-repair photo" className="size-16 rounded-lg object-cover" />
                  <div className="min-w-0 flex-1 text-sm">
                    <div className="font-medium">Photo accepted</div>
                    <div className="text-xs text-muted-foreground">{fmtTime(accepted.observedAt)} · {acceptedChange ? changeLabel[acceptedChange.verdict].label : "not compared"}</div>
                  </div>
                  <Button asChild variant="outline" size="sm"><Link href={`/m/i/${id}/scan`}>Retake</Link></Button>
                </div>
              ) : (
                <div className={cn("panel space-y-3 p-3.5", change && "border-failure/40 bg-failure/10")}>
                  {change ? (
                    <div className="text-sm"><EvidenceBadge state="failed" label={changeLabel[change.verdict].label} /><p className="mt-1.5">{changeLabel[change.verdict].detail}</p></div>
                  ) : <p className="text-sm text-muted-foreground">Take a photo of the repaired connection.</p>}
                  <Button asChild className="h-11 w-full"><Link href={`/m/i/${id}/scan`}><Camera /> Take post-repair photo</Link></Button>
                </div>
              )}
            </Section>

            <Section title="2 · Your review">
              <div className={cn("panel divide-y divide-border", !accepted && "opacity-50")}>
                {required.map((r) => {
                  const cur = reviewed(r.requirementId)?.status;
                  return (
                    <div key={r.requirementId} className="space-y-2 p-3">
                      <div className="text-sm">{featureName(r.requirementId)}</div>
                      <div className="grid grid-cols-3 gap-1.5">
                        {(["Satisfied", "NotSatisfied", "Unknown"] as ChecklistStatus[]).map((st) => (
                          <button key={st} disabled={!accepted || review.isPending}
                            onClick={() => accepted && review.mutate([r.requirementId, st, accepted.evidenceId])}
                            className={cn("h-9 rounded-lg border text-xs font-medium transition-colors",
                              cur === st ? (st === "Satisfied" ? "border-success/50 bg-success/15 text-success" : st === "NotSatisfied" ? "border-failure/50 bg-failure/15 text-failure" : "border-warning/50 bg-warning/15 text-warning") : "border-border text-muted-foreground hover:border-primary/40")}>
                            {checklistLabel[st]}
                          </button>
                        ))}
                      </div>
                    </div>
                  );
                })}
              </div>
            </Section>

            <label className={cn("panel flex items-center justify-between gap-3 p-3.5 text-sm", !allReviewed && "opacity-50")}>
              <span className="flex items-center gap-2"><UserCheck className="size-4 text-muted-foreground" />3 · I reviewed this evidence myself</span>
              <Switch checked={i.technicianAcknowledged} disabled={!allReviewed || i.technicianAcknowledged || ack.isPending} onCheckedChange={() => ack.mutate([])} />
            </label>

            <Button size="lg" className="h-12 w-full" onClick={start} disabled={!ready || running || !accepted || !allReviewed || !i.technicianAcknowledged}>
              {running ? <Loader2 className="animate-spin" /> : <Wrench />} Run verification
            </Button>
            {ready && (!accepted || !allReviewed || !i.technicianAcknowledged) && (
              <p className="text-center text-xs text-muted-foreground">{!accepted ? "Take a post-repair photo first." : !allReviewed ? "Review each item against the new photo." : "Confirm you reviewed the evidence."}</p>
            )}
          </>
        )}

        {(running || decision) && (
          <div className="space-y-2.5">
            <CheckRow label="Visual verification"
              detail={running ? "Checking the new photo and your review…" : outcome.visual === false ? plainReason(decision!.reasons[0] ?? "") : "New photo accepted and every item reviewed."}
              pass={running ? "pending" : outcome.visual} />
            <CheckRow label="Equipment check"
              detail={running ? "Fresh measurement of the approved endpoint…" : outcome.diagnostic === null ? "Not run: the visual check has to pass first." : outcome.diagnostic ? `Endpoint reachable · ${fmtTime(diag?.result.completedAt)}` : `Endpoint still failing · ${fmtTime(diag?.result.completedAt)}`}
              pass={running ? "pending" : outcome.diagnostic} />
          </div>
        )}

        {!running && decision && decision.result === "Denied" && i.stage === "VerificationFailed" && (
          <div className="animate-rise space-y-4 rounded-2xl border border-failure/50 bg-failure/10 p-5">
            <ShieldX className="size-10 text-failure" />
            <div>
              <div className="text-xl font-semibold text-failure">Verification failed</div>
              <p className="mt-1 text-sm">{plainReason(decision.reasons[0] ?? "")}</p>
              <p className="mt-2 text-xs text-muted-foreground">This work can't be marked verified. Every attempt is kept in the record.</p>
            </div>
            <div className="space-y-2">
              <input value={retry} onChange={(e) => setRetry(e.target.value)} placeholder="What did you do this time?"
                className="h-11 w-full rounded-lg border border-input bg-background px-3 text-sm outline-none focus:border-primary" />
              <Button className="h-11 w-full" disabled={!retry.trim() || claim.isPending} onClick={() => claim.mutate([retry.trim()], { onSuccess: () => { setRetry(""); verify.reset(); } })}>
                <RotateCcw /> Fixed it again — verify
              </Button>
              <Button asChild variant="outline" className="h-11 w-full"><Link href={`/m/i/${id}/diagnostics`}><Terminal /> Review equipment check</Link></Button>
            </div>
          </div>
        )}

        {!running && decision && decision.result === "NeedsHumanReview" && (
          <div className="animate-rise space-y-2 rounded-2xl border border-warning/50 bg-warning/10 p-5">
            <Scale className="size-10 text-warning" />
            <div className="text-xl font-semibold text-warning">Needs a decision</div>
            <p className="text-sm">{plainReason(decision.reasons[0] ?? "")}</p>
          </div>
        )}

        {!running && decision?.result === "Verified" && (
          <div className="animate-rise space-y-4 rounded-2xl border border-success/40 bg-success/10 p-5">
            <ShieldCheck className="size-10 text-success" />
            <div>
              <div className="text-xl font-semibold text-success">Verified complete</div>
              <p className="mt-1 text-sm">All required checks passed.</p>
              {decision.outcomeKind === "VerifiedCompleteSimulated" && (
                <p className="mt-2 flex items-center gap-2 text-xs text-muted-foreground"><SourceBadge source="Simulated" /> The equipment check was simulated, not a live measurement.</p>
              )}
            </div>
            <div className="grid grid-cols-3 gap-2">
              {i.photos.slice(-3).map((p) => photoUrl(p.evidenceId) && (
                <img key={p.evidenceId} src={photoUrl(p.evidenceId)} alt="" className="h-16 w-full rounded-lg border border-border object-cover" />
              ))}
            </div>
            <Button asChild size="lg" className="h-12 w-full"><Link href={`/m/i/${id}/receipt`}><FileCheck2 /> View FieldProof receipt</Link></Button>
          </div>
        )}

        {attempts.length > 0 && (
          <div className="text-xs text-muted-foreground">
            Attempts: {attempts.map((a) => ((a["decision"] as Decision).result === "Verified" ? "✓" : "✕")).join("  ")} — every attempt is kept in the audit record.
          </div>
        )}
        {decision && (
          <TechDetails rows={[["Decision", decision.result], ["Reason", decision.reasons.join(" ")], ["Policy", decision.policyVersion], ["Outcome", decision.outcomeKind ?? "—"], ["Last attempt", attempts.length ? fmtWhen(attempts[attempts.length - 1]!.occurredAt) : "—"]]} />
        )}
      </Page>
    </>
  );
}
