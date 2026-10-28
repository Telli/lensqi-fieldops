import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "wouter";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { X, Images, ChevronUp, Check, Loader2, AlertTriangle, Camera as CameraIcon, ArrowRight, Glasses } from "lucide-react";
import { api, type Assessment, type ChangeAnalysis, type Inspection, type SegmentationGeometry } from "@/lib/api";
import { friendlyError, qk } from "@/lib/hooks";
import { useInspectionBundle } from "@/lib/inspection";
import { changeLabel, featureName, isPostRepairItem, observationState, sentence } from "@/lib/labels";
import { ArFrame } from "@/components/fo/ar-frame";
import { arTags, outlineOrder, targetConcept, usePhotoOutlines } from "@/lib/ar";
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
  const [result, setResult] = useState<{ evidenceId: string; assessment?: Assessment; outlines: SegmentationGeometry[]; change?: ChangeAnalysis | null; state: Inspection } | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [preview, setPreview] = useState<string | null>(null);

  const afterRepair = !!i?.currentClaimId && (i.stage === "ReadyToVerify" || i.stage === "NeedsEvidence");
  const asked = i?.evidenceRequests?.find((r) => !r.answeredByPhotoId);
  const prompt = asked ? `Requested: ${asked.note}`
    : afterRepair ? "Take a new photo of the repaired connection"
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
      : [{ label: "Saving photo", state: "doing" }, { label: "Observing", state: "todo" }, { label: "Matching asset", state: "todo" }, { label: "Outlining parts", state: "todo" }];
    setSteps(plan);
    try {
      const up = await api.upload(id, blob, name, source);
      setStep(0, "done");
      let state = up.state;
      let assessment: Assessment | undefined;
      const outlines: SegmentationGeometry[] = [];
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
        // The asset match is the AI's reading of the label against the inspection's asset; the server keeps both.
        setStep(2, assessment ? "done" : "skipped");
        setStep(3, "doing");
        let failed = 0;
        for (const concept of outlineOrder(fixture, assessment ?? prior)) {
          try {
            const seg = await api.segment(id, concept);
            if (seg.segmentation.matched) outlines.push(seg.segmentation);
            state = seg.state;
          } catch (e) {
            failed++;
            toast.message(`Couldn't outline the ${concept}`, { description: friendlyError(e) });
          }
        }
        setStep(3, failed ? "skipped" : "done");
      }
      setResult({ evidenceId: up.evidenceId, assessment, outlines, change: up.changeAnalysis, state });
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

  return (
    <div className="relative h-dvh overflow-hidden bg-overlay text-overlay-foreground md:h-full">
      {/* Feed: live camera, or the photo just taken with its outline */}
      <video ref={video} autoPlay playsInline muted className={cn("absolute inset-0 size-full object-cover", (live !== "on" || preview) && "hidden")} />
      {preview && (
        <div className="absolute inset-x-0 top-14 bottom-56 grid place-items-center">
          <ArFrame src={result ? photoUrl(result.evidenceId) ?? preview : preview} outlines={outlines} tags={tags} selected={selected} onSelect={setSelected}
            guide={target ? { concept: target, label: `Target region · ${sentence(result?.assessment?.requestedView) || target}` } : null} className="w-full" />
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
          {!fixture ? "Loading…"
            : busy && !result ? <span className="opacity-80">Matching asset…</span>
            : match === "mismatch" ? <span className="text-warning">▲ Looks like {result?.assessment?.assetId}, not {name}</span>
            : match === "match" ? <span><span className="text-success">●</span> {name} · matches site record</span>
            : match === "unseen" ? <span className="text-warning">▲ Can't confirm {name} in this photo</span>
            : assetMatch(shown, i?.assetId) === "match" ? <span><span className="text-success">●</span> {name} · matched earlier</span>
            : <span className="opacity-80">{expected?.displayName ?? i?.assetId} · from asset label</span>}
        </div>
        <Link href={`/m/i/${id}/glasses`} className="glass flex h-10 items-center gap-1.5 rounded-full px-3 text-[11px]" aria-label="Glasses view"><Glasses className="size-4" /> Glasses</Link>
      </div>

      {/* Bottom: LensQi's prompt and the shutter, or what LensQi sees in the photo just taken */}
      <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/75 via-black/35 to-transparent px-3 pt-16 pb-[max(1rem,env(safe-area-inset-bottom))]">
        {!result ? (
          <>
            <div className="glass mx-auto mb-5 flex max-w-sm items-start gap-2.5 rounded-2xl px-3 py-2.5">
              <LensQiMark className="size-6" />
              <div className="min-w-0 flex-1">
                <div className="text-sm leading-snug">{busy ? (afterRepair ? "Comparing with the photo from before…" : "Looking at your photo…") : prompt}</div>
                {steps.length > 0 && <StepLine steps={steps} />}
              </div>
            </div>
            <div className="flex items-center justify-between px-5">
              <button onClick={() => fileGallery.current?.click()} disabled={busy} className="glass grid size-12 place-items-center rounded-2xl" aria-label="Choose a photo">
                <Images className="size-5" />
              </button>
              <button onClick={onShutter} disabled={busy || !i} aria-label="Capture" className="grid size-[76px] place-items-center rounded-full border-4 border-overlay-foreground/90 disabled:opacity-60">
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
              </div>
            </div>
            {(findings.length > 0 || result.change) && (
              <button onClick={() => setDrawer(!drawer)} className="mt-3 flex w-full items-center justify-between text-xs opacity-80">
                {drawer ? "Hide details" : tags.length ? "Details · tap a label on the photo for the reason" : "Details"}
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
            <button onClick={() => (rejected ? (setPreview(null), setResult(null), setSteps([])) : nav(next))}
              className="mt-4 flex h-12 w-full items-center justify-center gap-2 rounded-2xl bg-primary text-sm font-medium text-primary-foreground">
              {rejected ? <><CameraIcon className="size-4" /> Take another photo</> : <>{afterRepair ? "Continue to verification" : "Continue"} <ArrowRight className="size-4" /></>}
            </button>
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
