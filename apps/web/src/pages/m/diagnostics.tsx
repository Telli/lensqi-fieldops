import { Link } from "wouter";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Play, ShieldCheck, CheckCircle2, XCircle, ArrowRight, CircleDashed, FlaskConical } from "lucide-react";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { Expandable, Section, SourceBadge, TechDetails } from "@/components/fo/primitives";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { api, commandScope } from "@/lib/api";
import { useAct, useElapsed, useHealth } from "@/lib/hooks";
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
  const check = fixture?.diagnostics[0];
  // Keyed by the round it measures for: a retry after a lost answer replays the recorded result, never a second probe.
  const run = useAct(id, (d: string): ReturnType<typeof api.runDiagnostic> => api.runDiagnostic(id, d, commandScope(i), run.signal()));
  const elapsed = useElapsed(run.isPending);
  const last = latestDiagnostic(i);
  const start = () => { if (check) run.mutate([check.diagnosticId]); };
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

        {run.isPending && (
          <div className="panel space-y-2 p-4" role="status">
            <div className="flex justify-between text-sm"><span>Running approved check…</span><span className="font-mono text-muted-foreground">{elapsed}s</span></div>
            {/* The probe reports nothing until it finishes, so the bar shows waiting, not a made-up percentage. */}
            <div className="h-2 w-full overflow-hidden rounded-full bg-primary/20" aria-hidden><div className="h-full w-1/3 animate-indeterminate rounded-full bg-primary" /></div>
            <p className="text-[11px] text-muted-foreground">Waiting for the measurement. A result that arrives after a cancel is still recorded.</p>
            <Button variant="outline" className="h-10 w-full" onClick={run.cancel}>Cancel</Button>
          </div>
        )}

        {r && !run.isPending && (
          <Section title="Result">
            <div className={cn("panel animate-rise p-4", r.passed === true ? "border-success/30" : r.passed === false ? "border-failure/40" : "border-warning/40")}>
              <div className="flex items-center gap-2 text-base font-semibold">
                {r.passed === true ? <CheckCircle2 className="size-5 text-success" /> : r.passed === false ? <XCircle className="size-5 text-failure" /> : <CircleDashed className="size-5 text-warning" />}
                {r.passed === true ? "Pass — endpoint reachable" : r.passed === false ? "Fail — endpoint unreachable" : "No answer — check unavailable"}
              </div>
              <div className="mt-3 grid grid-cols-3 gap-2 text-center">
                {[["Result", r.passed === true ? "Pass" : r.passed === false ? "Fail" : "—"], ["Measured", r.httpStatus != null ? `HTTP ${r.httpStatus}` : r.normalizedValue ?? "—"], ["Source", r.source === "LiveLocal" ? "Live" : r.source === "Simulated" ? "Sim" : "Replay"]].map(([k, v]) => (
                  <div key={k} className="rounded-lg bg-surface p-2"><div className="eyebrow">{k}</div><div className="mt-1 text-sm">{v}</div></div>
                ))}
              </div>
              {check?.claimVerified && <p className="mt-3 text-xs text-muted-foreground">What a pass proves: {check.claimVerified}</p>}
            </div>
            <TechDetails title="Raw evidence" rows={[["Diagnostic", r.diagnosticId], ["Target alias", r.targetAlias], ["Source", r.source], ["Completed", r.completedAt], ["Trace", r.traceId], ["Response digest", r.rawDigest ?? "none (no body)"]]} />
          </Section>
        )}

        <Button size="lg" className="h-12 w-full" onClick={start} disabled={run.isPending || !check}><Play /> Run approved check</Button>
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
