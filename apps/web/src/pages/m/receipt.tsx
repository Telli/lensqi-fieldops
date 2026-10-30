import { useQuery } from "@tanstack/react-query";
import { Link } from "wouter";
import { toast } from "sonner";
import { Download, Share2, Fingerprint, Printer, ShieldCheck, ShieldX, CircleDashed, Loader2 } from "lucide-react";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { Button } from "@/components/ui/button";
import { Logo, Citation, SourceBadge, TechDetails } from "@/components/fo/primitives";
import { EvidencePhoto } from "@/components/fo/evidence-photo";
import { api, receiptHtmlUrl, receiptJsonUrl, type Decision, type DiagnosticResult, type EventRecord } from "@/lib/api";
import { beforePhoto, useGeometry, useInspectionBundle } from "@/lib/inspection";
import { changeLabel, featureName, fmtWhen, isPostRepairItem, plainReason, predictionOutcome, probeValue, type EvidenceState } from "@/lib/labels";
import type { ChangeVerdict } from "@/lib/api";
import { cn } from "@/lib/utils";

interface Receipt {
  inspection: { inspectionId: string; assetId: string; fixtureId: string; fixtureRevision: number; stage: string };
  versions: { policyVersion: string; receiptVersion: string };
  diagnostics: { evidenceId: string; result: DiagnosticResult }[];
  decision?: Decision | null;
  knowledge?: { packId: string; citations: { factId: string; documentTitle: string; sourceLocator: string; sourceUrl?: string | null }[] } | null;
  predictions?: { tally: { confirmed: number; falsified: number; unscorable: number; pending: number }; items: { predictionId: string; diagnosticId: string; predictedValue: string; measuredValue?: string | null; outcome: string }[] };
  visualChange?: { claims: { claimId: string; description: string; verdict: string }[] };
  proposals?: { accepted: number; rejected: { proposalId: string; nextAction?: string | null; codes: string[] }[] };
  events: EventRecord[];
  artifacts: { evidenceId: string; sha256: string }[];
  manifestSha256: string;
  generatedAt: string;
}

export function FieldProofReceipt({ id, wide }: { id: string; wide?: boolean }) {
  const { i, fixture, photoUrl } = useInspectionBundle(id);
  const receipt = useQuery({ queryKey: ["receipt", id, i?.lastSequence], queryFn: () => api.receipt(id) as Promise<unknown> as Promise<Receipt>, enabled: !!i });
  const check = useQuery({ queryKey: ["receipt-verify", id, i?.lastSequence], queryFn: () => api.verifyReceipt(id), enabled: false });
  // "Before" is the last photo taken before the first repair claim, not just before the latest one.
  const firstClaimAt = receipt.data?.events.find((e) => e.kind === "RepairClaimed")?.occurredAt;
  const before = firstClaimAt ? [...(i?.photos ?? [])].reverse().find((p) => new Date(p.observedAt) <= new Date(firstClaimAt)) ?? beforePhoto(i) : beforePhoto(i);
  const after = i?.photos.find((p) => p.evidenceId === i.acceptedPostRepairPhotoId);
  const beforeGeo = useGeometry(id, i, before?.evidenceId);
  const afterGeo = useGeometry(id, i, after?.evidenceId);
  const r = receipt.data;
  if (!i || !r) return <div className="panel grid h-40 place-items-center"><Loader2 className="animate-spin text-muted-foreground" /></div>;

  const ok = r.decision?.result === "Verified";
  const attempts = r.events.filter((e) => e.kind === "VerificationEvaluated");
  const claims = r.events.filter((e) => e.kind === "RepairClaimed").map((e) => String(e["description"]));
  const firstFail = r.diagnostics.find((d) => d.result.passed === false);
  const firstAssessment = r.events.find((e) => e.kind === "AssessmentRecorded")?.["assessment"] as { hypotheses: { featureId: string; status: string }[] } | undefined;
  const unclear = firstAssessment?.hypotheses.filter((h) => h.status !== "Observed" && !isPostRepairItem(h.featureId)).map((h) => featureName(h.featureId).toLowerCase());
  const asset = fixture?.assets.find((a) => a.assetId === r.inspection.assetId);
  const simulated = r.diagnostics.some((d) => d.result.source !== "LiveLocal");

  const share = async () => {
    const text = `FieldProof ${r.inspection.inspectionId} · ${asset?.displayName ?? r.inspection.assetId} · ${ok ? "Verified complete" : "Not verified"} · manifest ${r.manifestSha256}`;
    try {
      if (navigator.share) await navigator.share({ title: "FieldProof receipt", text, url: location.origin + receiptHtmlUrl(id) });
      else { await navigator.clipboard.writeText(text); toast.success("Receipt summary copied"); }
    } catch { /* the share sheet was dismissed */ }
  };

  return (
    <article className="panel overflow-hidden">
      <header className={cn("relative overflow-hidden border-b border-border px-5 pb-5 pt-4", ok ? "bg-success/[0.07]" : attempts.length ? "bg-failure/[0.07]" : "bg-surface")}>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2"><Logo size={20} /><span className="text-xs font-semibold uppercase tracking-[0.14em] text-muted-foreground">FieldProof receipt</span></div>
          <span className="text-[11px] text-muted-foreground">{fmtWhen(r.generatedAt)}</span>
        </div>
        <div className="mt-4 flex items-center gap-3">
          <span className={cn("grid size-12 shrink-0 place-items-center rounded-2xl", ok ? "bg-success/15 text-success" : attempts.length ? "bg-failure/15 text-failure" : "bg-muted text-muted-foreground")}>
            {ok ? <ShieldCheck className="size-6" /> : attempts.length ? <ShieldX className="size-6" /> : <CircleDashed className="size-6" />}
          </span>
          <div className="min-w-0">
            <div className={cn("text-xl font-semibold tracking-tight", ok ? "text-success" : attempts.length ? "text-failure" : "")}>{ok ? "Verified complete" : attempts.length ? "Not verified" : "Open"}</div>
            <div className="truncate text-sm text-muted-foreground">{asset?.displayName ?? r.inspection.assetId} · {r.inspection.fixtureId} rev {r.inspection.fixtureRevision}</div>
          </div>
        </div>
        {ok && <div className="mt-3 flex items-center gap-2 text-xs text-muted-foreground">{r.decision?.outcomeKind === "VerifiedCompleteLive" ? <SourceBadge source="LiveLocal" /> : <SourceBadge source="Simulated" />}{r.decision?.outcomeKind === "VerifiedCompleteLive" ? "Live equipment check" : "Equipment check simulated, not a live measurement"}</div>}
      </header>
      <div className={cn("space-y-5 p-4 text-sm", wide && "grid gap-6 space-y-0 md:grid-cols-2")}>
        <div className="space-y-5">
          <div className="grid grid-cols-2 gap-2">
            {[["Before", before, beforeGeo.data, "warning"], ["After", after, afterGeo.data, "success"]].map(([label, p, g, tone]) => {
              const photo = p as typeof before;
              const src = photo && photoUrl(photo.evidenceId);
              return (
                <div key={label as string} className="overflow-hidden rounded-xl border border-border bg-overlay">
                  {src ? <EvidencePhoto src={src} geometry={g as never} tone={tone as "warning" | "success"} imgClassName="aspect-[4/3] object-contain">
                    <figcaption className="glass absolute bottom-1.5 left-1.5 rounded-full px-2 py-0.5 text-[10px] font-medium">{label as string}{(g as { matched?: boolean } | null)?.matched ? " · outlined" : ""}</figcaption>
                  </EvidencePhoto> : <div className="grid aspect-[4/3] place-items-center text-[11px] text-overlay-foreground/60">No {String(label).toLowerCase()} photo</div>}
                </div>
              );
            })}
          </div>
          <div><div className="eyebrow">Initial discrepancy</div><p className="mt-1">
            {firstFail ? "The approved equipment check failed" : "Inspection opened"}{unclear?.length ? `; the first photo left ${unclear.join(" and ")} unclear` : ""}.
          </p></div>
          {claims.length > 0 && (
            <div><div className="eyebrow">Technician action</div>
              <ol className="mt-1 list-decimal space-y-0.5 pl-4">{claims.map((c, k) => <li key={k}>{c}</li>)}</ol>
            </div>
          )}
        </div>
        <div className="space-y-5">
          <div>
            <div className="eyebrow">Verification decisions</div>
            <ul className="mt-1.5 space-y-1 text-xs">
              {attempts.length === 0 && <li className="text-muted-foreground">No verification attempts yet.</li>}
              {attempts.map((a, k) => {
                const d = a["decision"] as Decision;
                return <li key={a.sequence} className={d.result === "Verified" ? "text-success" : "text-failure"}>#{k + 1} {fmtWhen(a.occurredAt)} · {d.result === "Verified" ? "Accepted" : "Rejected"}{d.result !== "Verified" ? ` — ${plainReason(d.reasons[0] ?? "")}` : ""}</li>;
              })}
            </ul>
          </div>
          <div>
            <div className="eyebrow">Equipment checks</div>
            <ul className="mt-1.5 space-y-1.5 text-xs">
              {r.diagnostics.map((d) => (
                <li key={d.evidenceId} className="flex items-center justify-between gap-2">
                  <span>{fmtWhen(d.result.completedAt)} · {d.result.passed === true ? "Pass" : d.result.passed === false ? "Fail" : "Unavailable"}</span>
                  <SourceBadge source={d.result.source} />
                </li>
              ))}
              {r.diagnostics.length === 0 && <li className="text-muted-foreground">None recorded.</li>}
            </ul>
            {simulated && <p className="mt-1.5 text-[11px] text-muted-foreground">Simulated checks stand in for the lab endpoint and are never a live measurement.</p>}
          </div>
          {r.visualChange?.claims.length ? (
            <div>
              <div className="eyebrow">Change evidence per repair</div>
              <ul className="mt-1.5 space-y-1.5 text-xs">
                {r.visualChange.claims.map((c, k) => (
                  <li key={c.claimId} className="flex items-center justify-between gap-2">
                    <span className="min-w-0 truncate">#{k + 1} {c.description}</span>
                    <Tag state={changeLabel[c.verdict as ChangeVerdict]?.state ?? "unknown"}>{changeLabel[c.verdict as ChangeVerdict]?.label ?? "Not analysed"}</Tag>
                  </li>
                ))}
              </ul>
              <p className="mt-1.5 text-[11px] text-muted-foreground">Shows that something changed in the photo, never that the repair worked.</p>
            </div>
          ) : null}
          {r.predictions?.items.length ? (
            <div>
              <div className="eyebrow">Predictions before each check</div>
              <ul className="mt-1.5 space-y-1.5 text-xs">
                {r.predictions.items.map((p) => (
                  <li key={p.predictionId} className="flex items-center justify-between gap-2">
                    <span>Predicted {probeValue(p.predictedValue)} · measured {probeValue(p.measuredValue)}</span>
                    <Tag state={predictionOutcome[p.outcome]?.state ?? "unknown"}>{p.outcome}</Tag>
                  </li>
                ))}
              </ul>
              <p className="mt-1.5 text-[11px] text-muted-foreground">
                {r.predictions.tally.confirmed} confirmed · {r.predictions.tally.falsified} falsified{r.predictions.tally.pending ? ` · ${r.predictions.tally.pending} waiting` : ""}. Model calibration, scored by code; never a verification input.
              </p>
            </div>
          ) : null}
          {r.proposals && r.proposals.accepted + r.proposals.rejected.length > 0 ? (
            <div>
              <div className="eyebrow">AI suggestions checked by the server</div>
              <p className="mt-1 text-xs">{r.proposals.accepted} accepted · {r.proposals.rejected.length} refused</p>
              {r.proposals.rejected.length > 0 && (
                <ul className="mt-1 space-y-0.5 text-[11px] text-muted-foreground">
                  {r.proposals.rejected.map((x) => <li key={x.proposalId}>{x.nextAction ?? "Unreadable output"}: {x.codes.join(", ")}. Nothing was run.</li>)}
                </ul>
              )}
            </div>
          ) : null}
          {r.knowledge?.citations?.length ? (
            <div className="space-y-1.5"><div className="eyebrow">Documentation references</div>
              {r.knowledge.citations.slice(0, 3).map((c) => <Citation key={c.factId} title={c.documentTitle} section={c.sourceLocator} href={c.sourceUrl} />)}
            </div>
          ) : null}
          <div className="rounded-xl border border-border bg-surface p-3.5">
            <div className="flex items-center justify-between gap-2">
              <span className="flex items-center gap-1.5 text-xs font-medium"><Fingerprint className="size-3.5 text-primary" /> Evidence integrity</span>
              <button onClick={() => check.refetch()} className="text-[11px] text-primary">{check.isFetching ? "Checking…" : "Check now"}</button>
            </div>
            <div className="mt-1 break-all font-mono text-[11px] text-muted-foreground">{r.manifestSha256}</div>
            <div className="mt-1 text-[11px] text-muted-foreground">{r.artifacts.length} photos · {r.diagnostics.length} checks · {r.events.length} events</div>
            {check.data && (
              <div className={cn("mt-2 flex items-center gap-1.5 text-xs", check.data.isValid ? "text-success" : "text-failure")}>
                <ShieldCheck className="size-3.5" />{check.data.isValid ? `Intact · ${check.data.checks.length} checks passed` : `Tampered: ${check.data.failures.join("; ")}`}
              </div>
            )}
          </div>
          <div className="grid grid-cols-2 gap-2">
            <Button size="lg" className="col-span-2 h-12 rounded-2xl" onClick={share}><Share2 /> Share receipt</Button>
            <Button asChild variant="outline" className="h-11 rounded-xl"><a href={receiptJsonUrl(id)} download={`fieldproof-${id}.json`}><Download /> Export</a></Button>
            <Button asChild variant="outline" className="h-11 rounded-xl"><a href={receiptHtmlUrl(id)} target="_blank" rel="noopener"><Printer /> Print</a></Button>
          </div>
          <TechDetails rows={[["Inspection", r.inspection.inspectionId], ["Policy", r.versions.policyVersion], ["Receipt format", r.versions.receiptVersion], ...r.artifacts.map((a): [string, string] => [a.evidenceId, a.sha256])]} />
        </div>
      </div>
    </article>
  );
}

const tagTone: Record<EvidenceState, string> = {
  verified: "border-success/40 text-success", failed: "border-failure/40 text-failure", hypothesis: "border-primary/40 text-primary",
  "evidence-required": "border-warning/40 text-warning", "human-confirmed": "border-border text-foreground", unknown: "border-border text-muted-foreground",
};

function Tag({ state, children }: { state: EvidenceState; children: React.ReactNode }) {
  return <span className={cn("shrink-0 rounded-full border px-2 py-0.5 text-[10px] font-medium", tagTone[state])}>{children}</span>;
}

export default function ReceiptPage({ id }: { id: string }) {
  if (new URLSearchParams(location.search).has("embed")) return <Page><FieldProofReceipt id={id} /></Page>;
  return (
    <>
      <TopBar title="FieldProof receipt" back={`/m/i/${id}/verify`} />
      <Page><FieldProofReceipt id={id} /><Link href="/m" className="block text-center text-sm text-muted-foreground">Back to home</Link></Page>
    </>
  );
}
