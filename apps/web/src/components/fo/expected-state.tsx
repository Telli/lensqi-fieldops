import { Cable, Server, Cpu } from "lucide-react";
import { cn } from "@/lib/utils";
import type { Fixture } from "@/lib/api";

/**
 * The site's expected installation state for one asset: which port, which cable, to which peer. It is the site's
 * record of intent, shown beside the evidence; it never proves what is plugged in today.
 */
export function ExpectedState({ fixture, assetId, selectedConcept, onSelectConcept, className }: {
  fixture?: Fixture | null; assetId: string; selectedConcept?: string | null; onSelectConcept?: (concept: string | null) => void; className?: string;
}) {
  const name = (id: string) => fixture?.assets.find((a) => a.assetId === id)?.displayName ?? id;
  const links = (fixture?.connections ?? []).filter((c) => c.fromAssetId === assetId || c.toAssetId === assetId);
  // The fixture names the outline prompt for each part; an asset matches by its kind's noun (ethernet_switch → "switch").
  const prompts = (fixture?.visualRequirements ?? []).map((v) => v.segmentationPrompt).filter((p): p is string => !!p);
  const conceptFor = (assetKind?: string) => { const noun = assetKind?.split("_").at(-1); return noun ? prompts.find((p) => p.toLowerCase().includes(noun)) : undefined; };
  const cableConcept = prompts.find((p) => p.toLowerCase().includes("cable"));
  const kind = (id: string) => fixture?.assets.find((a) => a.assetId === id)?.kind;
  const part = (key: string, concept: string | undefined, children: React.ReactNode, extra?: string) => {
    const on = !!concept && selectedConcept === concept;
    const cls = cn("rounded-lg border p-2.5 text-left transition-colors", on ? "border-primary bg-primary/10" : "border-border bg-surface/60", extra);
    return concept && onSelectConcept
      ? <button key={key} type="button" aria-pressed={on} onClick={() => onSelectConcept(on ? null : concept)} className={cn(cls, "hover:border-primary/50 focus-visible:outline-2 focus-visible:outline-primary")}>{children}</button>
      : <div key={key} className={cls}>{children}</div>;
  };

  return (
    <div className={className}>
      {links.length > 0 && (
        <div className="panel mb-2.5 space-y-2.5 p-3" data-expected-topology>
          <div className="flex items-center justify-between gap-2 text-[11px]">
            <span className="eyebrow">Expected connections · site record</span>
            {onSelectConcept && prompts.length > 0 && <span className="text-muted-foreground">Tap a part to find its outline</span>}
          </div>
          {links.map((c) => (
            <div key={c.connectionId} className="grid items-center gap-2 sm:grid-cols-[1fr_auto_1fr]">
              {part("from", conceptFor(kind(c.fromAssetId)), <>
                <div className="flex items-center gap-1.5 text-xs font-medium"><Server className="size-3.5 text-primary" />{name(c.fromAssetId)}</div>
                <div className="mt-1.5 inline-block rounded border border-primary bg-primary/15 px-1.5 py-0.5 font-mono text-[10px] font-semibold text-primary">Port {c.fromPortLabel}</div>
              </>)}
              {part("cable", cableConcept, <>
                <div className="font-mono text-[10px] font-semibold text-primary">{c.cableLabel}</div>
                <div className="text-[10px] text-muted-foreground">───▶ <span className="capitalize">{c.expectedState}</span></div>
              </>, "border-dashed text-center")}
              {part("to", conceptFor(kind(c.toAssetId)), <>
                <div className="flex items-center gap-1.5 text-xs font-medium"><Cpu className="size-3.5 text-primary" />{name(c.toAssetId)}</div>
                <div className="mt-1 font-mono text-[10px] text-muted-foreground">{c.toAssetId}</div>
              </>)}
            </div>
          ))}
        </div>
      )}
      <div className="panel divide-y divide-border">
        {links.length === 0 && <div className="px-4 py-3 text-sm text-muted-foreground">The site record lists no connections for this asset.</div>}
        {links.map((c) => {
          const outgoing = c.fromAssetId === assetId;
          return (
            <div key={c.connectionId} className="flex items-center gap-3 px-4 py-3 text-sm">
              <Cable className="size-4 shrink-0 text-primary" />
              <div className="min-w-0 flex-1">
                <div className="font-medium">{outgoing ? `Port ${c.fromPortLabel}` : `From ${name(c.fromAssetId)} port ${c.fromPortLabel}`} → {outgoing ? name(c.toAssetId) : "this asset"}</div>
                <div className="text-xs text-muted-foreground">Cable label {c.cableLabel} · {c.connectionId}</div>
              </div>
              <span className="rounded-full border border-border px-2 py-0.5 text-[11px] capitalize">{c.expectedState}</span>
            </div>
          );
        })}
      </div>
      <p className="mt-1.5 text-[11px] text-muted-foreground">From the site record (fixture revision {fixture?.fixtureRevision ?? "—"}). This is how it should be installed, not proof of how it is.</p>
    </div>
  );
}
