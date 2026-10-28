import { useState } from "react";
import { Link } from "wouter";
import { useQueries, useQuery } from "@tanstack/react-query";
import { Boxes, FileCheck2, Search, ExternalLink, Download, ShieldCheck, ShieldX } from "lucide-react";
import { OpsPage } from "@/components/fo/ops-shell";
import { EvidenceBadge, HealthDot, Empty, SourceBadge, Section } from "@/components/fo/primitives";
import { ExpectedState } from "@/components/fo/expected-state";
import { ArFrame } from "@/components/fo/ar-frame";
import { api, receiptHtmlUrl, receiptJsonUrl, type InspectionSummary } from "@/lib/api";
import { arTags, usePhotoOutlines } from "@/lib/ar";
import { useFixtures, useInspections } from "@/lib/hooks";
import { lastPhoto, useInspectionBundle } from "@/lib/inspection";
import { fmtWhen, stageLabel, stageState } from "@/lib/labels";
import { cn } from "@/lib/utils";

const health = (s?: InspectionSummary): "healthy" | "attention" | "fault" | "unknown" =>
  !s ? "unknown" : s.stage === "VerifiedComplete" ? "healthy" : ["FaultConfirmed", "VerificationFailed", "AwaitingRepair"].includes(s.stage) ? "fault" : "attention";

const Chips = ({ options, value, onChange }: { options: string[]; value: number; onChange: (k: number) => void }) => (
  <div className="flex flex-wrap gap-1.5">
    {options.map((x, k) => (
      <button key={x} onClick={() => onChange(k)} className={cn("rounded-full border px-3 py-1 text-xs", k === value ? "border-primary/50 bg-primary/10 text-primary" : "border-border text-muted-foreground")}>{x}</button>
    ))}
  </div>
);

export function AssetRegistry() {
  const fixtures = useFixtures();
  const list = useInspections();
  const [q, setQ] = useState("");
  const match = (a: { assetId: string; displayName: string; kind: string }) => !q.trim() || `${a.assetId} ${a.displayName} ${a.kind}`.toLowerCase().includes(q.trim().toLowerCase());
  return (
    <OpsPage title="Assets" sub="From each site's expected setup">
      <label className="panel flex max-w-sm items-center gap-2 px-3 py-2"><Search className="size-4 text-muted-foreground" />
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search assets" className="w-full bg-transparent text-sm outline-none" /></label>
      {fixtures.data?.map((f) => (
        <div key={f.fixtureId} className="panel overflow-hidden">
          <div className="border-b border-border px-4 py-3"><div className="text-sm font-medium">{f.fixtureId} · revision {f.fixtureRevision}</div><div className="text-xs text-muted-foreground">{f.fixtureDescription}</div></div>
          <table className="w-full text-sm"><tbody>
            {f.assets.filter(match).map((a) => {
              const mine = list.data?.filter((s) => s.assetId === a.assetId) ?? [];
              const links = (f.connections ?? []).filter((c) => c.fromAssetId === a.assetId || c.toAssetId === a.assetId).length;
              return (
                <tr key={a.assetId} className="border-b border-border/60 last:border-0 hover:bg-muted/40">
                  <td className="px-4 py-2.5"><Link href={`/ops/assets/${a.assetId}`} className="font-medium hover:text-primary">{a.displayName}</Link><div className="text-xs text-muted-foreground">{a.assetId} · {a.kind.replace(/_/g, " ")}</div></td>
                  <td className="px-4 py-2.5"><HealthDot h={health(mine[0])} /></td>
                  <td className="px-4 py-2.5 text-xs text-muted-foreground">{links} expected connection{links === 1 ? "" : "s"}</td>
                  <td className="px-4 py-2.5 text-xs text-muted-foreground">{mine.length} inspection{mine.length === 1 ? "" : "s"}</td>
                  <td className="px-4 py-2.5 text-right text-xs"><Link href={`/ops/assets/${a.assetId}`} className="text-primary">Details</Link></td>
                </tr>
              );
            })}
          </tbody></table>
        </div>
      ))}
    </OpsPage>
  );
}

/** The latest photo of an asset with its outlines, from the inspection that took it. */
export function LatestEvidence({ inspectionId, className }: { inspectionId: string; className?: string }) {
  const { i, fixture, assessment, photoUrl } = useInspectionBundle(inspectionId);
  const photo = lastPhoto(i);
  const outlines = usePhotoOutlines(inspectionId, i, photo?.evidenceId);
  const change = i?.changeAnalyses.find((c) => c.photoEvidenceId === photo?.evidenceId);
  if (!photo || !photoUrl(photo.evidenceId)) return <div className="panel grid h-48 place-items-center text-sm text-muted-foreground">No photo yet</div>;
  return (
    <div className={className}>
      <ArFrame src={photoUrl(photo.evidenceId)!} outlines={outlines} tags={arTags(fixture, i?.currentClaimId ? undefined : assessment, outlines, change)} className="panel bg-overlay" imgClassName="max-h-[360px] object-contain" />
      <p className="mt-1.5 text-[11px] text-muted-foreground">Latest photo · {fmtWhen(photo.observedAt)} · <span className="font-mono">{photo.artifactSha256.slice(0, 16)}…</span></p>
    </div>
  );
}

export function AssetDetail({ assetId }: { assetId: string }) {
  const fixtures = useFixtures();
  const list = useInspections();
  const fixture = fixtures.data?.find((f) => f.assets.some((a) => a.assetId === assetId));
  const asset = fixture?.assets.find((a) => a.assetId === assetId);
  const mine = (list.data ?? []).filter((s) => s.assetId === assetId);
  const withPhoto = mine.find((s) => s.photos > 0);
  if (fixtures.isLoading) return <OpsPage title="Asset">Loading…</OpsPage>;
  if (!asset) return <OpsPage title="Asset"><Empty icon={Boxes} title={`No asset ${assetId} in any site record`} /></OpsPage>;
  return (
    <OpsPage title={asset.displayName} sub={`${asset.assetId} · ${asset.kind.replace(/_/g, " ")} · ${fixture!.fixtureId}`} actions={<HealthDot h={health(mine[0])} />}>
      <div className="grid gap-4 lg:grid-cols-2">
        <Section title="Expected installation state"><ExpectedState fixture={fixture} assetId={assetId} /></Section>
        <Section title="Latest evidence">{withPhoto ? <LatestEvidence inspectionId={withPhoto.inspectionId} /> : <p className="text-sm text-muted-foreground">No photos of this asset yet.</p>}</Section>
      </div>
      <Section title={`Inspection history · ${mine.length}`}>
        <div className="panel overflow-x-auto">
          <table className="w-full text-sm">
            <thead><tr className="border-b border-border text-left text-xs text-muted-foreground">{["Updated", "State", "Work", "Photos", "Attempts", ""].map((h) => <th key={h} className="px-4 py-2.5 font-medium">{h}</th>)}</tr></thead>
            <tbody>
              {mine.map((s) => (
                <tr key={s.inspectionId} className="border-b border-border/60 last:border-0">
                  <td className="px-4 py-2.5 text-xs text-muted-foreground">{fmtWhen(s.updatedAt)}</td>
                  <td className="px-4 py-2.5"><EvidenceBadge state={stageState[s.stage]} label={stageLabel[s.stage]} /></td>
                  <td className="max-w-[280px] truncate px-4 py-2.5">{s.workOrder ?? "—"}{s.openEvidenceRequest && <span className="ml-2 text-xs text-warning">evidence requested</span>}</td>
                  <td className="px-4 py-2.5 tabular-nums">{s.photos}</td>
                  <td className="px-4 py-2.5 tabular-nums">{s.verificationAttempts ?? 0}</td>
                  <td className="px-4 py-2.5 text-right text-xs"><Link href={`/ops/inspections/${s.inspectionId}`} className="text-primary">Open</Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>
    </OpsPage>
  );
}

export function WorkOrders() {
  const list = useInspections();
  const fixtures = useFixtures();
  const nameOf = (id: string) => fixtures.data?.flatMap((x) => x.assets).find((a) => a.assetId === id)?.displayName ?? id;
  const withWork = (list.data ?? []).filter((s) => s.workOrder);
  const cols: { title: string; match: (s: InspectionSummary) => boolean }[] = [
    { title: "Repair in progress", match: (s) => s.stage === "AwaitingRepair" || (!s.currentClaimId && s.stage !== "VerifiedComplete") },
    { title: "Awaiting verification", match: (s) => !!s.currentClaimId && ["ReadyToVerify", "NeedsEvidence"].includes(s.stage) },
    { title: "Verification failed", match: (s) => s.stage === "VerificationFailed" },
    { title: "Verified", match: (s) => s.stage === "VerifiedComplete" },
  ];
  return (
    <OpsPage title="Work orders" sub="Drafted by technicians during inspections">
      {withWork.length === 0 && !list.isLoading && <Empty icon={Boxes} title="No work orders yet" />}
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        {cols.map((c) => (
          <div key={c.title} className="space-y-2">
            <div className="eyebrow">{c.title} · {withWork.filter(c.match).length}</div>
            {withWork.filter(c.match).slice(0, 12).map((s) => (
              <Link key={s.inspectionId} href={`/ops/inspections/${s.inspectionId}`} className="panel block p-3 hover:border-primary/40">
                <div className="text-sm font-medium">{s.workOrder}</div>
                <div className="mt-1 text-xs text-muted-foreground">{nameOf(s.assetId)} · {fmtWhen(s.updatedAt)}</div>
                {s.claim && <div className="mt-1.5 text-xs">Done: {s.claim}</div>}
              </Link>
            ))}
          </div>
        ))}
      </div>
    </OpsPage>
  );
}

export function KnowledgePacks() {
  const packs = useQuery({ queryKey: ["packs"], queryFn: api.packs });
  return (
    <OpsPage title="Knowledge" sub="Vendor and site documentation the agent may cite. Guidance, never evidence.">
      {(packs.data ?? []).map((p) => (
        <div key={p.packId} className="panel overflow-hidden">
          <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border px-4 py-3">
            <div>
              <div className="text-sm font-medium">{p.manufacturerId} · {p.productCode}</div>
              <div className="text-xs text-muted-foreground">{p.packId} · revision {p.packRevision} · {p.originKind === "SiteConfigured" ? "site configured" : "vendor documentation"}</div>
            </div>
            <div className="flex flex-wrap gap-1.5 text-[11px]">
              <span className="rounded-full border border-border px-2 py-0.5">Firmware {p.firmwareMajor ? `${p.firmwareMajor}.x` : "any"}</span>
              <span className="rounded-full border border-border px-2 py-0.5">Hardware {p.hardwareRevisions.length ? p.hardwareRevisions.join(", ") : "any"}</span>
              <span className="rounded-full border border-border px-2 py-0.5">{p.factCount} facts</span>
            </div>
          </div>
          <table className="w-full text-sm">
            <thead><tr className="border-b border-border text-left text-xs text-muted-foreground">{["Source document", "Publisher", "Version", "Accessed", "Facts", ""].map((h) => <th key={h} className="px-4 py-2 font-medium">{h}</th>)}</tr></thead>
            <tbody>
              {p.documents.map((d) => (
                <tr key={d.documentId} className="border-b border-border/60 last:border-0">
                  <td className="px-4 py-2.5"><div className="font-medium">{d.title}</div><div className="text-xs text-muted-foreground">{d.documentId} · {d.rightsMode.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase()}</div></td>
                  <td className="px-4 py-2.5 text-xs">{d.publisher}</td>
                  <td className="px-4 py-2.5 text-xs">{d.documentVersion ?? "—"}</td>
                  <td className="px-4 py-2.5 text-xs text-muted-foreground">{d.accessedOn}</td>
                  <td className="px-4 py-2.5 tabular-nums">{d.factCount}</td>
                  <td className="px-4 py-2.5 text-right text-xs">{d.url ? <a href={d.url} target="_blank" rel="noopener" className="inline-flex items-center gap-1 text-primary">Source <ExternalLink className="size-3" /></a> : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="break-all border-t border-border px-4 py-2 font-mono text-[11px] text-muted-foreground">sha256 {p.sha256}</div>
        </div>
      ))}
    </OpsPage>
  );
}

const reportFilters: { label: string; match: (s: InspectionSummary) => boolean }[] = [
  { label: "All", match: () => true },
  { label: "Verified", match: (s) => s.stage === "VerifiedComplete" },
  { label: "Not verified", match: (s) => s.stage === "VerificationFailed" },
  { label: "In progress", match: (s) => s.stage !== "VerifiedComplete" && s.stage !== "VerificationFailed" },
];

export function Reports() {
  const list = useInspections();
  const fixtures = useFixtures();
  const [f, setF] = useState(0);
  const nameOf = (id: string) => fixtures.data?.flatMap((x) => x.assets).find((a) => a.assetId === id)?.displayName ?? id;
  const claimed = (list.data ?? []).filter((s) => s.lastDecision || s.currentClaimId);
  const rows = claimed.filter(reportFilters[f]!.match).slice(0, 40);
  // Re-hash every listed receipt bundle on the server; a tampered artifact or event shows here.
  const checks = useQueries({ queries: rows.map((s) => ({ queryKey: ["receipt-verify", s.inspectionId, s.updatedAt], queryFn: () => api.verifyReceipt(s.inspectionId), staleTime: 60_000 })) });
  return (
    <OpsPage title="FieldProof reports" sub="Every repair claim gets a hash-verified receipt; every attempt stays on it">
      <Chips options={reportFilters.map((x) => `${x.label} · ${claimed.filter(x.match).length}`)} value={f} onChange={setF} />
      {rows.length === 0 && !list.isLoading && <Empty icon={FileCheck2} title="No receipts here" />}
      <div className="panel overflow-x-auto">
        <table className="w-full text-sm">
          <thead><tr className="border-b border-border text-left text-xs text-muted-foreground">{["Asset", "Result", "Attempts", "Check source", "Integrity", "Updated", ""].map((h) => <th key={h} className="whitespace-nowrap px-4 py-2.5 font-medium">{h}</th>)}</tr></thead>
          <tbody>
            {rows.map((s, k) => {
              const c = checks[k]?.data;
              return (
                <tr key={s.inspectionId} className="border-b border-border/60 last:border-0">
                  <td className="px-4 py-2.5"><Link href={`/ops/inspections/${s.inspectionId}`} className="font-medium hover:text-primary">{nameOf(s.assetId)}</Link><div className="max-w-[240px] truncate text-xs text-muted-foreground">{s.claim ?? s.workOrder}</div></td>
                  <td className="px-4 py-2.5"><EvidenceBadge state={stageState[s.stage]} label={stageLabel[s.stage]} /></td>
                  <td className="px-4 py-2.5 tabular-nums">{s.verificationAttempts ?? 0}</td>
                  <td className="px-4 py-2.5">{s.outcomeKind ? <SourceBadge source={s.outcomeKind === "VerifiedCompleteLive" ? "LiveLocal" : "Simulated"} /> : "—"}</td>
                  <td className="whitespace-nowrap px-4 py-2.5 text-xs">
                    {!c ? <span className="text-muted-foreground">Checking…</span>
                      : c.isValid ? <span className="inline-flex items-center gap-1 text-success"><ShieldCheck className="size-3.5" />Intact · {c.checks.length}</span>
                      : <span className="inline-flex items-center gap-1 text-failure" title={c.failures.join("; ")}><ShieldX className="size-3.5" />Tampered</span>}
                  </td>
                  <td className="whitespace-nowrap px-4 py-2.5 text-xs text-muted-foreground">{fmtWhen(s.updatedAt)}</td>
                  <td className="whitespace-nowrap px-4 py-2.5 text-right text-xs">
                    <span className="inline-flex gap-3">
                      <a href={receiptHtmlUrl(s.inspectionId)} target="_blank" rel="noopener" className="text-primary">Receipt</a>
                      <a href={receiptJsonUrl(s.inspectionId)} download={`fieldproof-${s.inspectionId}.json`} className="inline-flex items-center gap-1 text-primary"><Download className="size-3" />JSON</a>
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </OpsPage>
  );
}
