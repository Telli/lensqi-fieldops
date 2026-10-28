import { Link } from "wouter";
import { XCircle, ShieldX, CheckCircle2 } from "lucide-react";
import { OpsPage } from "@/components/fo/ops-shell";
import { Kpi, EvidenceBadge, Empty } from "@/components/fo/primitives";
import { useFixtures, useInspections } from "@/lib/hooks";
import { fmtWhen, stageLabel, stageState } from "@/lib/labels";
import type { InspectionSummary } from "@/lib/api";

const DAY = 86_400_000;

export default function Overview() {
  const list = useInspections();
  const fixtures = useFixtures();
  const all = list.data ?? [];
  const assets = fixtures.data?.flatMap((f) => f.assets) ?? [];
  const nameOf = (id: string) => assets.find((a) => a.assetId === id)?.displayName ?? id;
  const open = all.filter((s) => s.stage !== "VerifiedComplete" && s.stage !== "Inconclusive");
  const pending = all.filter((s) => s.stage === "ReadyToVerify" || s.stage === "VerificationFailed");
  const verified = all.filter((s) => s.stage === "VerifiedComplete");
  const decided = all.filter((s) => s.lastDecision);
  const caught = all.filter((s) => s.latestChange === "DuplicateImage" || s.latestChange === "NoVisualChange");

  // 14-day activity from real update times.
  const days = Array.from({ length: 14 }, (_, k) => new Date(Date.now() - (13 - k) * DAY).toDateString());
  const activity = days.map((d) => all.filter((s) => new Date(s.updatedAt).toDateString() === d).length);
  const max = Math.max(1, ...activity);
  const pts = activity.map((v, k) => `${(k / 13) * 100},${100 - (v / max) * 90}`).join(" ");

  const byStage = Object.entries(all.reduce<Record<string, number>>((m, s) => ({ ...m, [s.stage]: (m[s.stage] ?? 0) + 1 }), {}))
    .sort((a, b) => b[1] - a[1]).slice(0, 6) as [InspectionSummary["stage"], number][];
  const top = Math.max(1, ...byStage.map(([, n]) => n));

  return (
    <OpsPage title="Overview" sub={`${fixtures.data?.length ?? 0} site · ${assets.length} assets · live from the inspection server`}>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Kpi label="Assets" value={assets.length} hint={`${fixtures.data?.length ?? 0} site`} />
        <Kpi label="Open inspections" value={open.length} tone="primary" hint={`${all.filter((s) => s.stage === "NeedsEvidence").length} waiting for more evidence`} />
        <Kpi label="Pending verification" value={pending.length} tone="warning" hint={`${all.filter((s) => s.stage === "VerificationFailed").length} failed`} />
        <Kpi label="Verified outcomes" value={verified.length} tone="success" hint={decided.length ? `${Math.round((verified.length / decided.length) * 100)}% of decided inspections` : "none decided yet"} />
      </div>
      {all.length === 0 && !list.isLoading && <Empty icon={CheckCircle2} title="No inspections yet" body="They appear here as technicians start them." />}
      {all.length > 0 && (
        <div className="grid gap-3 lg:grid-cols-3">
          <div className="panel p-5 lg:col-span-2">
            <div className="eyebrow">Inspection activity · 14 days</div>
            <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="mt-4 h-44 w-full">
              <polyline points={`0,100 ${pts} 100,100`} fill="var(--primary)" fillOpacity=".08" />
              <polyline points={pts} fill="none" stroke="var(--primary)" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
            </svg>
          </div>
          <div className="panel p-5">
            <div className="eyebrow">Inspections by state</div>
            <div className="mt-4 space-y-3">
              {byStage.map(([st, n]) => (
                <div key={st}>
                  <div className="flex justify-between text-sm"><span>{stageLabel[st]}</span><span className="font-mono text-muted-foreground">{n}</span></div>
                  <div className="mt-1 h-1.5 rounded-full bg-muted"><div className="h-full rounded-full bg-primary" style={{ width: `${(n / top) * 100}%` }} /></div>
                </div>
              ))}
            </div>
          </div>
          <div className="panel p-5">
            <div className="eyebrow">Failed verifications</div>
            <ul className="mt-3 space-y-2.5 text-sm">
              {all.filter((s) => s.stage === "VerificationFailed").slice(0, 4).map((s) => (
                <li key={s.inspectionId}><Link href={`/ops/inspections/${s.inspectionId}`} className="flex gap-2 hover:text-primary"><XCircle className="mt-0.5 size-4 text-failure" /><div>{nameOf(s.assetId)} · check still failing<div className="text-xs text-muted-foreground">{fmtWhen(s.updatedAt)}</div></div></Link></li>
              ))}
              {!all.some((s) => s.stage === "VerificationFailed") && <li className="text-muted-foreground">None.</li>}
            </ul>
          </div>
          <div className="panel p-5">
            <div className="eyebrow">Photos rejected by change evidence</div>
            <div className="mt-3 flex items-center gap-3"><ShieldX className="size-8 text-failure" /><div><div className="text-2xl font-semibold tabular-nums">{caught.length}</div><div className="text-xs text-muted-foreground">re-submitted or unchanged post-repair photos, caught before any check ran</div></div></div>
          </div>
          <div className="panel p-5">
            <div className="eyebrow">Latest inspection</div>
            {all[0] && (
              <Link href={`/ops/inspections/${all[0].inspectionId}`} className="mt-3 block rounded-lg border border-border p-3 hover:border-primary/40">
                <div className="flex justify-between gap-2"><span className="text-xs text-muted-foreground">{fmtWhen(all[0].updatedAt)}</span><EvidenceBadge state={stageState[all[0].stage]} label={stageLabel[all[0].stage]} /></div>
                <div className="mt-1.5 text-sm font-medium">{all[0].workOrder ?? nameOf(all[0].assetId)}</div>
                <div className="text-xs text-muted-foreground">{all[0].photos} photos · {all[0].diagnostics} checks</div>
              </Link>
            )}
          </div>
        </div>
      )}
    </OpsPage>
  );
}
