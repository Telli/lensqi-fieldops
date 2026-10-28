import { Cable } from "lucide-react";
import type { Fixture } from "@/lib/api";

/**
 * The site's expected installation state for one asset: which port, which cable, to which peer. It is the site's
 * record of intent, shown beside the evidence; it never proves what is plugged in today.
 */
export function ExpectedState({ fixture, assetId, className }: { fixture?: Fixture | null; assetId: string; className?: string }) {
  const name = (id: string) => fixture?.assets.find((a) => a.assetId === id)?.displayName ?? id;
  const links = (fixture?.connections ?? []).filter((c) => c.fromAssetId === assetId || c.toAssetId === assetId);
  return (
    <div className={className}>
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
