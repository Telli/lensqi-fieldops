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
  const ports = ["P1", "P2", "P3", "P4"];
  const switchConcept = fixture?.visualRequirements.find((v) => v.segmentationPrompt?.toLowerCase().includes("switch"))?.segmentationPrompt;
  const cableConcept = fixture?.visualRequirements.find((v) => v.segmentationPrompt?.toLowerCase().includes("cable"))?.segmentationPrompt;

  return (
    <div className={className}>
      {links.length > 0 && (
        <div className="panel mb-2.5 overflow-hidden p-3" data-expected-topology>
          <div className="mb-2 flex items-center justify-between gap-2 text-[11px]">
            <span className="eyebrow">Expected Port Topology · Site Record</span>
            {onSelectConcept && (switchConcept || cableConcept) && (
              <span className="text-muted-foreground">Tap part to highlight AR outline</span>
            )}
          </div>
          <div className="grid gap-2.5 sm:grid-cols-[1fr_auto_1fr] sm:items-center">
            <div
              onClick={() => onSelectConcept && switchConcept && onSelectConcept(selectedConcept === switchConcept ? null : switchConcept)}
              className={cn("rounded-lg border p-2.5 transition-colors",
                onSelectConcept && switchConcept && "cursor-pointer hover:border-primary/50",
                selectedConcept && switchConcept === selectedConcept ? "border-primary bg-primary/10" : "border-border bg-surface/60")}>
              <div className="flex items-center gap-1.5 text-xs font-medium">
                <Server className="size-3.5 text-primary" />
                <span>{name(links[0]!.fromAssetId)}</span>
              </div>
              <div className="mt-2 grid grid-cols-4 gap-1.5">
                {ports.map((p) => {
                  const activeLink = links.find((l) => l.fromPortLabel === p);
                  return (
                    <div key={p} className={cn("rounded border px-1.5 py-1 text-center font-mono text-[10px]",
                      activeLink ? "border-primary bg-primary/15 font-semibold text-primary" : "border-border/70 bg-background/50 text-muted-foreground")}>
                      <div>{p}</div>
                      <div className="mt-0.5 text-[8px] uppercase">{activeLink ? "EXPECTED" : "IDLE"}</div>
                    </div>
                  );
                })}
              </div>
            </div>

            <div
              onClick={() => onSelectConcept && cableConcept && onSelectConcept(selectedConcept === cableConcept ? null : cableConcept)}
              className={cn("flex flex-col items-center justify-center rounded-lg border px-3 py-1.5 text-center transition-colors",
                onSelectConcept && cableConcept && "cursor-pointer hover:border-primary/50",
                selectedConcept && cableConcept === selectedConcept ? "border-primary bg-primary/10" : "border-dashed border-primary/40 bg-primary/5")}>
              <span className="font-mono text-[10px] font-semibold text-primary">{links[0]!.cableLabel}</span>
              <span className="my-0.5 text-[10px] text-muted-foreground">Port {links[0]!.fromPortLabel} ───▶</span>
              <span className="text-[9px] text-muted-foreground">{links[0]!.expectedState}</span>
            </div>

            <div className="rounded-lg border border-border bg-surface/60 p-2.5">
              <div className="flex items-center gap-1.5 text-xs font-medium">
                <Cpu className="size-3.5 text-primary" />
                <span>{name(links[0]!.toAssetId)}</span>
              </div>
              <div className="mt-1.5 font-mono text-[10px] text-muted-foreground">
                Target · {links[0]!.toAssetId}
              </div>
            </div>
          </div>
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
