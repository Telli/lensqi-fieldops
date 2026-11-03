import { TaskSources } from "@/components/fo/task-sources";
import { useState } from "react";
import { Link, Redirect, useLocation } from "wouter";
import { toast } from "sonner";
import { AlertTriangle, ArrowRight, Camera, Clock, Hand, Loader2, OctagonX, RotateCcw, ShieldAlert, Wrench } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Page, TopBar } from "@/components/fo/mobile-shell";
import { AiCard } from "@/components/fo/ai";
import { cautionLabel, riskTone, tasks, useTask, useTaskAct, type Task, type TaskStep } from "@/lib/tasks";
import { cn } from "@/lib/utils";

function StepItem({ id, step, n }: { id: string; step: TaskStep; n: number }) {
  return (
    <li className="flex gap-3 p-3.5">
      <span className={cn("grid size-7 shrink-0 place-items-center rounded-full text-xs font-semibold",
        step.status === "Done" ? "bg-success/15 text-success" : step.status === "Active" ? "bg-primary text-primary-foreground" : step.status === "Skipped" ? "bg-muted text-muted-foreground line-through" : "bg-muted")}>{n}</span>
      <div className="min-w-0 flex-1 space-y-1.5">
        <div className="flex items-center gap-2 text-sm font-medium">{step.title}{step.handsOn && <Hand className="size-3.5 text-muted-foreground" aria-label="Hands-on" />}{step.check?.required && <Camera className="size-3.5 text-muted-foreground" aria-label="Photo check" />}</div>
        <p className="text-xs text-muted-foreground">{step.instruction}</p><TaskSources id={id} step={step} />
        {step.cautions.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {step.cautions.map((c, k) => <span key={k} className="rounded-full border border-warning/40 bg-warning/10 px-2 py-0.5 text-[11px] text-warning">{cautionLabel[c.kind]} · {c.text}</span>)}
          </div>
        )}
        {step.stopIf.length > 0 && <p className="text-[11px] text-failure">Stop if {step.stopIf.join("; ")}.</p>}
        {step.status === "Skipped" && step.skipReason && (
          <p className="text-[11px] text-muted-foreground">Skipped: {step.skipReason.text}{step.skipReason.partHeightCm != null && step.skipReason.reachCm != null
            ? ` (measured ${step.skipReason.partHeightCm} cm up; reach about ${step.skipReason.reachCm} cm, estimated)` : ""}</p>
        )}
      </div>
    </li>
  );
}

/** Stopping a task that is not finished, after a confirmation. The record is kept; no more steps are accepted. */
function StopTask({ t }: { t: Task }) {
  const [, nav] = useLocation();
  const [confirming, setConfirming] = useState(false);
  const stop = useTaskAct(t.taskId, () => tasks.abandon(t.taskId, "Stopped by the user"));
  if (t.phase !== "Planned" && t.phase !== "InProgress") return null;
  if (!confirming)
    return <button onClick={() => setConfirming(true)} className="mx-auto flex min-h-11 items-center gap-1.5 px-2 text-sm text-muted-foreground"><OctagonX className="size-4" />Stop this task</button>;
  return (
    <div className="space-y-2 rounded-2xl border border-border p-3.5 text-sm">
      <p>Stop this task? Its record stays in your list, but no more steps can be done.</p>
      <div className="grid grid-cols-2 gap-2">
        <Button variant="outline" className="h-11 rounded-xl" onClick={() => setConfirming(false)} disabled={stop.isPending}>Keep going</Button>
        <Button variant="destructive" className="h-11 rounded-xl" disabled={stop.isPending}
          onClick={() => stop.mutate([], { onSuccess: () => { toast.success("Task stopped"); nav("/m"); } })}>
          {stop.isPending && <Loader2 className="animate-spin" />}Stop task
        </Button>
      </div>
    </div>
  );
}

/** The AI-written plan, its risk and safety notes, before anything starts. */
export default function TaskPlanPage({ id }: { id: string }) {
  const { data: t, isLoading } = useTask(id);
  const [, nav] = useLocation();
  const replan = useTaskAct(id, () => tasks.plan(id));
  // Starting is the acknowledgement: the safety notes sit right above the button that says so.
  const accept = useTaskAct(id, () => tasks.accept(id, true));

  if (t?.mode === "Freeform") return <Redirect to={`/m/t/${id}/live`} replace />;
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
          <StopTask t={t} />
        </Page>
      </>
    );

  const started = t.phase !== "Planned";
  const allCautions = [...new Map(plan.steps.flatMap((s) => s.cautions).map((c) => [c.text, c])).values()];
  return (
    <>
      <TopBar title={t.mode === "Diy" ? "Your plan" : "Job plan"} back="/m" />
      <Page className="space-y-4">
        {t.cached && <p role="status">Saved job · {t.pendingUpdates ?? 0} updates awaiting sync</p>}
        <a download href={`/v1/tasks/${encodeURIComponent(id)}/package.zip`}>Download work package</a>
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
          {plan.steps.map((s, n) => <StepItem id={id} key={s.id} step={s} n={n + 1} />)}
        </ol>

        {t.phase === "Abandoned" ? (
          <p className="rounded-2xl border border-border p-3.5 text-sm text-muted-foreground">You stopped this task{t.abandonReason && t.abandonReason !== "Stopped by the user" ? `: ${t.abandonReason}` : ""}. Its record is kept.</p>
        ) : started ? (
          <Button asChild size="lg" className="h-12 w-full rounded-2xl">
            <Link href={t.phase === "InProgress" && !t.pendingCompletion ? `/m/t/${id}/step` : `/m/t/${id}/done`}>{t.phase === "InProgress" && !t.pendingCompletion ? "Continue" : "See the summary"} <ArrowRight /></Link>
          </Button>
        ) : (
          <div className="space-y-3">
            <div className={cn("rounded-2xl p-3.5 text-sm", plan.needsSafetyAck ? "border border-warning/40 bg-warning/5" : "border border-border")}>
              {plan.needsSafetyAck && <p className="flex items-center gap-1.5 font-medium"><AlertTriangle className="size-4 text-warning" />Safety notes</p>}
              {allCautions.length > 0 && <p className="mt-1 text-xs text-muted-foreground">{allCautions.slice(0, 4).map((c) => c.text).join(" · ")}{allCautions.length > 4 ? " · …" : ""}</p>}
              <p className="mt-1 text-xs text-muted-foreground">This plan is AI-written, not a safety certification. Check specs in your manual, and stop if anything looks different from what LensQi describes.</p>
            </div>
            <Button size="lg" className="h-12 w-full rounded-2xl" disabled={accept.isPending}
              onClick={() => accept.mutate([], { onSuccess: () => nav(`/m/t/${id}/step`) })}>
              {accept.isPending ? <Loader2 className="animate-spin" /> : <ArrowRight />} {plan.needsSafetyAck ? "I've read the safety notes · Start" : "Start step 1"}
            </Button>
          </div>
        )}
        <StopTask t={t} />
        <p className="text-center text-[11px] text-muted-foreground">Plan by {plan.modelId} · AI inference</p>
      </Page>
    </>
  );
}
