import { useQuery } from "@tanstack/react-query";
import { Link } from "wouter";
import { toast } from "sonner";
import { Download, Share2, Fingerprint, Printer, ShieldCheck, Loader2 } from "lucide-react";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { Button } from "@/components/ui/button";
import { Logo, EvidenceBadge, Citation, SourceBadge, TechDetails } from "@/components/fo/primitives";
import { EvidencePhoto } from "@/components/fo/evidence-photo";
import { api, receiptHtmlUrl, receiptJsonUrl, type Decision, type DiagnosticResult, type EventRecord } from "@/lib/api";
import { beforePhoto, useGeometry, useInspectionBundle } from "@/lib/inspection";
import { featureName, fmtWhen, isPostRepairItem, plainReason } from "@/lib/labels";
import { cn } from "@/lib/utils";

interface Receipt {
  inspection: { inspectionId: string; assetId: string; fixtureId: string; fixtureRevision: number; stage: string };
  versions: { policyVersion: string; receiptVersion: string };
  diagnostics: { evidenceId: string; result: DiagnosticResult }[];
  decision?: Decision | null;
  knowledge?: { packId: string; citations: { factId: string; documentTitle: string; sourceLocator: string; sourceUrl?: string | null }[] } | null;
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
      <header className="flex items-center justify-between border-b border-border bg-surface px-4 py-3">
        <div className="flex items-center gap-2"><Logo size={22} /><span className="text-sm font-semibold">FieldProof Receipt</span></div>
        <EvidenceBadge state={ok ? "verified" : attempts.length ? "failed" : "evidence-required"} label={ok ? "Verified complete" : attempts.length ? "Not verified" : "Pending"} />
      </header>
      <div className={cn("space-y-4 p-4 text-sm", wide && "grid gap-6 space-y-0 md:grid-cols-2")}>
        <div className="space-y-4">
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-xs">
            {[["Asset", asset?.displayName ?? r.inspection.assetId], ["Site", `${r.inspection.fixtureId} rev ${r.inspection.fixtureRevision}`], ["Issued", fmtWhen(r.generatedAt)], ["Result", ok ? (r.decision?.outcomeKind === "VerifiedCompleteLive" ? "Verified · live check" : "Verified · simulated check") : r.decision ? "Not verified" : "Open"]].map(([k, v]) => (
              <div key={k}><dt className="text-muted-foreground">{k}</dt><dd className="font-medium">{v}</dd></div>
            ))}
          </dl>
          <div><div className="eyebrow">Initial discrepancy</div><p className="mt-1">
            {firstFail ? "The approved equipment check failed" : "Inspection opened"}{unclear?.length ? `; the first photo left ${unclear.join(" and ")} unclear` : ""}.
          </p></div>
          {claims.length > 0 && (
            <div><div className="eyebrow">Technician action</div>
              <ol className="mt-1 list-decimal space-y-0.5 pl-4">{claims.map((c, k) => <li key={k}>{c}</li>)}</ol>
            </div>
          )}
          <div className="grid grid-cols-2 gap-2">
            {[["Before", before, beforeGeo.data, "warning"], ["After", after, afterGeo.data, "success"]].map(([label, p, g, tone]) => {
              const photo = p as typeof before;
              const src = photo && photoUrl(photo.evidenceId);
              return (
                <div key={label as string} className="overflow-hidden rounded-lg border border-border">
                  {src ? <EvidencePhoto src={src} geometry={g as never} tone={tone as "warning" | "success"} imgClassName="h-24 object-cover">
                    <figcaption className="glass absolute bottom-1 left-1 rounded px-1.5 text-[10px]">{label as string}{(g as { matched?: boolean } | null)?.matched ? " · outlined" : ""}</figcaption>
                  </EvidencePhoto> : <div className="grid h-24 place-items-center text-[11px] text-muted-foreground">No {String(label).toLowerCase()} photo</div>}
                </div>
              );
            })}
          </div>
        </div>
        <div className="space-y-4">
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
          {r.knowledge?.citations?.length ? (
            <div className="space-y-1.5"><div className="eyebrow">Documentation references</div>
              {r.knowledge.citations.slice(0, 3).map((c) => <Citation key={c.factId} title={c.documentTitle} section={c.sourceLocator} href={c.sourceUrl} />)}
            </div>
          ) : null}
          <div className="rounded-lg border border-border bg-surface p-3">
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
            <Button asChild><a href={receiptJsonUrl(id)} download={`fieldproof-${id}.json`}><Download /> Export</a></Button>
            <Button variant="outline" onClick={share}><Share2 /> Share</Button>
            <Button asChild variant="outline" className="col-span-2"><a href={receiptHtmlUrl(id)} target="_blank" rel="noopener"><Printer /> Printable receipt</a></Button>
          </div>
          <TechDetails rows={[["Inspection", r.inspection.inspectionId], ["Policy", r.versions.policyVersion], ["Receipt format", r.versions.receiptVersion], ...r.artifacts.map((a): [string, string] => [a.evidenceId, a.sha256])]} />
        </div>
      </div>
    </article>
  );
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
