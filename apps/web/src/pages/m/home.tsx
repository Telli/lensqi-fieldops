import { Link } from "wouter";
import { ScanLine, ChevronRight, Camera, User, ShieldCheck, ShieldX, Wrench, Search } from "lucide-react";
import { EvidenceBadge } from "@/components/fo/primitives";
import { ConnBadge, Page } from "@/components/fo/mobile-shell";
import { ArFrame } from "@/components/fo/ar-frame";
import { LensQiMark } from "@/components/fo/ai";
import { useFixtures, useHealth, useInspections } from "@/lib/hooks";
import { lastPhoto, useInspectionBundle } from "@/lib/inspection";
import { arTags, targetConcept, usePhotoOutlines } from "@/lib/ar";
import { aiSummary, nextMove } from "@/lib/ai";
import { fmtWhen, greeting, stageLabel, stageState } from "@/lib/labels";
import type { InspectionSummary } from "@/lib/api";
import { cn } from "@/lib/utils";

export function inspectionHref(s: Pick<InspectionSummary, "inspectionId" | "stage">) {
  const base = `/m/i/${s.inspectionId}`;
  if (s.stage === "VerifiedComplete") return `${base}/receipt`;
  if (s.stage === "ReadyToVerify" || s.stage === "VerificationFailed") return `${base}/verify`;
  if (s.stage === "Created") return `${base}/scan`;
  return base;
}

export function InspectionCard({ s, assetName }: { s: InspectionSummary; assetName?: string }) {
  return (
    <Link href={inspectionHref(s)} className="panel block p-3.5 hover:border-primary/40">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">{fmtWhen(s.updatedAt)}</span>
        <EvidenceBadge state={stageState[s.stage]} label={stageLabel[s.stage]} />
      </div>
      <div className="mt-1.5 text-sm font-medium">{s.workOrder ?? assetName ?? s.assetId}</div>
      <div className="text-xs text-muted-foreground">{assetName ?? s.assetId} · {s.photos} photo{s.photos === 1 ? "" : "s"}{s.diagnostics ? ` · ${s.diagnostics} check${s.diagnostics === 1 ? "" : "s"}` : ""}</div>
    </Link>
  );
}

/** What needs the technician, most urgent first. */
function needsYou(all: InspectionSummary[]) {
  const rank = (s: InspectionSummary) =>
    s.openEvidenceRequest ? 0 : s.stage === "VerificationFailed" ? 1 : s.stage === "ReadyToVerify" ? 2 : s.stage === "AwaitingRepair" || s.stage === "FaultConfirmed" ? 3 : s.stage === "NeedsEvidence" && s.photos > 0 ? 4 : 9;
  return all.filter((s) => rank(s) < 9).sort((a, b) => rank(a) - rank(b) || +new Date(b.updatedAt) - +new Date(a.updatedAt));
}

const reasonOf = (s: InspectionSummary) =>
  s.openEvidenceRequest ? { text: s.openEvidenceRequest.note, Icon: Camera, tone: "text-warning bg-warning/12" }
  : s.stage === "VerificationFailed" ? { text: "Failed. Check it again.", Icon: ShieldX, tone: "text-failure bg-failure/12" }
  : s.stage === "ReadyToVerify" ? { text: "Repair claimed. Verify it.", Icon: ShieldCheck, tone: "text-primary bg-primary/12" }
  : s.stage === "AwaitingRepair" || s.stage === "FaultConfirmed" ? { text: s.workOrder ?? "Fault confirmed. Plan the fix.", Icon: Wrench, tone: "text-warning bg-warning/12" }
  : { text: "LensQi needs a better view.", Icon: Search, tone: "text-warning bg-warning/12" };

/** The photo LensQi is working on, with its outlines, and the next move in one line. */
function FocusHero({ s, assetName }: { s: InspectionSummary; assetName?: string }) {
  const { i, fixture, assessment, photoUrl } = useInspectionBundle(s.inspectionId);
  const photo = lastPhoto(i);
  const outlines = usePhotoOutlines(s.inspectionId, i, photo?.evidenceId);
  const shown = i?.currentClaimId ? undefined : assessment;
  const move = nextMove(i, assessment);
  const src = photo && photoUrl(photo.evidenceId);
  return (
    <Link href={inspectionHref(s)} className="group relative block overflow-hidden rounded-[28px] bg-overlay text-overlay-foreground shadow-xl shadow-black/20">
      {src ? <ArFrame src={src} outlines={outlines} tags={arTags(fixture, shown, outlines)} guide={(() => { const t = targetConcept(fixture, shown); return t ? { concept: t, label: "Target" } : null; })()} />
        : <div className="grid aspect-[4/3] place-items-center"><ScanLine className="size-10 opacity-40" /></div>}
      <div className="pointer-events-none absolute inset-x-0 top-0 flex items-center justify-between p-3">
        <span className="glass rounded-full px-2.5 py-1 text-[11px] font-medium">{assetName ?? s.assetId}</span>
        <span className="glass rounded-full px-2.5 py-1 text-[11px]">{stageLabel[s.stage]}</span>
      </div>
      <div className="relative -mt-16 bg-gradient-to-t from-overlay via-overlay/95 to-transparent px-4 pb-4 pt-10">
        <div className="flex items-start gap-3">
          <LensQiMark />
          <div className="min-w-0 flex-1">
            <p className="line-clamp-2 text-[12px] opacity-75">{aiSummary(i, shown)}</p>
            <p className="mt-0.5 text-[17px] font-semibold leading-snug">{move?.title ?? "Continue"}</p>
          </div>
          <ChevronRight className="mt-2 size-5 opacity-60 transition-transform group-hover:translate-x-0.5" />
        </div>
      </div>
    </Link>
  );
}

/** Nothing in flight: the camera is the whole story. */
function ScanHero() {
  return (
    <Link href="/m/start" className="relative grid aspect-[4/3] place-items-center overflow-hidden rounded-[28px] bg-overlay text-overlay-foreground shadow-xl shadow-black/20">
      <div className="pointer-events-none absolute inset-x-0 top-0 h-1/4 animate-scan bg-gradient-to-b from-transparent via-primary/25 to-transparent" />
      <div className="relative size-24">
        {["left-0 top-0 border-l-2 border-t-2", "right-0 top-0 border-r-2 border-t-2", "left-0 bottom-0 border-l-2 border-b-2", "right-0 bottom-0 border-r-2 border-b-2"].map((c) => (
          <span key={c} className={cn("absolute size-6 rounded-[4px] border-overlay-foreground/80", c)} />
        ))}
        <span className="absolute left-1/2 top-1/2 size-1.5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-primary" />
      </div>
      <div className="absolute inset-x-0 bottom-0 flex items-center gap-3 p-4">
        <LensQiMark />
        <div><p className="text-[12px] opacity-75">Point at the equipment.</p><p className="text-[17px] font-semibold">I'll identify it and outline what matters.</p></div>
      </div>
    </Link>
  );
}

export default function Home() {
  const health = useHealth();
  const list = useInspections();
  const fixtures = useFixtures();
  const all = list.data ?? [];
  const nameOf = (id: string) => fixtures.data?.flatMap((f) => f.assets).find((a) => a.assetId === id)?.displayName;
  const queue = needsYou(all);
  const focus = queue.find((s) => s.photos > 0);
  const rest = queue.filter((s) => s !== focus).slice(0, 3);

  return (
    <Page className="space-y-6 pt-5">
      <header className="flex items-center gap-3">
        <div className="flex-1">
          <p className="text-xs text-muted-foreground">{greeting()}</p>
          <h1 className="text-[22px] font-semibold tracking-tight">{focus ? "Pick up where you left off" : "Ready when you are"}</h1>
        </div>
        <ConnBadge reachable={!health.isError} />
        <Link href="/m/profile" aria-label="Profile" className="grid size-9 place-items-center rounded-full border border-border bg-surface"><User className="size-4" /></Link>
      </header>

      {focus ? <FocusHero s={focus} assetName={nameOf(focus.assetId)} /> : <ScanHero />}

      {rest.length > 0 && (
        <section className="space-y-1">
          <h2 className="px-1 text-[11px] font-medium uppercase tracking-[0.14em] text-muted-foreground">Up next</h2>
          <div className="divide-y divide-border/70 overflow-hidden rounded-3xl border border-border bg-surface">
            {rest.map((s) => {
              const r = reasonOf(s);
              return (
                <Link key={s.inspectionId} href={inspectionHref(s)} className="flex items-center gap-3 p-3.5 hover:bg-muted/50">
                  <span className={cn("grid size-10 shrink-0 place-items-center rounded-2xl", r.tone)}><r.Icon className="size-[18px]" /></span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">{r.text}</span>
                    <span className="block text-xs text-muted-foreground">{nameOf(s.assetId) ?? s.assetId} · {fmtWhen(s.updatedAt)}</span>
                  </span>
                  <ChevronRight className="size-4 text-muted-foreground" />
                </Link>
              );
            })}
          </div>
        </section>
      )}

      <Link href="/m/inspections" className="flex items-center justify-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        {queue.length > 1 + rest.length ? `All inspections · ${queue.length} waiting` : "All inspections"} <ChevronRight className="size-4" />
      </Link>
    </Page>
  );
}
