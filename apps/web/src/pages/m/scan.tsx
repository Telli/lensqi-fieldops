import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "wouter";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { X, Images, ChevronUp, Check, Loader2, AlertTriangle, Camera as CameraIcon, ArrowRight, Glasses, RotateCcw } from "lucide-react";
import { api, ApiError, isCancelled, newKey, type Assessment, type ChangeAnalysis, type Inspection, type ObservationResponse, type SegmentationGeometry } from "@/lib/api";
import { friendlyError, qk, useElapsed } from "@/lib/hooks";
import { useInspectionBundle } from "@/lib/inspection";
import { changeLabel, featureName, isPostRepairItem, observationState, sentence } from "@/lib/labels";
import { ArFrame } from "@/components/fo/ar-frame";
import { arTags, assessmentForPhoto, outlineOrder, targetConcept, usePhotoOutlines } from "@/lib/ar";
import { cameraError, connectCamera, takeStill } from "@/lib/camera";
import { clearCapture, loadCaptures, saveCapture, type SavedCapture } from "@/lib/captures";
import { SavedAs } from "@/components/fo/verify-camera";
import { WhyNotAccepted } from "@/components/fo/why-not-accepted";
import { fmtWhen } from "@/lib/labels";
import { useLiveAssist } from "@/lib/use-live-assist";
import { boxAge, LiveOverlay } from "@/components/fo/live-overlay";
import type { LiveObject } from "@/lib/live-tracker";
import { EvidenceBadge } from "@/components/fo/primitives";
import { LensQiMark } from "@/components/fo/ai";
import { aiSummary, assetMatch, nextMove } from "@/lib/ai";
import { cn } from "@/lib/utils";

type Step = { label: string; state: "todo" | "doing" | "done" | "skipped" };

/**
 * Immersive capture. Before a repair claim: save the photo, let Muse describe what it can and cannot see, and let SAM
 * outline the part. After a claim: save the photo and show the server's change check against the earlier photo.
 * Every result shown here comes back from the API; nothing is decided on the device.
 */
export default function Scan({ id }: { id: string }) {
  const { i, fixture, photoUrl, assessment: prior } = useInspectionBundle(id);
  const qc = useQueryClient();
  const [, nav] = useLocation();
  const video = useRef<HTMLVideoElement>(null);
  const fileCamera = useRef<HTMLInputElement>(null);
  const fileGallery = useRef<HTMLInputElement>(null);
  const [live, setLive] = useState<"starting" | "on" | "off">("starting");
  const [shot, setShot] = useState(false);
  const [busy, setBusy] = useState(false);
  const [steps, setSteps] = useState<Step[]>([]);
  const [drawer, setDrawer] = useState(false);
  const [result, setResult] = useState<{ evidenceId: string; assessment?: Assessment; outlines: SegmentationGeometry[]; change?: ChangeAnalysis | null; state: Inspection; saved: ObservationResponse; still?: boolean } | null>(null);
  // Captures that could not be uploaded, each kept under its own key so it can be sent again, not re-taken, and never
  // replaced by a later one: they stay until sent or discarded. `onDevice` false means the browser gave no storage and the
  // capture is only held on this screen.
  const [kept, setKept] = useState<(SavedCapture & { onDevice: boolean })[]>([]);
  useEffect(() => { void loadCaptures(id).then((cs) => setKept(cs.map((c) => ({ ...c, onDevice: true })))); }, [id]);
  const dropKept = (key: string) => { void clearCapture(id, key); setKept((ks) => ks.filter((k) => k.key !== key)); };
  const [selected, setSelected] = useState<string | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [cameraMessage, setCameraMessage] = useState<string | null>(null);
  const [cameraAttempt, setCameraAttempt] = useState(0);
  const processing = useRef(false);
  const inflight = useRef<AbortController | null>(null);
  // One shutter at a time, and a still that finishes after the camera closed is dropped rather than uploaded.
  const shuttering = useRef(false);
  const gone = useRef(false);
  const assist = useLiveAssist(video, live === "on" && !preview && !busy, `/v1/inspections/${encodeURIComponent(id)}`);
  const elapsed = useElapsed(busy);
  // A tracked box the technician tapped: its label and freshness take the status line while it is still tracked.
  const [picked, setPicked] = useState<LiveObject | null>(null);
  const pickedNow = picked && assist.objects.find((o) => o.id === picked.id) ? picked : null;

  // Closing the camera ends the model calls here, so nothing toasts or lands on the next screen. The server may still
  // finish a call that reached it; the inspection view shows that on its next refresh.
  useEffect(() => () => { gone.current = true; inflight.current?.abort(); }, []);
  const cancel = () => inflight.current?.abort();

  const afterRepair = !!i?.currentClaimId && (i.stage === "ReadyToVerify" || i.stage === "NeedsEvidence");
  const asked = i?.evidenceRequests?.find((r) => !r.answeredByPhotoId);
  const prompt = asked ? `Requested: ${asked.note}`
    : afterRepair ? "Take a new photo of the repaired connection"
    : prior?.nextAction === "RequestView" && prior.requestedView ? sentence(prior.requestedView)
    : "Point at the equipment and capture a wide view";

  useEffect(() => {
    if (preview) return;
    setLive("starting");
    setCameraMessage(null);
    if (!navigator.mediaDevices?.getUserMedia || !window.isSecureContext) {
      setLive("off"); setCameraMessage("Live preview isn't available here. Open your device camera or choose a photo."); return;
    }
    const v = video.current;
    const dispose = connectCamera(
      () => navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" }, width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false }),
      (s) => { if (v) { v.srcObject = s; setLive("on"); } },
      (e) => { setLive("off"); setCameraMessage(cameraError(e)); },
    );
    return () => { dispose(); if (v) v.srcObject = null; };
  }, [preview, cameraAttempt]);

  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview); }, [preview]);

  const setStep = (n: number, state: Step["state"]) => setSteps((s) => s.map((x, k) => (k === n ? { ...x, state } : x)));

  async function process(blob: Blob, name: string, source: "Phone" | "RecordedFixture", opts?: { still?: boolean; prior?: SavedCapture }) {
    if (!i || processing.current) return;
    processing.current = true;
    setBusy(true);
    setResult(null);
    setSelected(null);
    setDrawer(false);
    setPreview(URL.createObjectURL(blob));
    const plan: Step[] = afterRepair
      ? [{ label: "Saving photo", state: "doing" }, { label: "Comparing with before", state: "todo" }]
      : [{ label: "Saving photo", state: "doing" }, { label: "Observing", state: "todo" }, { label: "Matching asset", state: "todo" }, { label: "Outlining parts", state: "todo" }];
    setSteps(plan);
    const controller = new AbortController();
    inflight.current = controller;
    const signal = controller.signal;
    // One key per capture: "try again" after a dropped connection finds the photo the server already recorded.
    const key = opts?.prior?.key ?? newKey();
    const observedAt = opts?.prior?.observedAt ?? new Date().toISOString();
    let up: ObservationResponse | undefined;
    let state: Inspection | undefined;
    let assessment: Assessment | undefined;
    const outlines: SegmentationGeometry[] = [];
    try {
      up = await api.upload(id, blob, name, source, key, observedAt, { signal });
      setStep(0, "done");
      if (opts?.prior) dropKept(opts.prior.key);
      state = up.state;
      if (afterRepair) {
        setStep(1, "done");
      } else {
        setStep(1, "doing");
        try {
          const inv = await api.investigate(id, up.evidenceId, signal);
          assessment = assessmentForPhoto(inv.assessment, up.evidenceId);
          state = inv.state;
          setStep(1, "done");
        } catch (e) {
          if (isCancelled(e)) throw e;
          setStep(1, "skipped");
          toast.message("The AI couldn't look at this photo", { description: friendlyError(e) });
        }
        // The asset match is the AI's reading of the label against the inspection's asset; the server keeps both.
        setStep(2, assessment ? "done" : "skipped");
        setStep(3, "doing");
        let failed = 0;
        for (const concept of outlineOrder(fixture, assessment ?? prior)) {
          try {
            const seg = await api.segment(id, concept, up.evidenceId, signal);
            if (seg.segmentation.matched && seg.segmentation.photoEvidenceId === up.evidenceId) outlines.push(seg.segmentation);
            state = seg.state;
          } catch (e) {
            if (isCancelled(e)) throw e;
            failed++;
            toast.message(`Couldn't outline the ${concept}`, { description: friendlyError(e) });
          }
        }
        setStep(3, failed ? "skipped" : "done");
      }
      setResult({ evidenceId: up.evidenceId, assessment, outlines, change: up.changeAnalysis, state, saved: up, still: opts?.still });
      qc.setQueryData(qk.inspection(id), state);
    } catch (e) {
      if (isCancelled(e) && up && state) {
        // The photo is saved; what the AI managed before the cancel is shown, the rest is marked as not done.
        setSteps((st) => st.map((x) => (x.state === "doing" || x.state === "todo" ? { ...x, state: "skipped" } : x)));
        setResult({ evidenceId: up.evidenceId, assessment, outlines, change: up.changeAnalysis, state, saved: up, still: opts?.still });
        qc.setQueryData(qk.inspection(id), state);
      } else {
        setSteps([]);
        setPreview(null);
        if (!isCancelled(e)) toast.error(friendlyError(e));
        // Unless the server refused the photo, it may never have arrived: keep it with its key for "try again".
        const refused = e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 408 && e.status !== 429;
        if (!up && !refused) {
          const c: SavedCapture = { inspectionId: id, blob, name, source, key, observedAt, savedAt: Date.now() };
          const onDevice = await saveCapture(c);
          setKept((ks) => [...ks.filter((k) => k.key !== key), { ...c, onDevice }]);
        }
      }
    } finally {
      processing.current = false;
      setBusy(false);
      qc.invalidateQueries({ queryKey: qk.events(id) });
      qc.invalidateQueries({ queryKey: qk.artifacts(id) });
      qc.invalidateQueries({ queryKey: qk.inspections });
    }
  }

  function onShutter() {
    if (processing.current || busy) return;
    const v = video.current;
    if (live !== "on" || !v || !v.videoWidth) {
      fileCamera.current?.click();
      return;
    }
    setShot(true);
    setTimeout(() => setShot(false), 180);
    // A full-resolution still from the camera where the browser offers one, else the preview frame.
    if (shuttering.current) return;
    shuttering.current = true;
    void takeStill(v).then((r) => { if (r && !gone.current) void process(r.blob, `capture-${Date.now()}.jpg`, "Phone", { still: r.still }); }).finally(() => { shuttering.current = false; });
  }
  const firstKept = kept[0];
  const retryKept = () => { if (firstKept) void process(firstKept.blob, firstKept.name, firstKept.source as "Phone" | "RecordedFixture", { prior: firstKept }); };
  const discardKept = () => { if (firstKept) dropKept(firstKept.key); };

  // A file from the native camera on a touch device is a capture; on a desktop it is an imported file and says so.
  const coarse = typeof matchMedia !== "undefined" && matchMedia("(pointer: coarse)").matches;
  const onFile = (source: "Phone" | "RecordedFixture") => (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    e.target.value = "";
    if (f) process(f, f.name, source);
  };

  const next = afterRepair ? `/m/i/${id}/verify` : `/m/i/${id}`;
  // After a repair the outline comes from the server's change check, so read it back from the stored segmentations.
  const stored = usePhotoOutlines(id, result?.state, result?.evidenceId);
  const outlines = result?.outlines.length ? result.outlines : stored;
  const shown = result?.assessment ?? (afterRepair ? undefined : prior);
  const tags = result ? arTags(fixture, result.assessment, outlines, result.change) : [];
  const target = result && !afterRepair ? targetConcept(fixture, result.assessment) : undefined;
  const expected = fixture?.assets.find((a) => a.assetId === i?.assetId);
  const match = assetMatch(result?.assessment, i?.assetId);
  const name = expected?.displayName ?? i?.assetId;
  const rejected = result?.change && (result.change.verdict === "DuplicateImage" || result.change.verdict === "NoVisualChange");
  const findings = result?.assessment?.hypotheses.filter((h) => !isPostRepairItem(h.featureId)) ?? [];
  const last = i?.photos[i.photos.length - 1];
  const retake = () => { setPreview(null); setResult(null); setSteps([]); setSelected(null); };

  return (
    <div className="dark relative flex h-dvh flex-col overflow-hidden bg-overlay text-overlay-foreground md:h-full">
      {/* Feed: live camera, or the photo just taken with its outline */}
      <video ref={video} autoPlay playsInline muted className={cn("absolute inset-0 size-full object-contain", (live !== "on" || preview) && "hidden")} />
      {!preview && assist.enabled && <LiveOverlay {...assist} targetConcept={!afterRepair ? targetConcept(fixture, prior) : undefined} selectedId={picked?.id ?? null} onSelect={(o) => setPicked(picked?.id === o.id ? null : o)} />}
      <div className={cn("pointer-events-none absolute inset-0 bg-overlay transition-opacity", shot ? "opacity-80" : "opacity-0")} />
      {busy && <div className="pointer-events-none absolute inset-x-0 top-0 h-[9%] animate-scan bg-gradient-to-b from-transparent via-primary/25 to-transparent" />}

      {/* Reticle */}
      {!preview && live === "on" && !assist.objects.length && (
        <div className="pointer-events-none absolute left-1/2 top-[44%] size-20 -translate-x-1/2 -translate-y-1/2">
          {["left-0 top-0 border-l-2 border-t-2", "right-0 top-0 border-r-2 border-t-2", "left-0 bottom-0 border-l-2 border-b-2", "right-0 bottom-0 border-r-2 border-b-2"].map((c) => (
            <span key={c} className={cn("absolute size-4 rounded-[3px] border-overlay-foreground/80", c)} />
          ))}
          <span className="absolute left-1/2 top-1/2 size-1 -translate-x-1/2 -translate-y-1/2 rounded-full bg-primary" />
        </div>
      )}

      {/* Top controls */}
      <div className="relative flex shrink-0 items-center gap-2 p-3">
        <Link href={i?.currentClaimId ? `/m/i/${id}/verify` : `/m/i/${id}`} className="glass grid size-10 place-items-center rounded-full" aria-label="Close camera"><X className="size-5" /></Link>
        <div className="glass flex-1 truncate rounded-full px-3 py-2 text-xs">
          {!fixture ? "Loading…"
            : busy && !result ? <span className="opacity-80">{steps.find((s) => s.state === "doing")?.label ?? "Processing photo"}…{elapsed > 1 && <span className="ml-1 font-mono">{elapsed}s</span>}</span>
            : match === "mismatch" ? <span className="text-warning">▲ Looks like {result?.assessment?.assetId}, not {name}</span>
            : match === "match" ? <span><span className="text-success">●</span> {name} · AI match</span>
            : match === "unseen" ? <span className="text-warning">▲ Can't confirm {name} in this photo</span>
            : assetMatch(shown, i?.assetId) === "match" ? <span><span className="text-success">●</span> {name} · matched earlier</span>
            : <span className="opacity-80">{expected?.displayName ?? i?.assetId} · from asset label</span>}
        </div>
        {busy && !result
          ? <button type="button" onClick={cancel} className="glass flex h-10 items-center rounded-full px-3 text-[11px]">Cancel</button>
          : <Link href={`/m/i/${id}/glasses`} className="glass flex h-10 items-center gap-1.5 rounded-full px-3 text-[11px]" aria-label="Glasses view"><Glasses className="size-4" /> Glasses</Link>}
      </div>

      <div className={cn("relative min-h-0 flex-1", preview && "overflow-y-auto")}>
        {!preview && live === "off" && <div className="grid h-full place-items-center px-8 text-center text-sm text-overlay-foreground/70"><div>
          <CameraIcon className="mx-auto mb-3 size-10 opacity-60" /><p role="status">{cameraMessage}</p>
          <button type="button" onClick={() => setCameraAttempt((n) => n + 1)} className="mt-4 min-h-11 rounded-xl border border-overlay-foreground/30 px-4 text-overlay-foreground">Try camera again</button>
        </div></div>}
        {preview && <ArFrame src={result ? photoUrl(result.evidenceId) ?? preview : preview} outlines={outlines} tags={tags} selected={selected} onSelect={setSelected}
          guide={target ? { concept: target, label: `Frame this part next · ${sentence(result?.assessment?.requestedView) || target}` } : null}
          className="w-full" imgClassName="max-h-[45dvh] object-contain" />}
        {!preview && live === "starting" && <p role="status" className="absolute inset-x-0 top-1/2 flex items-center justify-center gap-2 text-sm"><Loader2 className="size-4 animate-spin" /> Starting camera…</p>}
        {firstKept && !preview && !busy && (
          <div className="glass absolute inset-x-3 bottom-3 space-y-2 rounded-2xl p-3 text-xs" role="status" data-kept-capture>
            <p>A photo from {fmtWhen(firstKept.observedAt)} didn't finish uploading{kept.length > 1 ? ` (${kept.length - 1} more waiting)` : ""}. {firstKept.onDevice ? "It's still on this device." : "It's held on this screen only and goes if you leave."}</p>
            <div className="flex gap-2">
              <button type="button" onClick={retryKept} className="flex h-10 flex-1 items-center justify-center gap-1.5 rounded-xl bg-primary text-primary-foreground"><RotateCcw className="size-3.5" /> Try again with this photo</button>
              <button type="button" onClick={discardKept} className="h-10 rounded-xl border border-overlay-foreground/30 px-3">Discard</button>
            </div>
          </div>
        )}
      </div>

      {/* Bottom: LensQi's prompt and the shutter, or what LensQi sees in the photo just taken */}
      <div className="relative shrink-0 bg-gradient-to-t from-black/75 via-black/35 to-transparent px-3 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))]">
        {!result ? (
          <>
            {!preview && live === "on" && <div className="glass mb-3 rounded-2xl px-3 py-2">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs text-primary" role="status">{pickedNow ? `${pickedNow.label} · AI detected${(() => { const a = boxAge(assist.detectedAt, pickedNow.id, performance.now()); return a !== null ? ` · seen ${a}s ago` : ""; })()}` : assist.enabled ? assist.status : "Live camera assistance"}</span>
                <button type="button" className="min-h-11 rounded-xl border border-primary/50 px-3 text-xs" onClick={() => assist.setEnabled(!assist.enabled)}>{assist.enabled ? "Pause live assist" : "Start live assist"}</button>
              </div>
              <p className="text-[11px] opacity-70">{assist.enabled ? `${assist.fps} tracking updates/s · AI guidance${assist.updatedAt ? ` from ${Math.max(0, Math.round((performance.now() - assist.updatedAt) / 1000))}s ago` : ' updating'}` : "Sampled frames go to AI for live guidance. Only shutter photos are saved to the inspection."}</p>
              {assist.enabled && assist.telemetry && (
                <div className="mt-1.5 flex flex-wrap items-center gap-1.5 text-[10px]" aria-label="Camera quality telemetry">
                  {[
                    { label: "Light", ok: assist.telemetry.brightness >= 35, state: assist.telemetry.brightness >= 35 ? "OK" : "Low" },
                    { label: "Focus", ok: assist.telemetry.sharpness >= 3, state: assist.telemetry.sharpness >= 3 ? "Sharp" : "Soft" },
                    { label: "Stability", ok: assist.telemetry.motion <= 32, state: assist.telemetry.motion <= 32 ? "Steady" : "Moving" },
                  ].map((m) => (
                    <span key={m.label} className={cn("inline-flex items-center gap-1 rounded-full border px-2 py-0.5", m.ok ? "border-overlay-foreground/15 text-overlay-foreground/80" : "border-warning/50 bg-warning/15 text-warning")}>
                      <span className={cn("size-1.5 rounded-full", m.ok ? "bg-success" : "bg-warning")} />
                      {m.label}: {m.state}
                    </span>
                  ))}
                </div>
              )}
            </div>}
            <div className="glass mx-auto mb-5 flex max-w-sm items-start gap-2.5 rounded-2xl px-3 py-2.5">
              <LensQiMark className="size-6" />
              <div className="min-w-0 flex-1">
                <div className="text-sm leading-snug">{busy ? (afterRepair ? "Comparing with the photo from before…" : "Looking at your photo…") : assist.enabled && live === "on" ? assist.recommendation : prompt}</div>
                {!busy && assist.enabled && live === "on" && assist.screenText && <p className="mt-1 line-clamp-3 text-xs opacity-70">On screen: “{assist.screenText}”</p>}
                {steps.length > 0 && <StepLine steps={steps} />}
              </div>
            </div>
            <div className="flex items-center justify-between px-5">
              <button onClick={() => fileGallery.current?.click()} disabled={busy} className="glass grid size-12 place-items-center rounded-2xl" aria-label="Choose a photo">
                <Images className="size-5" />
              </button>
              <button onClick={onShutter} disabled={busy || !i || live === "starting"} aria-label="Capture" className="grid size-[76px] place-items-center rounded-full border-4 border-overlay-foreground/90 disabled:opacity-60">
                <span className={cn("size-[60px] rounded-full bg-overlay-foreground transition-transform active:scale-90", busy && "animate-pulse bg-primary")} />
              </button>
              {last && photoUrl(last.evidenceId) ? (
                <Link href={`/m/i/${id}`} aria-label={`${i?.photos.length} photos`} className="relative size-12 overflow-hidden rounded-2xl border-2 border-overlay-foreground/70">
                  <img src={photoUrl(last.evidenceId)} alt="" className="size-full object-cover" />
                  <span className="absolute right-0.5 top-0.5 rounded-full bg-black/70 px-1 text-[9px] font-semibold tabular-nums">{i?.photos.length}</span>
                </Link>
              ) : <span className="size-12" />}
            </div>
          </>
        ) : (
          <div className="glass max-h-[58dvh] animate-rise overflow-y-auto rounded-3xl border border-border p-4">
            <div className="flex items-start gap-3">
              <LensQiMark />
              <div className="min-w-0 flex-1 space-y-1">
                <p className="text-[13px] leading-relaxed opacity-80">{aiSummary(result.state, result.assessment)}</p>
                <h2 className={cn("text-lg font-semibold leading-snug", rejected && "text-failure")}>
                  {rejected ? "Photo not accepted" : afterRepair ? "Photo accepted" : nextMove(result.state, result.assessment)?.title}
                </h2>
                {steps.some((st) => st.state === "skipped") && <StepLine steps={steps} />}
                <SavedAs r={result.saved} still={result.still} />
                {rejected && result.change && (
                  <div className="mt-2">
                    <WhyNotAccepted change={result.change} yours={photoUrl(result.evidenceId) ?? preview ?? undefined} reference={result.change.referencePhotoEvidenceId ? photoUrl(result.change.referencePhotoEvidenceId) : undefined} />
                  </div>
                )}
              </div>
            </div>
            {(findings.length > 0 || result.change) && (
              <button onClick={() => setDrawer(!drawer)} className="mt-3 flex w-full items-center justify-between text-xs opacity-80">
                {drawer ? "Hide findings" : "All findings"}
                <ChevronUp className={cn("size-4 transition-transform", !drawer && "rotate-180")} />
              </button>
            )}
            {drawer && (
              <div className="mt-2 animate-rise space-y-2 border-t border-border pt-3 text-xs">
                {result.change && (
                  <div><EvidenceBadge state={changeLabel[result.change.verdict].state} label={changeLabel[result.change.verdict].label} /><p className="mt-1.5 opacity-80">{result.change.reason}</p></div>
                )}
                {findings.map((h) => (
                  <div key={h.featureId} className="flex gap-2">
                    <span className={cn("mt-1 size-1.5 shrink-0 rounded-full", h.status === "Observed" ? "bg-primary" : h.status === "Conflicting" ? "bg-failure" : "bg-warning")} />
                    <span><span className="font-medium">{featureName(h.featureId)}</span> <span className="opacity-70">· {observationState[h.status].label}</span><br /><span className="opacity-80">{h.reason}</span></span>
                  </div>
                ))}
              </div>
            )}
            <button onClick={() => (rejected || target ? retake() : nav(next))}
              className="mt-4 flex h-12 w-full items-center justify-center gap-2 rounded-2xl bg-primary text-sm font-medium text-primary-foreground">
              {rejected || target ? <><CameraIcon className="size-4" /> {target ? "Capture the requested view" : "Take another photo"}</> : <>{afterRepair ? "Continue to verification" : "Continue"} <ArrowRight className="size-4" /></>}
            </button>
            {!rejected && !target && <button type="button" onClick={retake} className="mt-1 min-h-11 w-full rounded-xl text-xs text-overlay-foreground/80">Take another photo</button>}
          </div>
        )}
      </div>
      <input ref={fileCamera} type="file" accept="image/jpeg,image/png" capture="environment" className="hidden" onChange={onFile(coarse ? "Phone" : "RecordedFixture")} />
      <input ref={fileGallery} type="file" accept="image/jpeg,image/png" className="hidden" onChange={onFile("RecordedFixture")} />
    </div>
  );
}

/** The server's work on the photo, step by step, in one quiet line. */
function StepLine({ steps }: { steps: Step[] }) {
  if (!steps.length) return null;
  return (
    <div className="mt-1.5 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-[11px]">
      {steps.map((st) => (
        <span key={st.label} className={cn("flex items-center gap-1", st.state === "done" ? "text-primary" : st.state === "doing" ? "text-overlay-foreground" : "text-overlay-foreground/50")}>
          {st.state === "done" ? <Check className="size-3" /> : st.state === "doing" ? <Loader2 className="size-3 animate-spin" /> : st.state === "skipped" ? <AlertTriangle className="size-3 text-warning" /> : null}
          {st.label}
        </span>
      ))}
    </div>
  );
}
