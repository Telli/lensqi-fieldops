import { useState } from "react";
import { Link } from "wouter";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Play, ShieldCheck, CheckCircle2, XCircle, ArrowRight, CircleDashed, FlaskConical } from "lucide-react";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { Expandable, Section, SourceBadge, TechDetails } from "@/components/fo/primitives";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { Switch } from "@/components/ui/switch";
import { api } from "@/lib/api";
import { useAct, useHealth } from "@/lib/hooks";
import { latestDiagnostic, useInspectionBundle } from "@/lib/inspection";
import { fmtWhen } from "@/lib/labels";
import { cn } from "@/lib/utils";

/** Only fixture-approved checks, resolved on the server. The app never names a host, command or target. */
export default function Diagnostics({ id }: { id: string }) {
  const { i, fixture } = useInspectionBundle(id);
  const health = useHealth();
  const qc = useQueryClient();
  const simulated = health.data?.diagnosticsMode === "Simulated";
  const sim = useQuery({ queryKey: ["simulation"], queryFn: api.simulation, enabled: simulated });
  const [progress, setProgress] = useState<number | null>(null);
  const check = fixture?.diagnostics[0];
  const run = useAct(id, (d: string) => api.runDiagnostic(id, d));
  const last = latestDiagnostic(i);

  const start = () => {
    if (!check) return;
    setProgress(8);
    const iv = setInterval(() => setProgress((p) => Math.min(92, (p ?? 0) + 14)), 160);
    run.mutate([check.diagnosticId], { onSettled: () => { clearInterval(iv); setProgress(null); } });
  };
  const simUp = sim.data?.outcomes[check?.diagnosticId ?? ""] ?? null;
  const setSim = async (up: boolean) => { if (check) { await api.simulate(check.diagnosticId, up); qc.invalidateQueries({ queryKey: ["simulation"] }); } };

  const meta: [string, React.ReactNode][] = [
    ["Check", check ? (check.type === "http_health" ? "Endpoint health" : check.type) : "—"],
    ["Target", check ? `${check.targetAlias} (set by the site, not the app)` : "—"],
    ["Authorization", <span className="flex items-center gap-1 text-success"><ShieldCheck className="size-3.5" />Approved for {i?.fixtureId}</span>],
    ["Mode", health.data ? <SourceBadge source={health.data.diagnosticsMode} /> : "…"],
    ["Last run", last ? fmtWhen(last.result.completedAt) : "—"],
  ];
  const r = last?.result;

  return (
    <>
      <TopBar title="Equipment check" back={i?.currentClaimId ? `/m/i/${id}/verify` : `/m/i/${id}`} right={health.data && <SourceBadge source={health.data.diagnosticsMode} />} />
      <Page>
        <dl className="panel divide-y divide-border">
          {meta.map(([k, v]) => (
            <div key={k} className="flex items-center justify-between gap-4 px-3.5 py-2.5 text-sm"><dt className="text-muted-foreground">{k}</dt><dd className="text-right">{v}</dd></div>
          ))}
        </dl>
        <p className="text-xs text-muted-foreground">LensQi can only run checks the site has approved for this equipment. It cannot run arbitrary commands.</p>

        {progress !== null && (
          <div className="panel space-y-2 p-4">
            <div className="flex justify-between text-sm"><span>Running approved check…</span><span className="font-mono text-muted-foreground">{progress}%</span></div>
            <Progress value={progress} />
          </div>
        )}

        {r && progress === null && (
          <Section title="Result">
            <div className={cn("panel animate-rise p-4", r.passed === true ? "border-success/30" : r.passed === false ? "border-failure/40" : "border-warning/40")}>
              <div className="flex items-center gap-2 text-base font-semibold">
                {r.passed === true ? <CheckCircle2 className="size-5 text-success" /> : r.passed === false ? <XCircle className="size-5 text-failure" /> : <CircleDashed className="size-5 text-warning" />}
                {r.passed === true ? "Pass — endpoint reachable" : r.passed === false ? "Fail — endpoint unreachable" : "No answer — check unavailable"}
              </div>
              <div className="mt-3 grid grid-cols-3 gap-2 text-center">
                {[["Result", r.passed === true ? "Pass" : r.passed === false ? "Fail" : "—"], ["Measured", r.normalizedValue ?? "—"], ["Source", r.source === "LiveLocal" ? "Live" : r.source === "Simulated" ? "Sim" : "Replay"]].map(([k, v]) => (
                  <div key={k} className="rounded-lg bg-surface p-2"><div className="eyebrow">{k}</div><div className="mt-1 text-sm">{v}</div></div>
                ))}
              </div>
              {check?.claimVerified && <p className="mt-3 text-xs text-muted-foreground">What a pass proves: {check.claimVerified}</p>}
            </div>
            <TechDetails title="Raw evidence" rows={[["Diagnostic", r.diagnosticId], ["Target alias", r.targetAlias], ["Source", r.source], ["Completed", r.completedAt], ["Trace", r.traceId], ["Response digest", r.rawDigest ?? "none (no body)"]]} />
          </Section>
        )}

        <Button size="lg" className="h-12 w-full" onClick={start} disabled={progress !== null || !check}><Play /> Run approved check</Button>
        {last && (
          <Button asChild variant="outline" className="h-11 w-full">
            <Link href={i?.currentClaimId ? `/m/i/${id}/verify` : `/m/i/${id}`}>{i?.currentClaimId ? "Back to verification" : "Back to investigation"} <ArrowRight /></Link>
          </Button>
        )}

        {simulated && check && (
          <Expandable title={<span className="flex items-center gap-2 text-muted-foreground"><FlaskConical className="size-4" /> Demo controls</span>}>
            <label className="flex items-center justify-between gap-3 rounded-lg border border-dashed border-border p-3 text-xs">
              <span><span className="font-medium text-foreground">Simulated endpoint reachable</span><br />Stands in for the lab endpoint. Results it produces are always labelled Simulated.</span>
              <Switch checked={simUp === true} onCheckedChange={setSim} />
            </label>
          </Expandable>
        )}
      </Page>
    </>
  );
}
