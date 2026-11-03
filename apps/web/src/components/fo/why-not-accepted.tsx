import type { ChangeAnalysis } from "@/lib/api";
import { changeLabel } from "@/lib/labels";
import { EvidenceBadge, Expandable } from "@/components/fo/primitives";

const whatToDo: Partial<Record<ChangeAnalysis["verdict"], string>> = {
  DuplicateImage: "The server found this to be the same image as a photo already in the record. Take a fresh photo of the equipment as it is now.",
  NoVisualChange: "At the part the repair concerned, the server saw no change from the earlier view. If the repair is done, frame that part so the change is visible; if not, finish it first.",
};

/**
 * The server's reason a repair photo was not accepted, shown beside the photo it was compared with, so the technician sees
 * what the comparison saw. The numbers are the server's own measurements; nothing here re-decides.
 */
export function WhyNotAccepted({ change, yours, reference }: { change: ChangeAnalysis; yours?: string; reference?: string }) {
  const metrics: [string, string][] = [];
  if (change.perceptualHashDistance != null) metrics.push(["Image difference", `${change.perceptualHashDistance} (0 is identical)`]);
  if (change.targetRegionIoU != null) metrics.push(["Part outline overlap with before", `${Math.round(change.targetRegionIoU * 100)}%`]);
  if (change.componentShiftNormalized != null) metrics.push(["Part moved by", `${(change.componentShiftNormalized * 100).toFixed(1)}% of the frame`]);
  return (
    <Expandable title="Why wasn't it accepted?">
      <div className="space-y-2.5 text-xs" data-why-not-accepted>
        <div className="flex items-center gap-2"><EvidenceBadge state={changeLabel[change.verdict].state} label={changeLabel[change.verdict].label} /><span className="text-muted-foreground">compared by the server · {change.thresholdsVersion}</span></div>
        {(reference || yours) && (
          <div className="grid grid-cols-2 gap-2">
            <figure className="space-y-1">
              <div className="aspect-[4/3] overflow-hidden rounded-lg bg-black/40">{reference ? <img src={reference} alt="The earlier view the photo was compared with" className="size-full object-contain" /> : <p className="grid size-full place-items-center p-2 text-center text-[11px] text-muted-foreground">No earlier view to show</p>}</div>
              <figcaption className="text-[11px] text-muted-foreground">Earlier view{change.targetPrompt ? ` · ${change.targetPrompt}` : ""}</figcaption>
            </figure>
            <figure className="space-y-1">
              <div className="aspect-[4/3] overflow-hidden rounded-lg bg-black/40">{yours ? <img src={yours} alt="The photo you took" className="size-full object-contain" /> : null}</div>
              <figcaption className="text-[11px] text-muted-foreground">Your photo</figcaption>
            </figure>
          </div>
        )}
        <p>{change.reason}</p>
        {whatToDo[change.verdict] && <p className="text-muted-foreground">{whatToDo[change.verdict]}</p>}
        {metrics.length > 0 && (
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 font-mono text-[11px] text-muted-foreground">
            {metrics.map(([k, v]) => <div key={k} className="contents"><dt>{k}</dt><dd className="text-right text-foreground">{v}</dd></div>)}
          </dl>
        )}
      </div>
    </Expandable>
  );
}
