import { useEffect, useRef, useState } from "react";
import { Link } from "wouter";
import { X, Volume2, VolumeX, Glasses as GlassesIcon, Loader2 } from "lucide-react";
import { ArFrame } from "@/components/fo/ar-frame";
import { arTags, nextStep, targetConcept, usePhotoOutlines } from "@/lib/ar";
import { useQueryClient } from "@tanstack/react-query";
import { lastPhoto, qk, useInspectionBundle } from "@/lib/inspection";
import { fmtWhen, stageLabel } from "@/lib/labels";
import { cn } from "@/lib/utils";

const originLabel = (o?: string) =>
  o === "Glasses" ? "Ray-Ban Meta glasses" : o === "MockWearables" ? "Mock glasses (Meta Mock Device Kit)" : o === "Phone" ? "Phone camera" : o ? "Imported photo" : "";

/**
 * The hands-free view: one instruction at a time, the latest photo with the AI's outlines, spoken aloud.
 * It follows the live inspection, so photos taken through the Android app's glasses mode appear here as they land.
 * The overlay is screen-fixed, not world-locked: it labels the photo it was computed from.
 */
export default function GlassesView({ id }: { id: string }) {
  const { i, fixture, assessment, photoUrl } = useInspectionBundle(id);
  const photo = lastPhoto(i);
  const outlines = usePhotoOutlines(id, i, photo?.evidenceId);
  const change = i?.changeAnalyses.find((c) => c.photoEvidenceId === photo?.evidenceId);
  const fromAssessment = !i?.currentClaimId ? assessment : undefined;
  const tags = arTags(fixture, fromAssessment, outlines, change);
  const target = targetConcept(fixture, fromAssessment);
  const step = nextStep(i, assessment);
  const asset = fixture?.assets.find((a) => a.assetId === i?.assetId);
  const ok = i?.stage === "VerifiedComplete";
  const [voice, setVoice] = useState(() => typeof speechSynthesis !== "undefined");
  const spoken = useRef("");
  const qc = useQueryClient();

  // Follow the live inspection: photos from the glasses land on the server, not in this page.
  useEffect(() => {
    const t = setInterval(() => [qk.inspection(id), qk.events(id), qk.artifacts(id)].forEach((k) => qc.invalidateQueries({ queryKey: k })), 4000);
    return () => clearInterval(t);
  }, [id, qc]);

  useEffect(() => {
    if (!voice || typeof speechSynthesis === "undefined" || !i || spoken.current === step.text) return;
    spoken.current = step.text;
    speechSynthesis.cancel();
    speechSynthesis.speak(new SpeechSynthesisUtterance([step.text, step.hint].filter(Boolean).join(". ")));
  }, [voice, i, step.text, step.hint]);

  if (!i) return <div className="grid h-dvh place-items-center bg-overlay"><Loader2 className="animate-spin text-overlay-foreground/60" /></div>;

  return (
    <div className="relative flex h-dvh flex-col overflow-hidden bg-overlay text-overlay-foreground md:h-full">
      <div className="flex items-center gap-2 p-3">
        <Link href={`/m/i/${id}/scan`} className="glass grid size-10 place-items-center rounded-full" aria-label="Exit glasses view"><X className="size-5" /></Link>
        <div className="glass flex flex-1 items-center gap-2 truncate rounded-full px-3 py-2 text-xs"><GlassesIcon className="size-4 text-primary" /> Hands-free view</div>
        <button onClick={() => { setVoice(!voice); spoken.current = ""; if (voice) speechSynthesis.cancel(); }} className="glass grid size-10 place-items-center rounded-full" aria-label={voice ? "Mute voice" : "Speak instructions"}>
          {voice ? <Volume2 className="size-5 text-primary" /> : <VolumeX className="size-5" />}
        </button>
      </div>

      {/* Heads-up panel: what the wearer needs, nothing else */}
      <div className="mx-4 flex flex-1 flex-col gap-3 overflow-y-auto rounded-[2rem] border border-overlay-foreground/15 p-4">
        <div className="flex items-center justify-between text-xs">
          <span className="font-mono text-primary">{asset?.displayName ?? i.assetId}</span>
          <span className="flex items-center gap-1.5"><span className={cn("size-2 rounded-full", ok ? "bg-success" : i.stage === "VerificationFailed" ? "bg-failure" : "bg-warning")} />{stageLabel[i.stage]}</span>
        </div>
        {photo && photoUrl(photo.evidenceId) ? (
          <ArFrame src={photoUrl(photo.evidenceId)!} outlines={outlines} tags={tags} guide={target ? { concept: target, label: "Target region" } : null} className="rounded-xl" />
        ) : (
          <div className="grid aspect-[4/3] place-items-center rounded-xl border border-dashed border-overlay-foreground/20 text-xs opacity-60">No photo yet</div>
        )}
        {photo && <div className="text-[11px] opacity-60">Latest photo · {originLabel(photo.origin)} · {fmtWhen(photo.observedAt)}</div>}
        <div className="mt-auto text-center">
          <div className="text-xl font-medium leading-snug">{step.text}</div>
          {step.hint && <div className="mt-1 text-sm opacity-70">{step.hint}</div>}
        </div>
      </div>

      <div className="space-y-1 p-4 text-center text-[11px] opacity-60">
        <div>Photos from Ray-Ban Meta glasses arrive through the LensQi Android app's glasses mode and appear here as they upload.</div>
        <div>Outlines label the photo they were computed from; they do not track the live view.</div>
      </div>
    </div>
  );
}
