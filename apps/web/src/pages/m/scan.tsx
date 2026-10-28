import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "wouter";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { X, Images, ChevronUp, Check, Loader2, AlertTriangle, Camera as CameraIcon, ArrowRight } from "lucide-react";
import { api, type Assessment, type ChangeAnalysis, type Inspection, type SegmentationGeometry } from "@/lib/api";
import { friendlyError, qk } from "@/lib/hooks";
import { useInspectionBundle } from "@/lib/inspection";
import { changeLabel, featureName, isPostRepairItem, observationState, sentence } from "@/lib/labels";
import { EvidencePhoto } from "@/components/fo/evidence-photo";
import { EvidenceBadge } from "@/components/fo/primitives";
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
  const [result, setResult] = useState<{ evidenceId: string; assessment?: Assessment; geometry?: SegmentationGeometry | null; change?: ChangeAnalysis | null; state: Inspection } | null>(null);
  const [preview, setPreview] = useState<string | null>(null);

  const afterRepair = !!i?.currentClaimId && (i.stage === "ReadyToVerify" || i.stage === "NeedsEvidence");
  const prompt = afterRepair
    ? "Take a new photo of the repaired connection"
    : prior?.nextAction === "RequestView" && prior.requestedView ? sentence(prior.requestedView)
    : "Point at the equipment and capture a wide view";

  useEffect(() => {
    let stream: MediaStream | undefined;
    if (!navigator.mediaDevices?.getUserMedia || !window.isSecureContext) { setLive("off"); return; }
    navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" }, width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false })
      .then((s) => { stream = s; if (video.current) { video.current.srcObject = s; setLive("on"); } })
      .catch(() => setLive("off"));
    return () => stream?.getTracks().forEach((t) => t.stop());
  }, []);

  const setStep = (n: number, state: Step["state"]) => setSteps((s) => s.map((x, k) => (k === n ? { ...x, state } : x)));

  async function process(blob: Blob, name: string, source: "Phone" | "RecordedFixture") {
    if (!i) return;
    setBusy(true);
    setResult(null);
    setPreview(URL.createObjectURL(blob));
    const plan: Step[] = afterRepair
      ? [{ label: "Saving photo", state: "doing" }, { label: "Comparing with before", state: "todo" }]
      : [{ label: "Saving photo", state: "doing" }, { label: "Observing", state: "todo" }, { label: "Outlining the part", state: "todo" }];
    setSteps(plan);
    try {
      const up = await api.upload(id, blob, name, source);
      setStep(0, "done");
      let state = up.state;
      let assessment: Assessment | undefined;
      let geometry: SegmentationGeometry | null | undefined;
      if (afterRepair) {
        setStep(1, "done");
      } else {
        setStep(1, "doing");
        try {
          const inv = await api.investigate(id);
          assessment = inv.assessment;
          state = inv.state;
          setStep(1, "done");
        } catch (e) {
          setStep(1, "skipped");
          toast.message("The AI couldn't look at this photo", { description: friendlyError(e) });
        }
        setStep(2, "doing");
        const prompts = (fixture?.visualRequirements ?? []).map((v) => v.segmentationPrompt).filter((p): p is string => !!p);
        const concept = assessment?.requestedSegmentationConcept ?? prompts[prompts.length - 1];
        try {
          const seg = await api.segment(id, concept);
          geometry = seg.segmentation;
          state = seg.state;
          setStep(2, "done");
        } catch (e) {
          setStep(2, "skipped");
          toast.message("Couldn't outline the part", { description: friendlyError(e) });
        }
      }
      setResult({ evidenceId: up.evidenceId, assessment, geometry, change: up.changeAnalysis, state });
      setDrawer(true);
      qc.setQueryData(qk.inspection(id), state);
    } catch (e) {
      setSteps([]);
      setPreview(null);
      toast.error(friendlyError(e));
    } finally {
      setBusy(false);
      qc.invalidateQueries({ queryKey: qk.events(id) });
      qc.invalidateQueries({ queryKey: qk.artifacts(id) });
      qc.invalidateQueries({ queryKey: qk.inspections });
    }
  }

  function onShutter() {
    const v = video.current;
    if (live !== "on" || !v || !v.videoWidth) {
      fileCamera.current?.click();
      return;
    }
    setShot(true);
    setTimeout(() => setShot(false), 180);
    const c = document.createElement("canvas");
    c.width = v.videoWidth; c.height = v.videoHeight;
    c.getContext("2d")!.drawImage(v, 0, 0);
    c.toBlob((b) => b && process(b, `capture-${Date.now()}.jpg`, "Phone"), "image/jpeg", 0.92);
  }

  // A file from the native camera on a touch device is a capture; on a desktop it is an imported file and says so.
  const coarse = typeof matchMedia !== "undefined" && matchMedia("(pointer: coarse)").matches;
  const onFile = (source: "Phone" | "RecordedFixture") => (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    e.target.value = "";
    if (f) process(f, f.name, source);
  };

  const next = afterRepair ? `/m/i/${id}/verify` : `/m/i/${id}`;
  const rejected = result?.change && (result.change.verdict === "DuplicateImage" || result.change.verdict === "NoVisualChange");

  return (
    <div className="relative h-dvh overflow-hidden bg-overlay text-overlay-foreground md:h-full">
      {/* Feed: live camera, or the photo just taken with its outline */}
      <video ref={video} autoPlay playsInline muted className={cn("absolute inset-0 size-full object-cover", (live !== "on" || preview) && "hidden")} />
      {preview && (
        <div className="absolute inset-x-0 top-14 bottom-0 grid place-items-start">
          <EvidencePhoto src={result ? photoUrl(result.evidenceId) ?? preview : preview} geometry={result?.geometry} className="w-full" />
        </div>
      )}
      {!preview && live === "off" && (
        <div className="absolute inset-0 grid place-items-center px-10 text-center text-sm text-overlay-foreground/70">
          <div>
            <CameraIcon className="mx-auto mb-3 size-10 opacity-60" />
            Tap the shutter to open your camera, or pick a photo you already took.
          </div>
        </div>
      )}
      <div className={cn("absolute inset-0 bg-overlay transition-opacity", shot ? "opacity-80" : "opacity-0")} />
      {busy && <div className="pointer-events-none absolute inset-x-0 top-0 h-[9%] animate-scan bg-gradient-to-b from-transparent via-primary/25 to-transparent" />}

      {/* Reticle */}
      {!preview && live === "on" && (
        <div className="pointer-events-none absolute left-1/2 top-[44%] size-20 -translate-x-1/2 -translate-y-1/2">
          {["left-0 top-0 border-l-2 border-t-2", "right-0 top-0 border-r-2 border-t-2", "left-0 bottom-0 border-l-2 border-b-2", "right-0 bottom-0 border-r-2 border-b-2"].map((c) => (
            <span key={c} className={cn("absolute size-4 rounded-[3px] border-overlay-foreground/80", c)} />
          ))}
          <span className="absolute left-1/2 top-1/2 size-1 -translate-x-1/2 -translate-y-1/2 rounded-full bg-primary" />
        </div>
      )}

      {/* Top controls */}
      <div className="absolute inset-x-0 top-0 flex items-center gap-2 p-3">
        <Link href={i?.currentClaimId ? `/m/i/${id}/verify` : `/m/i/${id}`} className="glass grid size-10 place-items-center rounded-full" aria-label="Close camera"><X className="size-5" /></Link>
        <div className="glass flex-1 truncate rounded-full px-3 py-2 text-xs">
          {fixture ? <span><span className="text-success">●</span> {fixture.assets.find((a) => a.assetId === i?.assetId)?.displayName ?? i?.assetId} · from asset label</span> : "Loading…"}
        </div>
      </div>
      {result?.geometry?.matched && (
        <div className="absolute right-3 top-16">
          <span className="glass flex items-center gap-1.5 rounded-md border border-primary/50 px-2 py-1 text-[10px] font-medium text-primary animate-rise">AI outline · {result.geometry.concept}</span>
        </div>
      )}

      {/* Investigation panel */}
      <div className="absolute inset-x-0 bottom-0 p-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]">
        <div className="glass max-h-[58dvh] overflow-y-auto rounded-2xl border border-border p-3.5">
          <button onClick={() => setDrawer(!drawer)} className="flex w-full items-center justify-between gap-3 text-left">
            <div className="text-sm font-medium">{result ? (rejected ? "Photo not accepted" : afterRepair ? "Photo accepted" : "Here's what LensQi sees") : prompt}</div>
            <ChevronUp className={cn("size-4 shrink-0 transition-transform", drawer && "rotate-180")} />
          </button>
          {steps.length > 0 && (
            <div className="mt-2.5 flex flex-wrap items-center gap-1.5 text-[11px]">
              {steps.map((st, k) => (
                <span key={st.label} className="flex items-center gap-1.5">
                  {k > 0 && <span className="h-px w-3 bg-overlay-foreground/30" />}
                  <span className={cn("flex items-center gap-1", st.state === "done" ? "text-primary" : st.state === "doing" ? "text-overlay-foreground" : "text-overlay-foreground/50")}>
                    {st.state === "done" ? <Check className="size-3" /> : st.state === "doing" ? <Loader2 className="size-3 animate-spin" /> : st.state === "skipped" ? <AlertTriangle className="size-3 text-warning" /> : null}
                    {st.label}
                  </span>
                </span>
              ))}
            </div>
          )}
          {drawer && result && (
            <div className="mt-3 animate-rise space-y-2 border-t border-border pt-3 text-xs">
              {result.change && (
                <div className={cn("rounded-lg border p-2.5", rejected ? "border-failure/40 bg-failure/10" : "border-border")}>
                  <EvidenceBadge state={changeLabel[result.change.verdict].state} label={changeLabel[result.change.verdict].label} />
                  <p className="mt-1.5">{changeLabel[result.change.verdict].detail}</p>
                </div>
              )}
              {result.assessment?.hypotheses.filter((h) => !isPostRepairItem(h.featureId)).map((h) => (
                <div key={h.featureId} className="flex gap-2">
                  <span className={cn("mt-1 size-1.5 shrink-0 rounded-full", h.status === "Observed" ? "bg-primary" : h.status === "Conflicting" ? "bg-failure" : "bg-warning")} />
                  <span><span className="font-medium">{featureName(h.featureId)}</span> <span className="opacity-70">· {observationState[h.status].label}</span><br /><span className="opacity-80">{h.reason}</span></span>
                </div>
              ))}
              {result.assessment?.nextAction === "RequestView" && result.assessment.requestedView && (
                <div className="flex gap-2 text-warning"><AlertTriangle className="mt-0.5 size-3.5 shrink-0" />Next: {sentence(result.assessment.requestedView)}</div>
              )}
              <button onClick={() => (rejected ? (setPreview(null), setResult(null), setSteps([])) : nav(next))}
                className="mt-1 flex h-11 w-full items-center justify-center gap-2 rounded-xl bg-primary text-sm font-medium text-primary-foreground">
                {rejected ? "Take another photo" : afterRepair ? "Continue to verification" : "Continue investigation"} <ArrowRight className="size-4" />
              </button>
            </div>
          )}
          {!result && (
            <div className="mt-3 flex items-center justify-between">
              <button onClick={() => fileGallery.current?.click()} disabled={busy} className="grid size-11 place-items-center rounded-xl border border-border" aria-label="Choose a photo">
                <Images className="size-5" />
              </button>
              <button onClick={onShutter} disabled={busy || !i} aria-label="Capture" className="grid size-[72px] place-items-center rounded-full border-4 border-primary disabled:opacity-60">
                <span className={cn("size-14 rounded-full bg-primary transition-transform active:scale-90", busy && "animate-pulse")} />
              </button>
              <div className="w-11 text-center text-[11px] opacity-70">{i?.photos.length ?? 0}<br />photos</div>
            </div>
          )}
        </div>
      </div>
      <input ref={fileCamera} type="file" accept="image/jpeg,image/png" capture="environment" className="hidden" onChange={onFile(coarse ? "Phone" : "RecordedFixture")} />
      <input ref={fileGallery} type="file" accept="image/jpeg,image/png" className="hidden" onChange={onFile("RecordedFixture")} />
    </div>
  );
}
