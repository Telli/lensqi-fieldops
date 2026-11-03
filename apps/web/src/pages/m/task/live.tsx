import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "wouter";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Ear, ListChecks, Loader2, Mic, Radio, Send, Square, Volume2, VolumeX, X } from "lucide-react";
import { LensQiMark } from "@/components/fo/ai";
import { LiveOverlay } from "@/components/fo/live-overlay";
import { cameraError, connectCamera } from "@/lib/camera";
import { isCancelled } from "@/lib/api";
import { friendlyError } from "@/lib/hooks";
import { taskKeys, tasks, useTask } from "@/lib/tasks";
import { useLiveAssist } from "@/lib/use-live-assist";
import { parseCommand, speechNotice, useHandsFree, usePause, usePushToTalk, useSpeak } from "@/lib/voice";
import { cn } from "@/lib/utils";

const LOOK = "What am I looking at, and is anything wrong?";

function grab(video: HTMLVideoElement | null): Promise<Blob | null> {
  if (!video || !video.videoWidth) return Promise.resolve(null);
  const scale = Math.min(1, 1280 / Math.max(video.videoWidth, video.videoHeight));
  const c = document.createElement("canvas");
  c.width = Math.round(video.videoWidth * scale); c.height = Math.round(video.videoHeight * scale);
  c.getContext("2d")!.drawImage(video, 0, 0, c.width, c.height);
  return new Promise((resolve) => c.toBlob(resolve, "image/jpeg", 0.9));
}

/**
 * Free-form help: the camera and a conversation, no plan. Ask about anything in view by voice or text; live coaching
 * outlines what LensQi points at. A longer job can become a step-by-step plan at any time.
 */
export default function FreeformLive({ id }: { id: string }) {
  const { data: t } = useTask(id);
  const qc = useQueryClient();
  const [, nav] = useLocation();
  const video = useRef<HTMLVideoElement>(null);
  const [live, setLive] = useState<"starting" | "on" | "off">("starting");
  const [cameraNote, setCameraNote] = useState<string | null>(null);
  const [asking, setAsking] = useState(false);
  const [typed, setTyped] = useState("");
  const speech = useSpeak(true);
  const open = t?.phase === "InProgress";
  const assist = useLiveAssist(video, live === "on" && open, `/v1/tasks/${encodeURIComponent(id)}`);
  const answers = [...(t?.answers ?? [])].reverse();
  const pauser = usePause(assist, speech);
  const inflight = useRef<AbortController | null>(null);
  useEffect(() => () => inflight.current?.abort(), []);

  useEffect(() => {
    if (!navigator.mediaDevices?.getUserMedia || !window.isSecureContext) { setLive("off"); setCameraNote("Live camera isn't available here. You can still ask by typing."); return; }
    const v = video.current;
    const dispose = connectCamera(
      () => navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" }, width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false }),
      (s) => { if (v) { v.srcObject = s; setLive("on"); } },
      (e) => { setLive("off"); setCameraNote(cameraError(e)); },
    );
    return () => { dispose(); if (v) v.srcObject = null; };
  }, []);
  useEffect(() => { if (assist.enabled && assist.updatedAt) speech.speak(assist.recommendation); }, [assist.updatedAt]); // eslint-disable-line react-hooks/exhaustive-deps

  async function ask(question: string) {
    // One question at a time: a second submit while one is pending would replace the controller Cancel and unmount abort.
    if (!question.trim() || !open || asking) return;
    setAsking(true);
    // A question asked by typing or by tapping a box while the microphone is open ends the listen first, so the answer is spoken.
    if (voice.listening) voice.abort();
    const controller = new AbortController();
    inflight.current = controller;
    try {
      const frame = live === "on" ? await grab(video.current) : null;
      const r = await tasks.ask(id, question.trim(), frame, { signal: controller.signal });
      speech.speak(r.answer, true);
      setTyped("");
      qc.invalidateQueries({ queryKey: taskKeys.one(id) });
      qc.invalidateQueries({ queryKey: taskKeys.list });
    } catch (e) { if (!isCancelled(e)) toast.error(friendlyError(e)); } finally { setAsking(false); }
  }

  const voice = usePushToTalk((text) => {
    switch (parseCommand(text)) {
      case "repeat": return answers[0] && speech.speak(answers[0].answer, true);
      case "mute": return speech.toggle(false);
      case "unmute": return speech.toggle(true);
      case "pause": return pauser.pause();
      case "resume": return pauser.resume();
      case "check": return void ask(LOOK);
      default: return void ask(text);
    }
  }, { onListening: speech.setListening });
  const handsFree = useHandsFree(voice, speech, asking || !open);

  async function end() {
    try { qc.setQueryData(taskKeys.one(id), await tasks.finish(id)); qc.invalidateQueries({ queryKey: taskKeys.list }); nav("/m"); }
    catch (e) { toast.error(friendlyError(e)); }
  }
  const planHref = `/m/new?mode=Diy&source=${encodeURIComponent(id)}&goal=${encodeURIComponent(t?.title && t.title !== "Just ask" ? t.title : answers.find(a => a.question !== LOOK)?.question ?? "")}`;

  return (
    <div className="dark flex h-dvh flex-col overflow-hidden bg-overlay text-overlay-foreground md:h-full">
      <div className="relative min-h-0 flex-1 bg-black">
        <video ref={video} autoPlay playsInline muted className={cn("absolute inset-0 size-full object-contain", live !== "on" && "hidden")} />
        {assist.enabled && <LiveOverlay {...assist} onSelect={asking || !open ? undefined : (o) => void ask(`Tell me about the ${o.label} in view. Is anything wrong with it?`)} />}
        {live !== "on" && <p className="absolute inset-0 grid place-items-center px-8 text-center text-sm opacity-70">{live === "starting" ? "Starting camera…" : cameraNote}</p>}
        <div className="absolute inset-x-0 top-0 flex items-center gap-2 p-3">
          <Link href="/m" aria-label="Close" className="glass grid size-11 place-items-center rounded-full"><X className="size-5" /></Link>
          <span className="glass flex-1 truncate rounded-full px-3 py-2 text-xs">{t?.title ?? "Just ask"}</span>
          <button onClick={() => speech.toggle()} disabled={!speech.supported} aria-pressed={speech.on} aria-label={speech.on ? "Mute voice" : "Turn voice on"} className="glass grid size-11 place-items-center rounded-full disabled:opacity-40">
            {speech.on ? <Volume2 className="size-5 text-primary" /> : <VolumeX className="size-5" />}
          </button>
        </div>
        {live === "on" && open && (
          <button onClick={() => assist.setEnabled(!assist.enabled)} aria-pressed={assist.enabled}
            className={cn("glass absolute bottom-3 left-3 flex min-h-11 items-center gap-1.5 rounded-full px-3 text-xs", assist.enabled && "text-primary")}>
            <Radio className="size-4" />{assist.enabled ? assist.status : "Start live help"}
          </button>
        )}
      </div>

      <div className="max-h-[55%] space-y-3 overflow-y-auto rounded-t-3xl border-t border-overlay-foreground/10 bg-overlay px-4 pb-[max(1rem,env(safe-area-inset-bottom))] pt-3">
        {voice.notice && <p className="text-xs opacity-70">{speechNotice}</p>}
        {assist.enabled ? (
          <div className="flex gap-2.5 rounded-2xl border border-overlay-foreground/15 p-3">
            <LensQiMark className="size-6" />
            <div className="min-w-0 text-sm"><p>{assist.recommendation}</p>{assist.screenText && <p className="mt-1 line-clamp-3 text-xs opacity-70">On screen: “{assist.screenText}”</p>}</div>
          </div>
        ) : answers.length === 0 && (
          <div className="flex gap-2.5 text-sm">
            <LensQiMark className="size-6" />
            <p className="opacity-90">Point at anything and ask: an error on a screen, a part you don't recognise, a quick fix. {live === "on" ? "Live help sends sampled frames to AI; a question sends the current frame." : ""}</p>
          </div>
        )}
        {answers.slice(0, 4).map((a, k) => (
          <div key={k} className={cn("space-y-1 rounded-2xl p-3 text-sm", k === 0 ? "border border-primary/30 bg-primary/10" : "border border-overlay-foreground/10 opacity-80")}>
            <p className="text-xs opacity-70">You asked: {a.question}</p><p>{a.answer}</p>
          </div>
        ))}
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
            <button type="button" onClick={voice.listening ? voice.stop : voice.start} disabled={asking || !open} aria-label={voice.listening ? "Stop listening" : "Ask by voice"}
              className={cn("grid size-12 shrink-0 place-items-center rounded-2xl border border-overlay-foreground/20", voice.listening && "border-primary bg-primary text-primary-foreground")}>
              {voice.listening ? <Square className="size-4" /> : <Mic className="size-5" />}
            </button>
          )}
          {voice.supported && (
            <button type="button" onClick={handsFree.toggle} disabled={!open} aria-pressed={handsFree.on} aria-label={handsFree.on ? "Turn hands-free off" : "Hands-free: keep listening"}
              className={cn("grid size-12 shrink-0 place-items-center rounded-2xl border border-overlay-foreground/20 disabled:opacity-40", handsFree.on && "border-primary text-primary")}>
              <Ear className="size-5" />
            </button>
          )}
          <input value={typed} onChange={(e) => setTyped(e.target.value)} disabled={!open} placeholder={open ? "Ask anything about what you see…" : "This session has ended"} aria-label="Ask a question"
            className="h-12 min-w-0 flex-1 rounded-2xl border border-overlay-foreground/20 bg-transparent px-3 text-sm outline-none focus:border-primary" />
          {asking ? (
            <button type="button" onClick={() => inflight.current?.abort()} aria-label="Cancel the question" className="grid size-12 shrink-0 place-items-center rounded-2xl bg-primary text-primary-foreground">
              <Loader2 className="size-5 animate-spin" />
            </button>
          ) : (
            <button type="submit" disabled={!typed.trim() || !open} aria-label="Ask" className="grid size-12 shrink-0 place-items-center rounded-2xl bg-primary text-primary-foreground disabled:opacity-40">
              <Send className="size-5" />
            </button>
          )}
        </form>
        <div className="flex items-center justify-between gap-2 text-xs">
          <button onClick={() => ask(LOOK)} disabled={asking || !open} className="min-h-11 rounded-xl border border-overlay-foreground/20 px-3 disabled:opacity-40">What am I looking at?</button>
          <Link href={planHref} className="flex min-h-11 items-center gap-1.5 rounded-xl px-2 text-primary"><ListChecks className="size-4" />Make a plan</Link>
          {open && <button onClick={end} className="min-h-11 px-2 opacity-70">End</button>}
        </div>
      </div>
    </div>
  );
}
