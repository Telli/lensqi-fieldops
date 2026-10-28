import { Sparkles } from "lucide-react";
import type { ReactNode } from "react";
import { progressSteps } from "@/lib/ai";
import { cn } from "@/lib/utils";

/** LensQi's mark: the AI speaking. Used only where the text is the model's (or the next step derived from it). */
export function LensQiMark({ className }: { className?: string }) {
  return (
    <span className={cn("grid size-7 shrink-0 place-items-center rounded-full bg-gradient-to-br from-primary to-primary/50 text-primary-foreground shadow-sm shadow-primary/30", className)}>
      <Sparkles className="size-3.5" />
    </span>
  );
}

type Tone = "success" | "failure" | "warning";
const toneEdge: Record<Tone, string> = { success: "border-success/40", failure: "border-failure/45", warning: "border-warning/45" };
const toneText: Record<Tone, string> = { success: "text-success", failure: "text-failure", warning: "text-warning" };

/** The one AI card per screen: what LensQi sees, what to do next, and the single action that does it. A tone marks a decided outcome. */
export function AiCard({ says, title, hint, tone, children, className }: { says?: string; title?: string; hint?: string; tone?: Tone; children?: ReactNode; className?: string }) {
  return (
    <section className={cn("rounded-3xl border border-border bg-gradient-to-b from-elevated to-surface p-4 shadow-sm", tone && toneEdge[tone], className)}>
      <div className="flex items-start gap-3">
        <LensQiMark />
        <div className="min-w-0 flex-1 space-y-1">
          {says && <p className="text-[13px] leading-relaxed text-muted-foreground">{says}</p>}
          {title && <h2 className={cn("text-lg font-semibold leading-snug tracking-tight", tone && toneText[tone])}>{title}</h2>}
          {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
        </div>
      </div>
      {children && <div className="mt-4 space-y-2">{children}</div>}
    </section>
  );
}

/** Five quiet segments instead of a stepper: where this inspection is, at a glance. */
export function Progress({ step, steps = progressSteps }: { step: number; steps?: readonly string[] }) {
  return (
    <div className="space-y-1.5">
      <div className="flex gap-1">
        {steps.map((s, k) => <span key={s} className={cn("h-1 flex-1 rounded-full", k < step ? "bg-primary" : k === step ? "bg-primary/35" : "bg-border")} />)}
      </div>
      <div className="flex gap-1 text-[10px] uppercase tracking-wider text-muted-foreground">
        {steps.map((s, k) => <span key={s} className={cn("flex-1", k < step && "text-foreground", k === step && "text-primary")}>{s}</span>)}
      </div>
    </div>
  );
}
