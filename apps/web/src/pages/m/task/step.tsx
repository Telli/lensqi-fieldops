import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "wouter";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { ArrowLeft, Camera, Check, ChevronRight, Ear, Loader2, Mic, Radio, Square, Volume2, VolumeX, X } from "lucide-react";
import { LensQiMark } from "@/components/fo/ai";
import { LiveOverlay } from "@/components/fo/live-overlay";
import { cameraError, connectCamera } from "@/lib/camera";
import { isCancelled } from "@/lib/api";
import { friendlyError } from "@/lib/hooks";
import { cautionLabel, stepIndex, taskKeys, tasks, useTask, verdictLabel, type Task } from "@/lib/tasks";
import { useLiveAssist } from "@/lib/use-live-assist";
import { parseCommand, speechNotice, useHandsFree, usePause, usePushToTalk, useSpeak } from "@/lib/voice";
import { cn } from "@/lib/utils";

/** The current camera frame as a JPEG, no larger than `maxSide` on its long edge. */
function grab(video: HTMLVideoElement | null, maxSide: number): Promise<Blob | null> {
  if (!video || !video.videoWidth) return Promise.resolve(null);
  const scale = Math.min(1, maxSide / Math.max(video.videoWidth, video.videoHeight));
  const c = document.createElement("canvas");
  c.width = Math.round(video.videoWidth * scale); c.height = Math.round(video.videoHeight * scale);
  c.getContext("2d")!.drawImage(video, 0, 0, c.width, c.height);
  return new Promise((resolve) => c.toBlob(resolve, "image/jpeg", 0.9));
}

/**
 * One step at a time: the camera with live outlines of the step's parts, the instruction read aloud, push-to-talk for
 * questions and commands, a photo check, and Done. The AI coaches and suggests; the person decides when a step is done.
 */
export default function TaskStepPage({ id, stepId }: { id: string; stepId?: string }) {
  const { data: t } = useTask(id);
  const qc = useQueryClient();
  const [, nav] = useLocation();
  const video = useRef<HTMLVideoElement>(null);
  const file = useRef<HTMLInputElement>(null);
  const [live, setLive] = useState<"starting" | "on" | "off">("starting");
  const [cameraNote, setCameraNote] = useState<string | null>(null);
  const [busy, setBusy] = useState<null | "ask" | "check" | "move">(null);
  const [answer, setAnswer] = useState<{ question: string; answer: string } | null>(null);
  const [typed, setTyped] = useState("");
  const speech = useSpeak(true);
  const inflight = useRef<AbortController | null>(null);
  // Leaving the step ends a question or check still waiting for the model, so its answer never toasts on another screen.
  useEffect(() => () => inflight.current?.abort(), []);
  const cancel = () => inflight.current?.abort();

  const steps = t?.plan?.steps ?? [];
  const step = steps.find((s) => s.id === (stepId ?? t?.currentStepId));
  const n = stepIndex(t, step?.id);
  const open = t?.phase === "InProgress";
  // An earlier, finished step shown read-only. The server coaches and answers for its current step, so questions and live
  // coaching stay off here rather than answer about a different step than the one on screen.
  const reviewing = open && !!step && t?.currentStepId !== step.id;
  const assist = useLiveAssist(video, live === "on" && open && !!step && !reviewing, `/v1/tasks/${encodeURIComponent(id)}`);
  const save = (next: Task) => qc.setQueryData(taskKeys.one(id), next);
  const pauser = usePause(assist, speech);

  // Camera for the whole visit, once the video element is on screen (after the task loads, on a cold start too); frames
  // leave the phone only while live coaching is on.
  const mounted = !!t && !!step;
  useEffect(() => {
    if (!mounted) return;
    if (!navigator.mediaDevices?.getUserMedia || !window.isSecureContext) { setLive("off"); setCameraNote("Live camera isn't available here. You can still follow the steps and add photos."); return; }
    const v = video.current;
    const dispose = connectCamera(
      () => navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" }, width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false }),
      (s) => { if (v) { v.srcObject = s; setLive("on"); } },
      (e) => { setLive("off"); setCameraNote(cameraError(e)); },
    );
    return () => { dispose(); if (v) v.srcObject = null; };
  }, [mounted]);

  // The route names the step; the server's current step follows it, so coaching and questions use the right step.
  useEffect(() => {
    if (!t) return;
    if (t.phase === "Completed") { nav(`/m/t/${id}/done`, { replace: true }); return; }
    if (t.phase === "Planned" || t.phase === "Abandoned") { nav(`/m/t/${id}`, { replace: true }); return; }
    const current = t.currentStepId ? `/m/t/${id}/step/${t.currentStepId}` : `/m/t/${id}`;
    if (!stepId) { nav(current, { replace: true }); return; }
    const routed = steps.find((s) => s.id === stepId);
    // A step id from an old link or another plan is not on this task: show the current step instead of waiting forever.
    if (!routed) { nav(current, { replace: true }); return; }
    // Only an unfinished step becomes current; a finished one is shown read-only with a way back to the current step.
    // When the server will not open it, coaching and questions would be about its own current step, so go there.
    // A failure that arrives after the route changed belongs to a screen that is gone: no toast, no redirect.
    let active = true;
    if (open && t.currentStepId !== stepId && routed.status !== "Done" && routed.status !== "Skipped")
      tasks.start(id, routed.id).then(save).catch((e) => { if (!active) return; toast.error(`This step couldn't be opened. ${friendlyError(e)}`); nav(current, { replace: true }); });
    return () => { active = false; };
  }, [t?.phase, t?.currentStepId, stepId]); // eslint-disable-line react-hooks/exhaustive-deps

  const readStep = (force = false) => step && speech.speak(`Step ${n + 1}. ${step.title}. ${step.instruction} ${step.cautions.map((c) => c.text).join(". ")}`, force);
  useEffect(() => { setAnswer(null); readStep(true); }, [step?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  // Speak AI guidance when it is new, not the local light/focus hints that change every frame.
  useEffect(() => { if (assist.enabled && assist.updatedAt) speech.speak(assist.recommendation); }, [assist.updatedAt]); // eslint-disable-line react-hooks/exhaustive-deps

  async function ask(question: string) {
    // One command at a time: a question during a photo check would take over the check's controller and its Cancel.
    if (!question.trim() || busy) return;
    if (reviewing) { toast.info("Questions are about the current step. Go to it to ask."); return; }
    setBusy("ask");
    const controller = new AbortController();
    inflight.current = controller;
    try {
      const frame = live === "on" ? await grab(video.current, 1280) : null;
      const r = await tasks.ask(id, question.trim(), frame, { signal: controller.signal, stepId: step?.id });
      setAnswer({ question: question.trim(), answer: r.answer });
      speech.speak(r.answer, true);
      setTyped("");
    } catch (e) { if (!isCancelled(e)) toast.error(friendlyError(e)); } finally { setBusy(null); }
  }

  async function check(photo?: Blob | null) {
    // A finished step is read-only: no check is recorded against it, by tap or by voice.
    if (!step || busy || step.status === "Done" || step.status === "Skipped") return;
    // No camera frame to take: the picker's file comes back through this function as `photo`, so no lock is needed yet.
    if (!photo && live !== "on") { file.current?.click(); return; }
    // Locked before the first await: a second tap while the frame is still being encoded must not start a second check.
    setBusy("check");
    const controller = new AbortController();
    inflight.current = controller;
    try {
      const blob = photo ?? await grab(video.current, 4096);
      if (!blob) { file.current?.click(); return; }
      const r = await tasks.check(id, step.id, blob, controller.signal);
      save(r.task);
      speech.speak(`${verdictLabel[r.check.verdict].text}. ${r.check.reason}`, true);
    } catch (e) {
      // A cancelled check may still be recorded by the server; the refresh shows it if so.
      if (!isCancelled(e)) toast.error(friendlyError(e));
      qc.invalidateQueries({ queryKey: taskKeys.one(id) });
    } finally { setBusy(null); }
  }

  async function move(kind: "complete" | "skip" | "back") {
    if (!step || busy) return;
    if (kind === "back") { const prev = steps[n - 1]; if (prev) nav(`/m/t/${id}/step/${prev.id}`); return; }
    setBusy("move");
    try {
      const next = kind === "complete" ? await tasks.complete(id, step.id) : await tasks.skip(id, step.id);
      save(next);
      qc.invalidateQueries({ queryKey: taskKeys.list });
      nav(next.phase === "Completed" ? `/m/t/${id}/done` : `/m/t/${id}/step/${next.currentStepId}`);
    } catch (e) { toast.error(friendlyError(e)); qc.invalidateQueries({ queryKey: taskKeys.one(id) }); } finally { setBusy(null); }
  }

  const voice = usePushToTalk((text) => {
    switch (parseCommand(text)) {
      case "next": return void (reviewing && t?.currentStepId ? nav(`/m/t/${id}/step/${t.currentStepId}`) : move("complete"));
      case "back": return void move("back");
      case "skip": return void move("skip");
      case "check":
        // A voice command is not a tap, so the browser would block opening the photo picker.
        if (live !== "on") { toast.info("Turn the camera on, or tap the camera button to add a photo."); return; }
        if (!video.current?.videoWidth) { toast.info("The camera is still starting. Say “check” again in a moment."); return; }
        return void check();
      case "repeat": return void readStep(true);
      case "mute": return speech.toggle(false);
      case "unmute": return speech.toggle(true);
      case "pause": return pauser.pause();
      case "resume": return pauser.resume();
      default: return void ask(text);
    }
  }, { onListening: speech.setListening });
  const handsFree = useHandsFree(voice, speech, !!busy);

  if (!t || !step)
    return <div className="grid h-dvh place-items-center bg-overlay text-overlay-foreground"><Loader2 className="animate-spin opacity-60" /></div>;

  const needsCheck = step.check?.required && !step.lastCheck;
  const finished = step.status === "Done" || step.status === "Skipped";
  return (
    <div className="dark flex h-dvh flex-col overflow-hidden bg-overlay text-overlay-foreground md:h-full">
      <div className="relative min-h-0 flex-1 bg-black">
        <video ref={video} autoPlay playsInline muted className={cn("absolute inset-0 size-full object-contain", live !== "on" && "hidden")} />
        {assist.enabled && <LiveOverlay {...assist} targetConcept={step.lookFor[0]} onSelect={busy || reviewing ? undefined : (o) => void ask(`Tell me about the ${o.label} in view. Is anything wrong with it for this step?`)} />}
        {live !== "on" && <p className="absolute inset-0 grid place-items-center px-8 text-center text-sm opacity-70">{live === "starting" ? "Starting camera…" : cameraNote}</p>}
        <div className="absolute inset-x-0 top-0 flex items-center gap-2 p-3">
          <Link href={`/m/t/${id}`} aria-label="Close" className="glass grid size-11 place-items-center rounded-full"><X className="size-5" /></Link>
          <span className="glass flex-1 truncate rounded-full px-3 py-2 text-xs">Step {n + 1} of {steps.length} · {t.plan!.title}</span>
          <button onClick={() => speech.toggle()} disabled={!speech.supported} aria-pressed={speech.on} aria-label={speech.on ? "Mute voice" : "Turn voice on"} className="glass grid size-11 place-items-center rounded-full disabled:opacity-40">
            {speech.on ? <Volume2 className="size-5 text-primary" /> : <VolumeX className="size-5" />}
          </button>
        </div>
        {live === "on" && !reviewing && (
          <button onClick={() => assist.setEnabled(!assist.enabled)} aria-pressed={assist.enabled}
            className={cn("glass absolute bottom-3 left-3 flex min-h-11 items-center gap-1.5 rounded-full px-3 text-xs", assist.enabled && "text-primary")}>
            <Radio className="size-4" />{assist.enabled ? assist.status : "Start live coaching"}
          </button>
        )}
      </div>

      <div className="max-h-[58%] space-y-3 overflow-y-auto rounded-t-3xl border-t border-overlay-foreground/10 bg-overlay px-4 pb-[max(1rem,env(safe-area-inset-bottom))] pt-3">
        <div className="flex gap-1" aria-hidden>{steps.map((s, k) => <span key={s.id} className={cn("h-1 flex-1 rounded-full", s.status === "Done" ? "bg-primary" : k === n ? "bg-primary/50" : s.status === "Skipped" ? "bg-overlay-foreground/30" : "bg-overlay-foreground/15")} />)}</div>
        <div>
          <h1 className="text-lg font-semibold leading-snug">{step.title}</h1>
          <p className="mt-1 text-sm leading-relaxed opacity-90">{step.instruction}</p>
        </div>
        {step.cautions.length > 0 && <div className="flex flex-wrap gap-1.5">{step.cautions.map((c, k) => <span key={k} className="rounded-full border border-warning/50 bg-warning/15 px-2 py-0.5 text-[11px] text-warning">{cautionLabel[c.kind]} · {c.text}</span>)}</div>}
        {step.stopIf.length > 0 && <p className="text-xs text-failure">Stop if {step.stopIf.join("; ")}.</p>}
        {finished && open && t.currentStepId && t.currentStepId !== step.id && (
          <Link href={`/m/t/${id}/step/${t.currentStepId}`} className="flex min-h-11 items-center justify-between rounded-2xl border border-primary/40 px-3 text-sm text-primary">
            This step is {step.status === "Done" ? "done" : "skipped"}. Go to the current step <ChevronRight className="size-4" />
          </Link>
        )}
        {step.lookFor.length > 0 && <p className="text-xs opacity-70">Look for: {step.lookFor.join(", ")}</p>}

        {assist.enabled ? (
          <div className="flex gap-2.5 rounded-2xl border border-overlay-foreground/15 p-3">
            <LensQiMark className="size-6" />
            <div className="min-w-0 text-sm">
              <p>{assist.recommendation}</p>
              {assist.screenText && <p className="mt-1 line-clamp-3 text-xs opacity-70">On screen: “{assist.screenText}”</p>}
            </div>
          </div>
        ) : live === "on" && !reviewing && <p className="text-[11px] opacity-60">Live coaching sends sampled frames to AI for guidance. Only check photos are saved to the task.</p>}

        {answer && (
          <div className="space-y-1 rounded-2xl border border-primary/30 bg-primary/10 p-3 text-sm" aria-live="polite">
            <p className="text-xs opacity-70">You asked: {answer.question}</p>
            <p>{answer.answer}</p>
          </div>
        )}
        {step.lastCheck && (
          <div className="flex gap-3 rounded-2xl border border-overlay-foreground/15 p-3 text-sm" aria-live="polite">
            <img src={tasks.photoUrl(id, step.lastCheck.photoId)} alt="Step check photo" className="size-14 shrink-0 rounded-lg object-cover" onError={(e) => (e.currentTarget.style.display = "none")} />
            <div><p className={cn("font-medium", verdictLabel[step.lastCheck.verdict].tone)}>AI check: {verdictLabel[step.lastCheck.verdict].text}</p><p className="text-xs opacity-80">{step.lastCheck.reason}</p><p className="mt-0.5 text-[10px] opacity-60">A suggestion. You decide when the step is done.</p></div>
          </div>
        )}
        {pauser.paused && (
          <button onClick={pauser.resume} className="flex min-h-11 w-full items-center justify-between rounded-2xl border border-primary/40 bg-primary/10 px-3 text-sm text-primary" aria-live="polite">
            Paused: no speech or live coaching. <span className="font-medium">Say “resume” or tap</span>
          </button>
        )}
        {voice.listening && <p className="text-center text-sm text-primary" aria-live="polite">{voice.heard || "Listening…"}</p>}
        {handsFree.on && !voice.listening && <p className="text-center text-[11px] opacity-60">Hands-free: the microphone opens again after each answer.</p>}
        {voice.error && <p className="text-center text-xs text-warning">{voice.error}</p>}

        <form onSubmit={(e) => { e.preventDefault(); ask(typed); }} className="flex items-center gap-2">
          {voice.supported && (
            <button type="button" onClick={voice.listening ? voice.stop : voice.start} disabled={busy === "ask"} aria-label={voice.listening ? "Stop listening" : "Ask or say a command"}
              className={cn("grid size-12 shrink-0 place-items-center rounded-2xl border border-overlay-foreground/20", voice.listening && "border-primary bg-primary text-primary-foreground")}>
              {voice.listening ? <Square className="size-4" /> : <Mic className="size-5" />}
            </button>
          )}
          {voice.supported && (
            <button type="button" onClick={handsFree.toggle} disabled={reviewing} aria-pressed={handsFree.on} aria-label={handsFree.on ? "Turn hands-free off" : "Hands-free: keep listening"}
              className={cn("grid size-12 shrink-0 place-items-center rounded-2xl border border-overlay-foreground/20 disabled:opacity-40", handsFree.on && "border-primary text-primary")}>
              <Ear className="size-5" />
            </button>
          )}
          <input value={typed} onChange={(e) => setTyped(e.target.value)} disabled={busy === "ask" || reviewing} placeholder={busy === "ask" ? "Thinking…" : reviewing ? "Go to the current step to ask" : "Ask about this step…"} aria-label="Ask a question"
            className="h-12 min-w-0 flex-1 rounded-2xl border border-overlay-foreground/20 bg-transparent px-3 text-sm outline-none focus:border-primary" />
          {busy === "ask" ? (
            <button type="button" onClick={cancel} aria-label="Cancel the question" className="grid size-12 shrink-0 place-items-center rounded-2xl border border-overlay-foreground/20">
              <Loader2 className="size-5 animate-spin" />
            </button>
          ) : typed.trim() && (
            <button type="submit" aria-label="Ask" className="grid size-12 shrink-0 place-items-center rounded-2xl border border-overlay-foreground/20">
              <ChevronRight className="size-5" />
            </button>
          )}
        </form>
        {voice.notice && <p className="text-[11px] opacity-60">{speechNotice}</p>}
        <div className="grid grid-cols-[auto_auto_1fr] items-center gap-2">
          <button onClick={() => move("back")} disabled={n === 0 || !!busy} aria-label="Previous step" className="grid size-12 place-items-center rounded-2xl border border-overlay-foreground/20 disabled:opacity-30"><ArrowLeft className="size-5" /></button>
          <button onClick={() => (busy === "check" ? cancel() : check())} disabled={(!!busy && busy !== "check") || !open || finished} aria-label={busy === "check" ? "Cancel the photo check" : "Check this step with a photo"} className={cn("grid size-12 place-items-center rounded-2xl border border-overlay-foreground/20", needsCheck && "border-primary text-primary")}>
            {busy === "check" ? <Loader2 className="size-5 animate-spin" /> : <Camera className="size-5" />}
          </button>
          <button onClick={() => move("complete")} disabled={!!busy || !open || finished} aria-label="Step done"
            className="flex h-12 items-center justify-center gap-1 rounded-2xl bg-primary px-4 text-sm font-medium text-primary-foreground disabled:opacity-40">
            {busy === "move" ? <Loader2 className="size-4 animate-spin" /> : <Check className="size-4" />}Done
          </button>
        </div>
        <div className="flex items-center justify-between text-xs opacity-70">
          <span>{needsCheck ? "Tip: a quick check photo can confirm this step." : voice.supported ? "Say “next”, “repeat”, “check this”, or ask a question." : ""}</span>
          {!finished && open && <button onClick={() => move("skip")} disabled={!!busy} className="flex min-h-11 items-center gap-0.5 px-1">Skip <ChevronRight className="size-3.5" /></button>}
        </div>
        <input ref={file} type="file" accept="image/jpeg,image/png" capture="environment" className="hidden" onChange={(e) => { const f = e.target.files?.[0]; if (f) check(f); e.target.value = ""; }} />
      </div>
    </div>
  );
}
