import { useState, type ReactNode } from "react";
import { CheckCircle2, CircleDashed, HelpCircle, ShieldCheck, UserCheck, XCircle, ChevronDown, FileText, ExternalLink, Radio } from "lucide-react";
import { cn } from "@/lib/utils";
import type { EvidenceState } from "@/lib/labels";
import { sourceLabel } from "@/lib/labels";
import type { DiagnosticSource } from "@/lib/api";

export function Logo({ size = 28, className }: { size?: number; className?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" className={cn("text-primary", className)} aria-label="LensQi">
      <rect x="1.5" y="1.5" width="29" height="29" rx="8" fill="none" stroke="currentColor" strokeOpacity=".35" />
      <path d="M8 5.5H5.5V8M24 5.5h2.5V8M8 26.5H5.5V24M24 26.5h2.5V24" stroke="currentColor" strokeWidth="1.6" fill="none" strokeLinecap="round" />
      <circle cx="16" cy="16" r="6.5" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <circle cx="16" cy="16" r="2.2" fill="currentColor" />
      <path d="M20.6 20.6 24 24" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

const stateMap: Record<EvidenceState, { label: string; cls: string; Icon: typeof CheckCircle2 }> = {
  unknown: { label: "Not yet observed", cls: "text-muted-foreground bg-muted border-border", Icon: CircleDashed },
  hypothesis: { label: "AI hypothesis", cls: "text-primary bg-primary/10 border-primary/30", Icon: HelpCircle },
  "evidence-required": { label: "Evidence required", cls: "text-warning bg-warning/10 border-warning/30", Icon: HelpCircle },
  "human-confirmed": { label: "Human-confirmed", cls: "text-foreground bg-secondary border-border", Icon: UserCheck },
  verified: { label: "Independently verified", cls: "text-success bg-success/10 border-success/30", Icon: ShieldCheck },
  failed: { label: "Verification failed", cls: "text-failure bg-failure/10 border-failure/30", Icon: XCircle },
};

export function EvidenceBadge({ state, label }: { state: EvidenceState; label?: string | undefined }) {
  const m = stateMap[state];
  return (
    <span className={cn("inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-[11px] font-medium whitespace-nowrap", m.cls)}>
      <m.Icon className="size-3" />
      {label ?? m.label}
    </span>
  );
}

/** Where a measurement came from. A simulated or replayed result is never shown without this. */
export function SourceBadge({ source }: { source: DiagnosticSource }) {
  if (source === "LiveLocal")
    return <span className="inline-flex items-center gap-1 rounded border border-success/40 px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-wider text-success"><Radio className="size-3" />Live</span>;
  return <SimBadge>{sourceLabel[source]}</SimBadge>;
}

export function SimBadge({ children = "Simulated" }: { children?: ReactNode }) {
  return (
    <span className="inline-flex items-center rounded border border-dashed border-border px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
      {children}
    </span>
  );
}

export function Kpi({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: string; tone?: "success" | "warning" | "failure" | "primary" }) {
  const toneCls = { success: "text-success", warning: "text-warning", failure: "text-failure", primary: "text-primary" };
  return (
    <div className="panel p-3.5">
      <div className="eyebrow">{label}</div>
      <div className={cn("mt-1.5 text-2xl font-semibold tabular-nums tracking-tight", tone && toneCls[tone])}>{value}</div>
      {hint && <div className="mt-0.5 text-xs text-muted-foreground">{hint}</div>}
    </div>
  );
}

export function Section({ title, action, children, className }: { title: string; action?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cn("space-y-2.5", className)}>
      <div className="flex items-center justify-between">
        <h2 className="eyebrow">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

export function Expandable({ title, children, defaultOpen = false, mono }: { title: ReactNode; children: ReactNode; defaultOpen?: boolean; mono?: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="panel overflow-hidden">
      <button onClick={() => setOpen(!open)} className="flex w-full items-center justify-between px-3.5 py-3 text-left text-sm font-medium" aria-expanded={open}>
        {title}
        <ChevronDown className={cn("size-4 text-muted-foreground transition-transform", open && "rotate-180")} />
      </button>
      {open && <div className={cn("animate-rise border-t border-border px-3.5 py-3 text-sm text-muted-foreground", mono && "font-mono text-xs")}>{children}</div>}
    </div>
  );
}

/** Key/value rows for identifiers and digests, kept out of the technician's way until asked for. */
export function TechDetails({ rows, title = "Technical details" }: { rows: [string, ReactNode][]; title?: string }) {
  return (
    <Expandable title={<span className="text-muted-foreground">{title}</span>}>
      <dl className="space-y-1.5">
        {rows.map(([k, v]) => (
          <div key={k} className="flex justify-between gap-3 text-xs">
            <dt className="shrink-0 text-muted-foreground">{k}</dt>
            <dd className="min-w-0 break-all text-right font-mono text-foreground/80">{v}</dd>
          </div>
        ))}
      </dl>
    </Expandable>
  );
}

export function Citation({ title, section, href }: { title: string; section: string; href?: string | null }) {
  const inner = (
    <>
      <FileText className="size-3.5 shrink-0 text-primary" />
      <span className="min-w-0 flex-1 truncate"><span className="text-foreground">{title}</span> <span className="text-muted-foreground">· {section}</span></span>
      {href && <ExternalLink className="size-3 text-muted-foreground" />}
    </>
  );
  const cls = "flex w-full items-center gap-2 rounded-lg border border-border bg-surface px-2.5 py-2 text-left text-xs hover:border-primary/40";
  return href ? <a href={href} target="_blank" rel="noopener noreferrer" className={cls}>{inner}</a> : <div className={cls}>{inner}</div>;
}

export function CheckRow({ label, detail, pass }: { label: string; detail: ReactNode; pass: boolean | null | "pending" }) {
  const waiting = pass === "pending";
  return (
    <div className={cn("flex gap-3 rounded-xl border p-3.5", waiting || pass === null ? "border-border" : pass ? "border-success/30 bg-success/5" : "border-failure/40 bg-failure/10")}>
      {waiting ? <CircleDashed className="mt-0.5 size-5 shrink-0 animate-spin text-primary" />
        : pass === null ? <CircleDashed className="mt-0.5 size-5 shrink-0 text-muted-foreground" />
        : pass ? <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-success" /> : <XCircle className="mt-0.5 size-5 shrink-0 text-failure" />}
      <div className="min-w-0">
        <div className="text-sm font-medium">{label}</div>
        <div className="mt-0.5 text-xs text-muted-foreground">{detail}</div>
      </div>
    </div>
  );
}

export function HealthDot({ h }: { h: "healthy" | "attention" | "fault" | "unknown" }) {
  const c = { healthy: "bg-success", attention: "bg-warning", fault: "bg-failure", unknown: "bg-muted-foreground" }[h];
  return <span className="inline-flex items-center gap-1.5 text-xs capitalize"><span className={cn("size-1.5 rounded-full", c)} />{h}</span>;
}

export function Empty({ icon: Icon, title, body, action }: { icon: typeof FileText; title: string; body?: string; action?: ReactNode }) {
  return (
    <div className="panel flex flex-col items-center gap-2 px-6 py-10 text-center">
      <Icon className="size-8 text-muted-foreground" />
      <div className="text-sm font-medium">{title}</div>
      {body && <p className="text-xs text-muted-foreground">{body}</p>}
      {action}
    </div>
  );
}
