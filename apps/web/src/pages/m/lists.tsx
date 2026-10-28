import { useState } from "react";
import { Link } from "wouter";
import { Boxes, ClipboardList, Server, ShieldCheck, Smartphone, LayoutDashboard, Search, ChevronRight } from "lucide-react";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { Empty, Section, SourceBadge } from "@/components/fo/primitives";
import { fmtWhen, stageLabel } from "@/lib/labels";
import { useFixtures, useHealth, useInspections } from "@/lib/hooks";
import { ThemeControl } from "@/components/fo/theme-control";
import { InspectionCard } from "./home";

export function Inspections() {
  const list = useInspections();
  const fixtures = useFixtures();
  const nameOf = (id: string) => fixtures.data?.flatMap((f) => f.assets).find((a) => a.assetId === id)?.displayName;
  const all = list.data ?? [];
  const open = all.filter((s) => s.stage !== "VerifiedComplete" && s.stage !== "Inconclusive");
  const done = all.filter((s) => !open.includes(s));
  return (
    <>
      <TopBar title="Inspections" />
      <Page>
        {all.length === 0 && !list.isLoading && <Empty icon={ClipboardList} title="No inspections yet" action={<Link href="/m/start" className="text-sm text-primary">Start one</Link>} />}
        {open.length > 0 && <Section title={`Open · ${open.length}`}>{open.map((s) => <InspectionCard key={s.inspectionId} s={s} assetName={nameOf(s.assetId)} />)}</Section>}
        {done.length > 0 && <Section title={`Closed · ${done.length}`}>{done.slice(0, 30).map((s) => <InspectionCard key={s.inspectionId} s={s} assetName={nameOf(s.assetId)} />)}</Section>}
      </Page>
    </>
  );
}

export function Assets() {
  const fixtures = useFixtures();
  const list = useInspections();
  const [q, setQ] = useState("");
  const match = (a: { assetId: string; displayName: string; kind: string }) => !q.trim() || `${a.assetId} ${a.displayName} ${a.kind}`.toLowerCase().includes(q.trim().toLowerCase());
  return (
    <>
      <TopBar title="Assets" />
      <Page className="space-y-4">
        <label className="flex h-12 items-center gap-2.5 rounded-2xl border border-border bg-surface px-4 focus-within:border-primary"><Search className="size-4 text-muted-foreground" />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search by name or asset label" className="w-full bg-transparent text-sm outline-none" /></label>
        {fixtures.data?.map((f) => (
          <Section key={f.fixtureId} title={f.fixtureId}>
            <div className="panel divide-y divide-border overflow-hidden">
              {f.assets.filter(match).map((a) => {
                const mine = list.data?.filter((s) => s.assetId === a.assetId) ?? [];
                const latest = mine[0];
                const h = !latest ? "unknown" : latest.stage === "VerifiedComplete" ? "healthy" : ["FaultConfirmed", "VerificationFailed", "AwaitingRepair"].includes(latest.stage) ? "fault" : "attention";
                const dot = { healthy: "bg-success", attention: "bg-warning", fault: "bg-failure", unknown: "bg-muted-foreground" }[h];
                return (
                  <Link key={a.assetId} href={`/m/assets/${encodeURIComponent(a.assetId)}`} className="flex items-center gap-3 px-3.5 py-3 hover:bg-muted/40">
                    <span className="relative grid size-11 place-items-center rounded-2xl bg-accent text-accent-foreground">
                      <Boxes className="size-5" />
                      <span className={`absolute -right-0.5 -top-0.5 size-2.5 rounded-full ring-2 ring-card ${dot}`} />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium">{a.displayName}</span>
                      <span className="block truncate text-xs text-muted-foreground">{latest ? `${stageLabel[latest.stage]} · ${fmtWhen(latest.updatedAt)}` : "Not inspected yet"}</span>
                    </span>
                    <ChevronRight className="size-4 text-muted-foreground" />
                  </Link>
                );
              })}
            </div>
          </Section>
        ))}
      </Page>
    </>
  );
}

export function Profile() {
  const health = useHealth();
  return (
    <>
      <TopBar title="Profile" />
      <Page>
        <div className="panel divide-y divide-border">
          <Row icon={Server} k="Inspection server" v={health.isError ? "Unreachable" : health.data ? "Connected" : "…"} />
          <Row icon={ShieldCheck} k="Equipment checks" v={health.data ? <SourceBadge source={health.data.diagnosticsMode} /> : "…"} />
          <Row icon={Boxes} k="Sites and knowledge" v={health.data ? `${health.data.fixtures} site · ${health.data.packs} packs` : "…"} />
        </div>
        <Section title="Appearance"><ThemeControl className="w-full [&>button]:flex-1 [&>button]:justify-center" /></Section>
        <p className="text-xs text-muted-foreground">
          Simulated checks are labelled everywhere and can never produce a live verification. AI observations are suggestions; a repair is verified only when a new photo, your review and a fresh equipment check all agree.
        </p>
        <div className="grid gap-2">
          <Link href="/ops" className="panel flex items-center gap-3 p-3.5 text-sm hover:border-primary/40"><LayoutDashboard className="size-4 text-primary" /> Operations dashboard</Link>
          <div className="panel flex items-center gap-3 p-3.5 text-sm text-muted-foreground"><Smartphone className="size-4" /> Android app: same server, works offline</div>
        </div>
      </Page>
    </>
  );
}

function Row({ icon: Icon, k, v }: { icon: typeof Server; k: string; v: React.ReactNode }) {
  return <div className="flex items-center gap-3 px-3.5 py-3 text-sm"><Icon className="size-4 text-muted-foreground" /><span className="flex-1 text-muted-foreground">{k}</span><span>{v}</span></div>;
}
