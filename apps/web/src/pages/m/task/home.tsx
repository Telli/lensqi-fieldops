import { useState } from "react";
import { Link, useLocation } from "wouter";
import { ArrowRight, Camera, ChevronRight, Loader2, MessageCircle, Mic, Square, User, Wrench, Sparkles } from "lucide-react";
import { toast } from "sonner";
import { useQueryClient } from "@tanstack/react-query";
import { friendlyError } from "@/lib/hooks";
import { ConnBadge, Page } from "@/components/fo/mobile-shell";
import { LensQiMark } from "@/components/fo/ai";
import { useHealth } from "@/lib/hooks";
import { fmtWhen, greeting } from "@/lib/labels";
import { taskKeys, tasks, useTasks, type TaskSummary } from "@/lib/tasks";
import { usePushToTalk } from "@/lib/voice";
import { cn } from "@/lib/utils";

export function taskHref(t: Pick<TaskSummary, "taskId" | "phase" | "mode">) {
  if (t.mode === "Freeform") return `/m/t/${t.taskId}/live`;
  return t.phase === "InProgress" ? `/m/t/${t.taskId}/step` : t.phase === "Completed" ? `/m/t/${t.taskId}/done` : `/m/t/${t.taskId}`;
}

export function TaskRow({ t }: { t: TaskSummary }) {
  const pct = t.steps ? Math.round((100 * t.finished) / t.steps) : 0;
  return (
    <Link href={taskHref(t)} className="flex items-center gap-3 p-3.5 hover:bg-muted/50">
      <span className={cn("grid size-10 shrink-0 place-items-center rounded-2xl", t.mode === "Technician" ? "bg-warning/12 text-warning" : "bg-primary/12 text-primary")}>
        {t.mode === "Diy" ? <Sparkles className="size-[18px]" /> : t.mode === "Freeform" ? <MessageCircle className="size-[18px]" /> : <Wrench className="size-[18px]" />}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-medium">{t.mode === "Freeform" ? "Just ask" : t.title ?? t.goal}</span>
        <span className="block truncate text-xs text-muted-foreground">
          {t.mode === "Freeform" ? `Free-form help · ${t.phase === "Completed" ? "ended" : "open"}` : t.phase === "Completed" ? "Done" : t.phase === "Abandoned" ? "Stopped" : t.phase === "Planned" ? (t.title ? "Plan ready" : "No plan yet") : t.currentStepTitle ?? "In progress"}
          {t.steps > 0 && t.phase === "InProgress" ? ` · ${t.finished}/${t.steps}` : ""} · {fmtWhen(t.updatedAt)}
        </span>
        {t.phase === "InProgress" && <span className="mt-1.5 block h-1 overflow-hidden rounded-full bg-border"><span className="block h-full bg-primary" style={{ width: `${pct}%` }} /></span>}
      </span>
      <ChevronRight className="size-4 text-muted-foreground" />
    </Link>
  );
}

/** Two ways in: say what you want to do yourself, or go to technician work. */
export default function ModeHome() {
  const health = useHealth();
  const list = useTasks();
  const [, nav] = useLocation();
  const [goal, setGoal] = useState("");
  const voice = usePushToTalk((text) => setGoal(text));
  const go = () => goal.trim().length >= 3 && nav(`/m/new?mode=Diy&goal=${encodeURIComponent(goal.trim())}`);
  const recent = (list.data ?? []).filter((t) => t.phase !== "Abandoned").slice(0, 5);
  const qc = useQueryClient();
  const [opening, setOpening] = useState(false);
  // Spontaneous help: straight to the camera, no goal or plan first.
  const justAsk = async () => {
    setOpening(true);
    try { const r = await tasks.create({ mode: "Freeform", goal: "" }); qc.setQueryData(taskKeys.one(r.task.taskId), r.task); nav(`/m/t/${r.task.taskId}/live`); }
    catch (e) { toast.error(friendlyError(e)); setOpening(false); }
  };

  return (
    <Page className="space-y-6 pt-5">
      <header className="flex items-center gap-3">
        <div className="flex-1">
          <p className="text-xs text-muted-foreground">{greeting()}</p>
          <h1 className="text-[22px] font-semibold tracking-tight">What are we doing today?</h1>
        </div>
        <ConnBadge reachable={!health.isError} />
        <Link href="/m/profile" aria-label="Profile" className="grid size-9 place-items-center rounded-full border border-border bg-surface"><User className="size-4" /></Link>
      </header>

      <section className="rounded-[28px] bg-overlay p-4 text-overlay-foreground shadow-xl shadow-black/20">
        <div className="flex items-center gap-2.5">
          <LensQiMark />
          <div>
            <p className="text-[15px] font-semibold">Do it yourself</p>
            <p className="text-xs opacity-70">I'll plan it, then guide you step by step with the camera and voice.</p>
          </div>
        </div>
        <form className="mt-4 flex items-end gap-2" onSubmit={(e) => { e.preventDefault(); go(); }}>
          <textarea value={voice.listening ? voice.heard || goal : goal} onChange={(e) => setGoal(e.target.value)} rows={2} maxLength={300}
            placeholder="e.g. Change the oil in my 2025 Civic, or mount an IP camera"
            aria-label="What do you want to do?"
            className="min-h-14 flex-1 resize-none rounded-2xl border border-overlay-foreground/20 bg-overlay-foreground/5 px-3.5 py-3 text-sm outline-none placeholder:text-overlay-foreground/45 focus:border-primary" />
          {voice.supported && (
            <button type="button" onClick={voice.listening ? voice.stop : voice.start} aria-label={voice.listening ? "Stop listening" : "Say what you want to do"}
              className={cn("grid size-12 shrink-0 place-items-center rounded-2xl border border-overlay-foreground/20", voice.listening && "border-primary bg-primary text-primary-foreground")}>
              {voice.listening ? <Square className="size-4" /> : <Mic className="size-5" />}
            </button>
          )}
          <button type="submit" disabled={goal.trim().length < 3} aria-label="Plan it" className="grid size-12 shrink-0 place-items-center rounded-2xl bg-primary text-primary-foreground disabled:opacity-40">
            <ArrowRight className="size-5" />
          </button>
        </form>
        <p className="text-xs opacity-70">Your browser may send microphone audio to its speech provider. Only recognised text reaches LensQi. You can type instead.</p>
        {voice.error && <p className="mt-2 text-xs text-warning">{voice.error}</p>}
      </section>

      <button onClick={justAsk} disabled={opening} className="panel flex w-full items-center gap-3 p-4 text-left hover:border-primary/40">
        <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-primary/12 text-primary">{opening ? <Loader2 className="size-5 animate-spin" /> : <Camera className="size-5" />}</span>
        <span className="min-w-0 flex-1">
          <span className="block text-[15px] font-semibold">Just ask</span>
          <span className="block text-xs text-muted-foreground">Point the camera at anything and ask. No plan needed: an error on a screen, a part you don't recognise, a quick fix.</span>
        </span>
        <ChevronRight className="size-4 text-muted-foreground" />
      </button>

      <Link href="/m/tech" className="panel flex items-center gap-3 p-4 hover:border-primary/40">
        <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-warning/12 text-warning"><Wrench className="size-5" /></span>
        <span className="min-w-0 flex-1">
          <span className="block text-[15px] font-semibold">Technician</span>
          <span className="block text-xs text-muted-foreground">Installs and repairs on site records, equipment checks and FieldProof receipts.</span>
        </span>
        <ChevronRight className="size-4 text-muted-foreground" />
      </Link>

      {recent.length > 0 && (
        <section className="space-y-1">
          <h2 className="px-1 text-[11px] font-medium uppercase tracking-[0.14em] text-muted-foreground">Your tasks</h2>
          <div className="divide-y divide-border/70 overflow-hidden rounded-3xl border border-border bg-surface">
            {recent.map((t) => <TaskRow key={t.taskId} t={t} />)}
          </div>
        </section>
      )}
    </Page>
  );
}
