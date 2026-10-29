import { useState } from "react";
import { Link, useLocation } from "wouter";
import { AlertTriangle, ArrowRight, Camera, Clock, Hand, Loader2, OctagonX, RotateCcw, ShieldAlert, Wrench } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Page, TopBar } from "@/components/fo/mobile-shell";
import { AiCard } from "@/components/fo/ai";
import { cautionLabel, riskTone, tasks, useTask, useTaskAct, type TaskStep } from "@/lib/tasks";
import { cn } from "@/lib/utils";

function StepItem({ step, n }: { step: TaskStep; n: number }) {
  return (
    <li className="flex gap-3 p-3.5">
      <span className={cn("grid size-7 shrink-0 place-items-center rounded-full text-xs font-semibold",
        step.status === "Done" ? "bg-success/15 text-success" : step.status === "Active" ? "bg-primary text-primary-foreground" : step.status === "Skipped" ? "bg-muted text-muted-foreground line-through" : "bg-muted")}>{n}</span>
      <div className="min-w-0 flex-1 space-y-1.5">
        <div className="flex items-center gap-2 text-sm font-medium">{step.title}{step.handsOn && <Hand className="size-3.5 text-muted-foreground" aria-label="Hands-on" />}{step.check?.required && <Camera className="size-3.5 text-muted-foreground" aria-label="Photo check" />}</div>
        <p className="text-xs text-muted-foreground">{step.instruction}</p>
        {step.cautions.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {step.cautions.map((c, k) => <span key={k} className="rounded-full border border-warning/40 bg-warning/10 px-2 py-0.5 text-[11px] text-warning">{cautionLabel[c.kind]} · {c.text}</span>)}
          </div>
        )}
        {step.stopIf.length > 0 && <p className="text-[11px] text-failure">Stop if {step.stopIf.join("; ")}.</p>}
      </div>
    </li>
  );
}

/** The AI-written plan, its risk and safety notes, before anything starts. */
export default function TaskPlanPage({ id }: { id: string }) {
  const { data: t, isLoading } = useTask(id);
  const [, nav] = useLocation();
  const [ack, setAck] = useState(false);
  const replan = useTaskAct(id, () => tasks.plan(id));
  const accept = useTaskAct(id, () => tasks.accept(id, ack));

  if (isLoading || !t) return <><TopBar title="Plan" back="/m" /><Page><Loader2 className="mx-auto animate-spin" /></Page></>;
  const plan = t.plan;
  if (!plan)
    return (
      <>
        <TopBar title="Plan" back="/m" />
        <Page className="space-y-4">
          <AiCard says={t.goal} title="The plan isn't ready" hint="The planner's answer failed its checks or the service was busy. A new attempt is checked the same way.">
            <Button size="lg" className="h-12 w-full rounded-2xl" disabled={replan.isPending} onClick={() => replan.mutate([])}>
              {replan.isPending ? <Loader2 className="animate-spin" /> : <RotateCcw />} {replan.isPending ? "Writing the plan…" : "Try again"}
            </Button>
          </AiCard>
        </Page>
      </>
    );

  const started = t.phase !== "Planned";
  const allCautions = [...new Map(plan.steps.flatMap((s) => s.cautions).map((c) => [c.text, c])).values()];
  return (
    <>
      <TopBar title={t.mode === "Diy" ? "Your plan" : "Job plan"} back="/m" />
      <Page className="space-y-4">
        <div className="space-y-1.5">
          <p className="text-[11px] uppercase tracking-[0.14em] text-muted-foreground">{plan.sourceNote}</p>
          <h1 className="text-xl font-semibold tracking-tight">{plan.title}</h1>
          <p className="text-sm text-muted-foreground">{plan.summary}</p>
          <div className="flex flex-wrap gap-3 pt-1 text-xs">
            <span className={cn("flex items-center gap-1 font-medium", riskTone[plan.risk])}><ShieldAlert className="size-3.5" />{plan.risk} risk</span>
            <span className="flex items-center gap-1 text-muted-foreground"><Clock className="size-3.5" />about {plan.estimatedMinutes} min</span>
            <span className="text-muted-foreground">{plan.steps.length} steps</span>
          </div>
        </div>

        {plan.professionalRequired && (
          <div className="flex gap-3 rounded-2xl border border-failure/40 bg-failure/10 p-3.5 text-sm" role="alert">
            <OctagonX className="mt-0.5 size-5 shrink-0 text-failure" />
            <div><p className="font-medium text-failure">This needs a professional</p><p className="mt-0.5 text-xs">{plan.professionalReason} LensQi will only help you prepare and document; it won't guide hands-on work here.</p></div>
          </div>
        )}

        {(plan.tools.length > 0 || plan.parts.length > 0) && (
          <section className="panel space-y-2 p-3.5 text-sm">
            {plan.tools.length > 0 && <p><Wrench className="mr-1.5 inline size-3.5 text-muted-foreground" /><span className="font-medium">Tools:</span> {plan.tools.join(", ")}</p>}
            {plan.parts.length > 0 && <p><span className="font-medium">Parts:</span> {plan.parts.join(", ")}</p>}
          </section>
        )}

        <ol className="divide-y divide-border/70 overflow-hidden rounded-3xl border border-border bg-surface">
          {plan.steps.map((s, n) => <StepItem key={s.id} step={s} n={n + 1} />)}
        </ol>

        {started ? (
          <Button asChild size="lg" className="h-12 w-full rounded-2xl">
            <Link href={t.phase === "InProgress" ? `/m/t/${id}/step` : `/m/t/${id}/done`}>{t.phase === "InProgress" ? "Continue" : "See the summary"} <ArrowRight /></Link>
          </Button>
        ) : (
          <div className="space-y-3">
            {plan.needsSafetyAck && (
              <label className="flex gap-3 rounded-2xl border border-warning/40 bg-warning/5 p-3.5 text-sm">
                <input type="checkbox" checked={ack} onChange={(e) => setAck(e.target.checked)} className="mt-1 size-4 accent-[var(--primary)]" />
                <span>
                  <span className="flex items-center gap-1.5 font-medium"><AlertTriangle className="size-4 text-warning" />I've read the safety notes</span>
                  {allCautions.length > 0 && <span className="mt-1 block text-xs text-muted-foreground">{allCautions.slice(0, 4).map((c) => c.text).join(" · ")}{allCautions.length > 4 ? " · …" : ""}</span>}
                  <span className="mt-1 block text-xs text-muted-foreground">This plan is AI-written. Check specs in your manual, and stop if anything looks different from what LensQi describes.</span>
                </span>
              </label>
            )}
            <Button size="lg" className="h-12 w-full rounded-2xl" disabled={accept.isPending || (plan.needsSafetyAck && !ack)}
              onClick={() => accept.mutate([], { onSuccess: () => nav(`/m/t/${id}/step`) })}>
              {accept.isPending ? <Loader2 className="animate-spin" /> : <ArrowRight />} Start step 1
            </Button>
          </div>
        )}
        <p className="text-center text-[11px] text-muted-foreground">Plan by {plan.modelId} · AI inference</p>
      </Page>
    </>
  );
}
