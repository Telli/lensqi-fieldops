import { useState } from "react";
import { Link } from "wouter";
import { OpsPage } from "@/components/fo/ops-shell";
import { fmtWhen } from "@/lib/labels";
import { useTasks, type TaskMode } from "@/lib/tasks";
import { cn } from "@/lib/utils";

const filters: { label: string; mode?: TaskMode }[] = [{ label: "All" }, { label: "Technician", mode: "Technician" }, { label: "DIY", mode: "Diy" }, { label: "Free-form", mode: "Freeform" }];
const modeLabel: Record<TaskMode, string> = { Diy: "DIY", Technician: "Technician", Freeform: "Free-form" };
const phaseLabel = { Planned: "Plan ready", InProgress: "In progress", Completed: "Done", Abandoned: "Stopped" } as const;

/** Guided tasks across both modes; technician jobs on a site record link to their FieldProof inspection. */
export function TasksList() {
  const [f, setF] = useState(0);
  const list = useTasks(filters[f]!.mode);
  const rows = list.data ?? [];
  return (
    <OpsPage title="Guided tasks" sub={`${rows.length} shown`}>
      <div className="flex gap-1.5">
        {filters.map((x, k) => (
          <button key={x.label} onClick={() => setF(k)} className={cn("rounded-full border px-3 py-1 text-xs", k === f ? "border-primary/50 bg-primary/10 text-primary" : "border-border text-muted-foreground")}>{x.label}</button>
        ))}
      </div>
      <div className="panel overflow-x-auto">
        <table className="w-full text-sm">
          <thead><tr className="border-b border-border text-left text-xs text-muted-foreground">
            {["Task", "Mode", "Progress", "Current step", "Site asset", "FieldProof", "Updated"].map((h) => <th key={h} className="px-4 py-2.5 font-medium">{h}</th>)}
          </tr></thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.taskId} className="border-b border-border/60 last:border-0 hover:bg-muted/40">
                <td className="max-w-[280px] truncate px-4 py-2.5"><Link href={t.mode === "Freeform" ? `/m/t/${t.taskId}/live` : `/m/t/${t.taskId}`} className="font-medium hover:text-primary">{t.title ?? t.goal}</Link></td>
                <td className="px-4 py-2.5 text-xs">{modeLabel[t.mode]}</td>
                <td className="px-4 py-2.5 text-xs">{t.phase === "Planned" && !t.title ? "No plan yet" : phaseLabel[t.phase]}{t.steps && t.phase !== "Planned" ? ` · ${t.finished}/${t.steps}` : ""}</td>
                <td className="max-w-[220px] truncate px-4 py-2.5 text-xs text-muted-foreground">{t.currentStepTitle ?? "—"}</td>
                <td className="px-4 py-2.5 text-xs">{t.site?.assetId ?? "—"}</td>
                <td className="px-4 py-2.5 text-xs">{t.inspectionId ? <Link href={`/ops/inspections/${t.inspectionId}`} className="text-primary">{t.inspectionId}</Link> : "—"}</td>
                <td className="px-4 py-2.5 text-xs text-muted-foreground">{fmtWhen(t.updatedAt)}</td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-xs text-muted-foreground">{list.isLoading ? "Loading…" : "No tasks yet."}</td></tr>}
          </tbody>
        </table>
      </div>
    </OpsPage>
  );
}
