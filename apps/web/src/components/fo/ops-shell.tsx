import type { ReactNode } from "react";
import { Link, useLocation } from "wouter";
import { LayoutGrid, ClipboardList, Boxes, KanbanSquare, BookOpen, FileCheck2, Smartphone, ListChecks } from "lucide-react";
import { Logo, SourceBadge } from "@/components/fo/primitives";
import { useHealth } from "@/lib/hooks";
import { cn } from "@/lib/utils";
import { ThemeControl } from "@/components/fo/theme-control";

const nav = [
  { to: "/ops", label: "Overview", Icon: LayoutGrid },
  { to: "/ops/tasks", label: "Guided tasks", Icon: ListChecks },
  { to: "/ops/inspections", label: "Inspections", Icon: ClipboardList },
  { to: "/ops/assets", label: "Assets", Icon: Boxes },
  { to: "/ops/work-orders", label: "Work Orders", Icon: KanbanSquare },
  { to: "/ops/knowledge", label: "Knowledge", Icon: BookOpen },
  { to: "/ops/reports", label: "FieldProof Reports", Icon: FileCheck2 },
] as const;

export function OpsShell({ children }: { children: ReactNode }) {
  const [path] = useLocation();
  const health = useHealth();
  const isActive = (to: string) => (to === "/ops" ? path === "/ops" || path === "/ops/" : path.startsWith(to));
  return (
    <div className="flex min-h-dvh">
      <aside className="sticky top-0 hidden h-dvh w-60 shrink-0 flex-col border-r border-sidebar-border bg-sidebar p-3 md:flex">
        <Link href="/ops" className="flex items-center gap-2 px-2 py-2"><Logo size={24} /><span className="font-semibold">LensQi</span><span className="text-muted-foreground">FieldOps</span></Link>
        <nav className="mt-4 space-y-0.5">
          {nav.map(({ to, label, Icon }) => (
            <Link key={to} href={to} className={cn("flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm text-muted-foreground hover:bg-sidebar-accent hover:text-foreground", isActive(to) && "bg-sidebar-accent text-foreground")}>
              <Icon className={cn("size-4", isActive(to) && "text-primary")} />{label}
            </Link>
          ))}
        </nav>
        <div className="mt-auto space-y-2">
          <ThemeControl compact className="w-full [&>button]:flex-1 [&>button]:justify-center" />
          <Link href="/m" className="flex items-center gap-2 rounded-lg border border-border px-2.5 py-2 text-xs hover:border-primary/40"><Smartphone className="size-4 text-primary" /> Technician app</Link>
          <div className="flex items-center justify-between px-1 text-[11px] text-muted-foreground">
            <span>Equipment checks</span>{health.data && <SourceBadge source={health.data.diagnosticsMode} />}
          </div>
        </div>
      </aside>
      <div className="min-w-0 flex-1">
        <div className="flex gap-1 overflow-x-auto border-b border-border p-2 md:hidden">
          {nav.map(({ to, label }) => <Link key={to} href={to} className={cn("whitespace-nowrap rounded-md px-2.5 py-1.5 text-xs text-muted-foreground", isActive(to) && "bg-muted text-foreground")}>{label}</Link>)}
        </div>
        {children}
      </div>
    </div>
  );
}

export function OpsPage({ title, sub, actions, children }: { title: string; sub?: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <div className="animate-rise mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
          {sub && <p className="mt-1 text-sm text-muted-foreground">{sub}</p>}
        </div>
        {actions && <div className="flex gap-2">{actions}</div>}
      </div>
      {children}
    </div>
  );
}
