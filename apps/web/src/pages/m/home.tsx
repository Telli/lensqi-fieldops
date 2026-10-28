import { Link } from "wouter";
import { ScanLine, ChevronRight, ClipboardList, Camera } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Kpi, Section, EvidenceBadge, HealthDot, Empty } from "@/components/fo/primitives";
import { ConnBadge, Page } from "@/components/fo/mobile-shell";
import { useFixtures, useHealth, useInspections } from "@/lib/hooks";
import { fmtWhen, greeting, stageLabel, stageState } from "@/lib/labels";
import type { InspectionSummary } from "@/lib/api";

export function inspectionHref(s: Pick<InspectionSummary, "inspectionId" | "stage">) {
  const base = `/m/i/${s.inspectionId}`;
  if (s.stage === "VerifiedComplete") return `${base}/receipt`;
  if (s.stage === "ReadyToVerify" || s.stage === "VerificationFailed") return `${base}/verify`;
  if (s.stage === "Created") return `${base}/scan`;
  return base;
}

export function InspectionCard({ s, assetName }: { s: InspectionSummary; assetName?: string }) {
  return (
    <Link href={inspectionHref(s)} className="panel block p-3.5 hover:border-primary/40">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">{fmtWhen(s.updatedAt)}</span>
        <EvidenceBadge state={stageState[s.stage]} label={stageLabel[s.stage]} />
      </div>
      <div className="mt-1.5 text-sm font-medium">{s.workOrder ?? assetName ?? s.assetId}</div>
      <div className="text-xs text-muted-foreground">{assetName ?? s.assetId} · {s.photos} photo{s.photos === 1 ? "" : "s"}{s.diagnostics ? ` · ${s.diagnostics} check${s.diagnostics === 1 ? "" : "s"}` : ""}</div>
    </Link>
  );
}

export default function Home() {
  const health = useHealth();
  const list = useInspections();
  const fixtures = useFixtures();
  const all = list.data ?? [];
  const assets = fixtures.data?.flatMap((f) => f.assets.map((a) => ({ ...a, fixtureId: f.fixtureId }))) ?? [];
  const nameOf = (id: string) => assets.find((a) => a.assetId === id)?.displayName;
  const active = all.filter((s) => s.stage !== "VerifiedComplete" && s.stage !== "Inconclusive");
  const toVerify = all.filter((s) => s.stage === "ReadyToVerify" || s.stage === "VerificationFailed");
  const requested = all.filter((s) => s.openEvidenceRequest);
  const today = new Date().toDateString();
  const doneToday = all.filter((s) => s.stage === "VerifiedComplete" && new Date(s.updatedAt).toDateString() === today);
  const healthOf = (assetId: string): "healthy" | "attention" | "fault" | "unknown" => {
    const latest = all.find((s) => s.assetId === assetId);
    if (!latest) return "unknown";
    if (latest.stage === "VerifiedComplete") return "healthy";
    if (latest.stage === "FaultConfirmed" || latest.stage === "VerificationFailed" || latest.stage === "AwaitingRepair") return "fault";
    return "attention";
  };

  return (
    <Page>
      <div className="flex items-start justify-between">
        <div>
          <div className="text-xs text-muted-foreground">{fixtures.data?.[0]?.fixtureId ?? "LensQi FieldOps"}</div>
          <h1 className="mt-0.5 text-2xl font-semibold tracking-tight">{greeting()}</h1>
        </div>
        <ConnBadge reachable={!health.isError} />
      </div>

      <Button asChild size="lg" className="h-14 w-full justify-between rounded-xl text-base">
        <Link href="/m/start"><span className="flex items-center gap-2"><ScanLine /> Start an inspection</span><ChevronRight /></Link>
      </Button>

      <div className="grid grid-cols-3 gap-2.5">
        <Kpi label="Active" value={active.length} tone="primary" />
        <Kpi label="To verify" value={toVerify.length} tone="warning" />
        <Kpi label="Done today" value={doneToday.length} tone="success" />
      </div>

      {requested.length > 0 && (
        <Section title="Evidence requested">
          {requested.slice(0, 3).map((s) => (
            <Link key={s.inspectionId} href={`/m/i/${s.inspectionId}/scan`} className="panel flex items-center gap-3 border-warning/40 bg-warning/10 p-3.5 hover:border-warning/70">
              <Camera className="size-5 shrink-0 text-warning" />
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium">{s.openEvidenceRequest!.note}</div>
                <div className="text-xs text-muted-foreground">{nameOf(s.assetId) ?? s.assetId} · {s.openEvidenceRequest!.requestedBy}, {fmtWhen(s.openEvidenceRequest!.requestedAt)}</div>
              </div>
              <ChevronRight className="size-4 text-muted-foreground" />
            </Link>
          ))}
        </Section>
      )}

      {toVerify.length > 0 && (
        <Section title="Work requiring verification">
          {toVerify.slice(0, 3).map((s) => (
            <Link key={s.inspectionId} href={`/m/i/${s.inspectionId}/verify`} className="panel flex items-center justify-between p-3.5 hover:border-primary/40">
              <div>
                <div className="text-sm font-medium">{s.workOrder ?? nameOf(s.assetId) ?? s.assetId}</div>
                <div className="text-xs text-muted-foreground">{s.stage === "VerificationFailed" ? "Last verification failed" : "Repair claimed · verification not yet run"}</div>
              </div>
              <ChevronRight className="size-4 text-muted-foreground" />
            </Link>
          ))}
        </Section>
      )}

      <Section title="Open inspections" action={<Link href="/m/inspections" className="text-xs text-primary">All</Link>}>
        {active.length === 0 && !list.isLoading && (
          <Empty icon={ClipboardList} title="No open inspections" body="Start one by scanning the equipment in front of you." />
        )}
        {active.slice(0, 4).map((s) => <InspectionCard key={s.inspectionId} s={s} assetName={nameOf(s.assetId)} />)}
      </Section>

      {assets.length > 0 && (
        <Section title="Assets">
          <div className="-mx-4 flex gap-2.5 overflow-x-auto px-4 pb-1">
            {assets.map((a) => (
              <Link key={a.assetId} href={`/m/start?asset=${encodeURIComponent(a.assetId)}`} className="panel min-w-[150px] p-3 hover:border-primary/40">
                <div className="text-[11px] text-muted-foreground">{a.assetId}</div>
                <div className="mt-1 text-sm font-medium">{a.displayName}</div>
                <div className="mt-1.5"><HealthDot h={healthOf(a.assetId)} /></div>
              </Link>
            ))}
          </div>
        </Section>
      )}

      {all.length > 0 && (
        <Section title="Recent activity">
          <ol className="relative space-y-3 border-l border-border pl-4">
            {all.slice(0, 4).map((s) => (
              <li key={s.inspectionId} className="relative text-sm">
                <span className="absolute -left-[21px] top-1.5 size-2 rounded-full bg-primary" />
                <div>{nameOf(s.assetId) ?? s.assetId}: {stageLabel[s.stage].toLowerCase()}</div>
                <div className="text-[11px] text-muted-foreground">{fmtWhen(s.updatedAt)}</div>
              </li>
            ))}
          </ol>
        </Section>
      )}
    </Page>
  );
}
