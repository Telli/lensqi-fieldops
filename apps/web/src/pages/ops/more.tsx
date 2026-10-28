import { Link } from "wouter";
import { useQuery } from "@tanstack/react-query";
import { Boxes, FileCheck2 } from "lucide-react";
import { OpsPage } from "@/components/fo/ops-shell";
import { EvidenceBadge, HealthDot, Empty, SourceBadge } from "@/components/fo/primitives";
import { api, receiptHtmlUrl, type InspectionSummary } from "@/lib/api";
import { useFixtures, useInspections } from "@/lib/hooks";
import { fmtWhen, stageLabel, stageState } from "@/lib/labels";

const health = (s?: InspectionSummary): "healthy" | "attention" | "fault" | "unknown" =>
  !s ? "unknown" : s.stage === "VerifiedComplete" ? "healthy" : ["FaultConfirmed", "VerificationFailed", "AwaitingRepair"].includes(s.stage) ? "fault" : "attention";

export function AssetRegistry() {
  const fixtures = useFixtures();
  const list = useInspections();
  return (
    <OpsPage title="Assets" sub="From each site's expected setup">
      {fixtures.data?.map((f) => (
        <div key={f.fixtureId} className="panel overflow-hidden">
          <div className="border-b border-border px-4 py-3"><div className="text-sm font-medium">{f.fixtureId} · revision {f.fixtureRevision}</div><div className="text-xs text-muted-foreground">{f.fixtureDescription}</div></div>
          <table className="w-full text-sm"><tbody>
            {f.assets.map((a) => {
              const mine = list.data?.filter((s) => s.assetId === a.assetId) ?? [];
              return (
                <tr key={a.assetId} className="border-b border-border/60 last:border-0">
                  <td className="px-4 py-2.5"><div className="font-medium">{a.displayName}</div><div className="text-xs text-muted-foreground">{a.assetId} · {a.kind.replace(/_/g, " ")}</div></td>
                  <td className="px-4 py-2.5"><HealthDot h={health(mine[0])} /></td>
                  <td className="px-4 py-2.5 text-xs text-muted-foreground">{mine.length} inspection{mine.length === 1 ? "" : "s"}</td>
                  <td className="px-4 py-2.5 text-right text-xs">{mine[0] ? <Link href={`/ops/inspections/${mine[0].inspectionId}`} className="text-primary">Latest</Link> : "—"}</td>
                </tr>
              );
            })}
          </tbody></table>
        </div>
      ))}
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
      <div className="grid gap-3 md:grid-cols-2">
        {(packs.data ?? []).map((p) => (
          <div key={String(p.packId)} className="panel space-y-1.5 p-4">
            <div className="text-sm font-medium">{String(p["title"] ?? p.packId)}</div>
            <div className="text-xs text-muted-foreground">{String(p.packId)}{p["revision"] ? ` · revision ${p["revision"]}` : ""}</div>
            {p["sha256"] ? <div className="break-all font-mono text-[11px] text-muted-foreground">{String(p["sha256"])}</div> : null}
          </div>
        ))}
      </div>
    </OpsPage>
  );
}

export function Reports() {
  const list = useInspections();
  const fixtures = useFixtures();
  const nameOf = (id: string) => fixtures.data?.flatMap((x) => x.assets).find((a) => a.assetId === id)?.displayName ?? id;
  const decided = (list.data ?? []).filter((s) => s.lastDecision);
  return (
    <OpsPage title="FieldProof reports" sub="Every decided inspection issues a hash-verified receipt">
      {decided.length === 0 && !list.isLoading && <Empty icon={FileCheck2} title="No receipts yet" />}
      <div className="panel overflow-x-auto">
        <table className="w-full text-sm">
          <thead><tr className="border-b border-border text-left text-xs text-muted-foreground">{["Asset", "Result", "Check source", "Decided", ""].map((h) => <th key={h} className="px-4 py-2.5 font-medium">{h}</th>)}</tr></thead>
          <tbody>
            {decided.map((s) => (
              <tr key={s.inspectionId} className="border-b border-border/60 last:border-0">
                <td className="px-4 py-2.5"><Link href={`/ops/inspections/${s.inspectionId}`} className="font-medium hover:text-primary">{nameOf(s.assetId)}</Link></td>
                <td className="px-4 py-2.5"><EvidenceBadge state={stageState[s.stage]} label={stageLabel[s.stage]} /></td>
                <td className="px-4 py-2.5">{s.outcomeKind ? <SourceBadge source={s.outcomeKind === "VerifiedCompleteLive" ? "LiveLocal" : "Simulated"} /> : "—"}</td>
                <td className="px-4 py-2.5 text-xs text-muted-foreground">{fmtWhen(s.updatedAt)}</td>
                <td className="px-4 py-2.5 text-right text-xs"><a href={receiptHtmlUrl(s.inspectionId)} target="_blank" rel="noopener" className="text-primary">Receipt</a></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </OpsPage>
  );
}
