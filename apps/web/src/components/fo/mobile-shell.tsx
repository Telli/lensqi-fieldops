import { Link, useLocation } from "wouter";
import { Home, Plus, Wrench, ChevronLeft, Wifi, WifiOff, LayoutDashboard } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { cn } from "@/lib/utils";

// Three destinations: home (DIY and recent tasks), a new task, technician work. Profile sits behind the avatar on Home.
const tabs = [
  { to: "/m", label: "Home", Icon: Home },
  { to: "/m/new", label: "New task", Icon: Plus },
  { to: "/m/tech", label: "Technician", Icon: Wrench },
] as const;
const technicianPaths = ["/m/tech", "/m/i/", "/m/start", "/m/assets", "/m/inspections"];

export function BottomNav() {
  const [path] = useLocation();
  return (
    <nav className="sticky bottom-0 z-20 grid grid-cols-3 items-end border-t border-border/60 bg-background/80 px-2 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2 backdrop-blur">
      {tabs.map(({ to, label, Icon }) => {
        const active = to === "/m" ? path === "/m" || path === "/m/" || path.startsWith("/m/t/") : to === "/m/tech" ? technicianPaths.some((p) => path.startsWith(p)) : path.startsWith(to);
        if (to === "/m/new")
          return (
            <Link key={to} href={to} className="flex flex-col items-center gap-1" aria-label="Start a new task">
              <span className="-mt-8 grid size-16 place-items-center rounded-full bg-primary text-primary-foreground shadow-xl shadow-primary/30 ring-[6px] ring-background">
                <Icon className="size-6" />
              </span>
              <span className="text-[10px] font-medium text-primary">{label}</span>
            </Link>
          );
        return (
          <Link key={to} href={to} className={cn("flex flex-col items-center gap-1 py-1 text-muted-foreground", active && "text-foreground")}>
            <Icon className="size-5" />
            <span className="text-[10px] font-medium">{label}</span>
          </Link>
        );
      })}
    </nav>
  );
}

export function useOnline() {
  const [on, setOn] = useState(typeof navigator === "undefined" ? true : navigator.onLine);
  useEffect(() => {
    const u = () => setOn(navigator.onLine);
    addEventListener("online", u);
    addEventListener("offline", u);
    return () => { removeEventListener("online", u); removeEventListener("offline", u); };
  }, []);
  return on;
}

export function ConnBadge({ reachable }: { reachable?: boolean }) {
  const on = useOnline() && reachable !== false;
  return (
    <span className={cn("inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-medium", on ? "border-border text-muted-foreground" : "border-warning/40 text-warning")}>
      {on ? <Wifi className="size-3" /> : <WifiOff className="size-3" />}
      {on ? "Connected" : "Offline"}
    </span>
  );
}

export function TopBar({ title, back, right }: { title: string; back?: string; right?: ReactNode }) {
  return (
    <header className="sticky top-0 z-20 flex h-14 items-center gap-2 border-b border-border bg-background/85 px-3 backdrop-blur">
      {back && (
        <Link href={back} className="grid size-9 place-items-center rounded-lg hover:bg-muted" aria-label="Back">
          <ChevronLeft className="size-5" />
        </Link>
      )}
      <h1 className="flex-1 truncate text-[15px] font-semibold">{title}</h1>
      {right}
    </header>
  );
}

export function Page({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("animate-rise space-y-5 px-4 pb-12 pt-4", className)}>{children}</div>;
}

/** Phone-sized column; on a desktop it becomes a device frame with a link to the operations dashboard. */
export function MobileFrame({ children, immersive }: { children: ReactNode; immersive?: boolean }) {
  return (
    <div className="min-h-dvh bg-surface md:flex md:items-center md:justify-center md:py-8">
      <div className="relative mx-auto flex min-h-dvh w-full max-w-[430px] flex-col bg-background md:h-[852px] md:min-h-[852px] md:overflow-hidden md:rounded-[2.5rem] md:border md:border-border md:shadow-2xl">
        <main className="flex-1 overflow-y-auto">{children}</main>
        {!immersive && <BottomNav />}
      </div>
      <Link href="/ops" className="fixed bottom-24 right-4 hidden items-center gap-2 rounded-full border border-border bg-card px-3 py-2 text-xs font-medium hover:border-primary/40 md:flex">
        <LayoutDashboard className="size-4 text-primary" /> Operations dashboard
      </Link>
    </div>
  );
}
