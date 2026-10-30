import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Camera as CameraIcon, Images, Layers, Loader2, RotateCcw, Sparkles, X } from "lucide-react";
import { api, ApiError, newKey, type ObservationResponse } from "@/lib/api";
import { cameraError, connectCamera } from "@/lib/camera";
import { friendlyError, qk } from "@/lib/hooks";
import { changeLabel } from "@/lib/labels";
import { useLiveAssist } from "@/lib/use-live-assist";
import { LiveOverlay } from "@/components/fo/live-overlay";
import { EvidenceBadge } from "@/components/fo/primitives";
import { cn } from "@/lib/utils";

/** One capture: the original bytes and the key that identifies it, kept until the server has answered. */
interface Capture { blob: Blob; url: string; name: string; source: "Phone" | "RecordedFixture"; key: string; observedAt: string }

/**
 * The camera inside the verify screen. Live preview with the earlier photo as a faint guide and, if switched on, live
 * AI framing advice; neither is saved or counts as evidence. Only the shutter photo is uploaded, at full resolution,
 * under one key per capture, so "Try again" after a dropped connection cannot record it twice. The server compares it
 * with the earlier view inside the upload request; this shows its verdict and never decides one.
 */
export function VerifyCamera({ id, prompt, guideSrc, onAccepted, onClose }: {
  id: string; prompt: string; guideSrc?: string;
  onAccepted: (r: ObservationResponse) => void; onClose?: () => void;
}) {
  const qc = useQueryClient();
  const video = useRef<HTMLVideoElement>(null);
  const fileCamera = useRef<HTMLInputElement>(null);
  const fileGallery = useRef<HTMLInputElement>(null);
  const [live, setLive] = useState<"starting" | "on" | "off">("starting");
  const [message, setMessage] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [guide, setGuide] = useState(!!guideSrc);
  const [capture, setCapture] = useState<Capture | null>(null);
  const [phase, setPhase] = useState<"framing" | "saving" | "failed" | "rejected">("framing");
  const [problem, setProblem] = useState<string | null>(null);
  const [answer, setAnswer] = useState<ObservationResponse | null>(null);
  const saving = useRef(false);
  const assist = useLiveAssist(video, live === "on" && !capture, `/v1/inspections/${encodeURIComponent(id)}`);

  useEffect(() => {
    if (capture) return;
    setLive("starting");
    setMessage(null);
    if (!navigator.mediaDevices?.getUserMedia || !window.isSecureContext) {
      setLive("off"); setMessage("Live preview isn't available here. Use your device camera or choose a photo."); return;
    }
    const v = video.current;
    const dispose = connectCamera(
      () => navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" }, width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false }),
      (s) => { if (v) { v.srcObject = s; setLive("on"); } },
      (e) => { setLive("off"); setMessage(cameraError(e)); },
    );
    return () => { dispose(); if (v) v.srcObject = null; };
  }, [capture, attempt]);

  useEffect(() => () => { if (capture) URL.revokeObjectURL(capture.url); }, [capture]);

  async function save(c: Capture) {
    if (saving.current) return;
    saving.current = true;
    setPhase("saving");
    setProblem(null);
    try {
      const r = await api.upload(id, c.blob, c.name, c.source, c.key, c.observedAt);
      qc.setQueryData(qk.inspection(id), r.state);
      setAnswer(r);
      if (r.changeAnalysis && (r.changeAnalysis.verdict === "DuplicateImage" || r.changeAnalysis.verdict === "NoVisualChange")) setPhase("rejected");
      else onAccepted(r);
    } catch (e) {
      // A refusal is final for this photo; anything else may not have reached the server, so the same capture is offered again.
      setProblem(friendlyError(e));
      setPhase(e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 408 && e.status !== 429 ? "rejected" : "failed");
    } finally {
      saving.current = false;
      qc.invalidateQueries({ queryKey: qk.events(id) });
      qc.invalidateQueries({ queryKey: qk.artifacts(id) });
    }
  }

  function take(blob: Blob, name: string, source: Capture["source"]) {
    const c = { blob, url: URL.createObjectURL(blob), name, source, key: newKey(), observedAt: new Date().toISOString() };
    setCapture(c);
    setAnswer(null);
    void save(c);
  }

  function shutter() {
    const v = video.current;
    if (live !== "on" || !v || !v.videoWidth) { fileCamera.current?.click(); return; }
    const canvas = document.createElement("canvas");
    canvas.width = v.videoWidth; canvas.height = v.videoHeight;
    canvas.getContext("2d")!.drawImage(v, 0, 0);
    canvas.toBlob((b) => b && take(b, `verify-${Date.now()}.jpg`, "Phone"), "image/jpeg", 0.92);
  }

  // A file from the native camera on a touch device is a capture; on a desktop it is an imported file and says so.
  const coarse = typeof matchMedia !== "undefined" && matchMedia("(pointer: coarse)").matches;
  const onFile = (source: Capture["source"]) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    e.target.value = "";
    if (f) take(f, f.name, source);
  };
  const retake = () => { setCapture(null); setAnswer(null); setProblem(null); setPhase("framing"); };
  const change = answer?.changeAnalysis;

  return (
    <div className="dark relative overflow-hidden rounded-[26px] bg-overlay text-overlay-foreground shadow-lg shadow-black/15" data-verify-camera>
      <div className="relative aspect-[3/4] max-h-[62dvh] w-full sm:aspect-[4/3]">
        <video ref={video} autoPlay playsInline muted className={cn("absolute inset-0 size-full object-cover", (live !== "on" || capture) && "hidden")} />
        {!capture && live === "on" && guide && guideSrc && (
          <img src={guideSrc} alt="" aria-hidden className="pointer-events-none absolute inset-0 size-full object-cover opacity-30 mix-blend-screen" />
        )}
        {!capture && assist.enabled && <LiveOverlay {...assist} />}
        {capture && <img src={capture.url} alt="The photo you just took" className="absolute inset-0 size-full object-cover" />}
        {phase === "saving" && <div className="pointer-events-none absolute inset-x-0 top-0 h-[9%] animate-scan bg-gradient-to-b from-transparent via-primary/25 to-transparent" />}
        {!capture && live === "off" && (
          <div className="absolute inset-0 grid place-items-center px-8 text-center text-sm text-overlay-foreground/75">
            <div>
              <CameraIcon className="mx-auto mb-3 size-9 opacity-60" />
              <p role="status">{message}</p>
              <button type="button" onClick={() => setAttempt((n) => n + 1)} className="mt-3 min-h-11 rounded-xl border border-overlay-foreground/30 px-4">Try camera again</button>
            </div>
          </div>
        )}
        {!capture && live === "starting" && <p role="status" className="absolute inset-x-0 top-1/2 flex items-center justify-center gap-2 text-sm"><Loader2 className="size-4 animate-spin" /> Starting camera…</p>}

        <div className="absolute inset-x-3 top-3 flex items-center gap-2">
          {onClose && phase !== "saving" && (
            <button type="button" onClick={onClose} aria-label="Close camera" className="glass grid size-10 place-items-center rounded-full"><X className="size-5" /></button>
          )}
          <div className="glass min-w-0 flex-1 rounded-full px-3 py-2 text-xs">
            <span className="line-clamp-2">{capture ? (phase === "saving" ? "Saving the photo and comparing it with the earlier view…" : "The photo you took") : assist.enabled && live === "on" ? assist.recommendation : prompt}</span>
          </div>
        </div>
        {!capture && live === "on" && (
          <div className="absolute inset-x-3 bottom-3 flex flex-wrap gap-2 text-[11px]">
            {guideSrc && (
              <button type="button" onClick={() => setGuide(!guide)} aria-pressed={guide} className={cn("glass flex min-h-9 items-center gap-1.5 rounded-full px-3", guide && "text-primary")}>
                <Layers className="size-3.5" /> {guide ? "Earlier view shown" : "Show earlier view"}
              </button>
            )}
            <button type="button" onClick={() => assist.setEnabled(!assist.enabled)} aria-pressed={assist.enabled} className={cn("glass flex min-h-9 items-center gap-1.5 rounded-full px-3", assist.enabled && "text-primary")}>
              <Sparkles className="size-3.5" /> {assist.enabled ? "Live advice on" : "Live advice"}
            </button>
          </div>
        )}
      </div>

      <div className="space-y-3 border-t border-overlay-foreground/10 p-3">
        {phase === "framing" && (
          <>
            <p className="text-center text-[11px] text-overlay-foreground/70">
              {assist.enabled ? "Live advice uses sampled frames and isn't saved. Only the photo you take is evidence." : "Line up the same view as before. Only the photo you take is saved."}
            </p>
            <div className="flex items-center justify-between px-6">
              <button type="button" onClick={() => fileGallery.current?.click()} className="glass grid size-12 place-items-center rounded-2xl" aria-label="Choose a photo"><Images className="size-5" /></button>
              <button type="button" onClick={shutter} disabled={live === "starting"} aria-label="Take the photo" className="grid size-[72px] place-items-center rounded-full border-4 border-overlay-foreground/90 disabled:opacity-60">
                <span className="size-[56px] rounded-full bg-overlay-foreground transition-transform active:scale-90" />
              </button>
              <span className="size-12" />
            </div>
          </>
        )}
        {phase === "saving" && (
          <p role="status" className="flex items-center justify-center gap-2 py-3 text-sm"><Loader2 className="size-4 animate-spin" /> Comparing with the photo from before…</p>
        )}
        {phase === "failed" && capture && (
          <div className="space-y-2" role="alert">
            <p className="text-sm">The photo didn't reach the server. {problem}</p>
            <button type="button" onClick={() => save(capture)} className="flex h-12 w-full items-center justify-center gap-2 rounded-2xl bg-primary text-sm font-medium text-primary-foreground"><RotateCcw className="size-4" /> Try again with this photo</button>
            <button type="button" onClick={retake} className="min-h-11 w-full rounded-xl text-xs text-overlay-foreground/80">Take a different photo</button>
          </div>
        )}
        {phase === "rejected" && (
          <div className="space-y-2" role="alert">
            <div className="flex items-center gap-2">
              {change ? <EvidenceBadge state={changeLabel[change.verdict].state} label={changeLabel[change.verdict].label} /> : <span className="text-sm font-medium text-failure">Photo not accepted</span>}
            </div>
            <p className="text-sm text-overlay-foreground/85">{change ? `${change.reason.trim().replace(/\.?$/, ".")} Nothing was checked on the equipment.` : problem}</p>
            <button type="button" onClick={retake} className="flex h-12 w-full items-center justify-center gap-2 rounded-2xl bg-primary text-sm font-medium text-primary-foreground"><CameraIcon className="size-4" /> Take a new photo</button>
          </div>
        )}
      </div>
      <input ref={fileCamera} type="file" accept="image/jpeg,image/png" capture="environment" className="hidden" onChange={onFile(coarse ? "Phone" : "RecordedFixture")} />
      <input ref={fileGallery} type="file" accept="image/jpeg,image/png" className="hidden" onChange={onFile("RecordedFixture")} />
    </div>
  );
}
