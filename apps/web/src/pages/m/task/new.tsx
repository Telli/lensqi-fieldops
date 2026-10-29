import { useState } from "react";
import { useLocation, useSearch } from "wouter";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { BookOpen, Loader2, Mic, Sparkles, Square, Wrench, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Page, TopBar } from "@/components/fo/mobile-shell";
import { LensQiMark } from "@/components/fo/ai";
import { friendlyError, useFixtures } from "@/lib/hooks";
import { taskKeys, tasks, type TaskMode } from "@/lib/tasks";
import { usePushToTalk } from "@/lib/voice";
import { cn } from "@/lib/utils";

/** State the goal; LensQi writes the plan. Manual photos and, for technicians, a site asset ground it. */
export default function NewTask() {
  const params = new URLSearchParams(useSearch());
  const [, nav] = useLocation();
  const qc = useQueryClient();
  const fixtures = useFixtures();
  const [mode, setMode] = useState<TaskMode>(params.get("mode") === "Technician" ? "Technician" : "Diy");
  const [goal, setGoal] = useState(params.get("goal") ?? "");
  const [details, setDetails] = useState("");
  const [asset, setAsset] = useState(params.get("asset") ?? "");
  const [manuals, setManuals] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const voice = usePushToTalk((text) => setGoal(text));
  const site = fixtures.data?.flatMap((f) => f.assets.map((a) => ({ fixtureId: f.fixtureId, ...a })));
  const chosen = site?.find((a) => a.assetId === asset);

  async function submit() {
    setBusy(true);
    try {
      const r = await tasks.create({ mode, goal: goal.trim(), details: details.trim() || undefined, fixtureId: chosen?.fixtureId, assetId: chosen?.assetId, manuals });
      qc.setQueryData(taskKeys.one(r.task.taskId), r.task);
      qc.invalidateQueries({ queryKey: taskKeys.list });
      if (r.planError) toast.warning(r.planError.code === "unsafe_plan" ? "The first plan failed the safety checks. Try again." : "The plan isn't ready. Try again.");
      nav(`/m/t/${r.task.taskId}`);
    } catch (e) {
      toast.error(friendlyError(e));
      setBusy(false);
    }
  }

  return (
    <>
      <TopBar title="New task" back="/m" />
      <Page className="space-y-5">
        <div className="grid grid-cols-2 gap-2 rounded-2xl border border-border bg-surface p-1 text-sm" role="tablist" aria-label="Mode">
          {(["Diy", "Technician"] as const).map((m) => (
            <button key={m} role="tab" aria-selected={mode === m} onClick={() => setMode(m)}
              className={cn("flex h-10 items-center justify-center gap-2 rounded-xl", mode === m ? "bg-primary text-primary-foreground" : "text-muted-foreground")}>
              {m === "Diy" ? <Sparkles className="size-4" /> : <Wrench className="size-4" />}{m === "Diy" ? "Do it yourself" : "Technician"}
            </button>
          ))}
        </div>

        <label className="block space-y-1.5">
          <span className="text-sm font-medium">What do you want to do?</span>
          <div className="flex items-start gap-2">
            <textarea value={voice.listening ? voice.heard || goal : goal} onChange={(e) => setGoal(e.target.value)} rows={3} maxLength={300}
              placeholder={mode === "Diy" ? "Change the oil in my 2025 Honda Civic" : "Install a PoE camera on the loading dock and connect it to the switch"}
              className="flex-1 resize-none rounded-2xl border border-input bg-background px-3.5 py-3 text-sm outline-none focus:border-primary" />
            {voice.supported && (
              <button type="button" onClick={voice.listening ? voice.stop : voice.start} aria-label={voice.listening ? "Stop listening" : "Say it"}
                className={cn("grid size-12 place-items-center rounded-2xl border border-border", voice.listening && "border-primary bg-primary text-primary-foreground")}>
                {voice.listening ? <Square className="size-4" /> : <Mic className="size-5" />}
              </button>
            )}
          </div>
        </label>

        <label className="block space-y-1.5">
          <span className="text-sm font-medium">Details <span className="font-normal text-muted-foreground">(optional)</span></span>
          <textarea value={details} onChange={(e) => setDetails(e.target.value)} rows={2} maxLength={1000}
            placeholder={mode === "Diy" ? "Engine size, tools you have, anything unusual" : "Site notes, access, what's already in place"}
            className="w-full resize-none rounded-2xl border border-input bg-background px-3.5 py-3 text-sm outline-none focus:border-primary" />
        </label>

        {mode === "Technician" && (
          <label className="block space-y-1.5">
            <span className="text-sm font-medium">Equipment on a site record <span className="font-normal text-muted-foreground">(optional)</span></span>
            <select value={asset} onChange={(e) => setAsset(e.target.value)} className="h-11 w-full rounded-2xl border border-input bg-background px-3 text-sm">
              <option value="">None</option>
              {site?.map((a) => <option key={a.assetId} value={a.assetId}>{a.displayName} · {a.fixtureId}</option>)}
            </select>
            <span className="block text-xs text-muted-foreground">With a site record the task can finish with the approved equipment check and a FieldProof receipt.</span>
          </label>
        )}

        <div className="space-y-1.5">
          <span className="text-sm font-medium">Manual photos <span className="font-normal text-muted-foreground">(optional, up to 3)</span></span>
          <div className="flex flex-wrap gap-2">
            {manuals.map((f, n) => (
              <span key={n} className="flex items-center gap-1.5 rounded-xl border border-border px-2.5 py-1.5 text-xs">
                <BookOpen className="size-3.5" />{f.name.slice(0, 18)}
                <button onClick={() => setManuals(manuals.filter((_, k) => k !== n))} aria-label="Remove photo"><X className="size-3.5" /></button>
              </span>
            ))}
            {manuals.length < 3 && (
              <label className="flex cursor-pointer items-center gap-1.5 rounded-xl border border-dashed border-border px-3 py-1.5 text-xs text-muted-foreground">
                <BookOpen className="size-3.5" />Add a page or label
                <input type="file" accept="image/*" capture="environment" className="hidden"
                  onChange={(e) => { const f = e.target.files?.[0]; if (f) setManuals([...manuals, f].slice(0, 3)); e.target.value = ""; }} />
              </label>
            )}
          </div>
          <p className="text-xs text-muted-foreground">A photo of the owner's manual or product label lets LensQi use its exact specs.</p>
        </div>

        <Button size="lg" className="h-12 w-full rounded-2xl" disabled={busy || goal.trim().length < 3} onClick={submit}>
          {busy ? <><Loader2 className="animate-spin" /> Writing your plan…</> : <><Sparkles /> Plan it</>}
        </Button>
        {busy && (
          <div className="flex items-start gap-2.5 rounded-2xl border border-border p-3 text-xs text-muted-foreground">
            <LensQiMark className="size-6" /> This takes up to half a minute. The plan is checked for safety before you see it.
          </div>
        )}
      </Page>
    </>
  );
}
