import { useQueries } from "@tanstack/react-query";
import { api, type Assessment, type Box, type ChangeAnalysis, type Fixture, type Inspection, type SegmentationGeometry } from "./api";
import { featureName, sentence } from "./labels";

export type Tone = "primary" | "warning" | "failure" | "success";

/** A label pinned to a part SAM outlined. Tags exist only where a real outline exists; nothing is placed by guesswork. */
export interface ArTag { concept: string; label: string; tone: Tone; detail: string; box: Box; cropFile?: string | null }

/** The newest matched outline per concept for one photo, read from the stored segmentation artifacts. */
export function usePhotoOutlines(id: string, inspection?: Inspection | null, photoEvidenceId?: string | null) {
  const latest = new Map<string, string>();
  for (const s of inspection?.segmentations ?? []) if (s.sourcePhotoEvidenceId === photoEvidenceId && s.matched) latest.set(s.concept, s.evidenceId);
  const ids = [...latest.values()];
  const results = useQueries({
    queries: ids.map((segId) => ({ queryKey: ["geometry", id, segId], queryFn: () => api.geometry(id, segId), staleTime: Infinity })),
  });
  return results.map((r) => r.data).filter((g): g is SegmentationGeometry => !!g?.matched && g.photoEvidenceId === photoEvidenceId);
}

/** The fixture's outline prompts, with the repair target last (the server compares later photos against the last outline). */
export function outlineOrder(fixture?: Fixture | null, assessment?: Assessment | null): string[] {
  const prompts = (fixture?.visualRequirements ?? []).map((v) => v.segmentationPrompt).filter((p): p is string => !!p);
  const target = assessment?.requestedSegmentationConcept && prompts.includes(assessment.requestedSegmentationConcept)
    ? assessment.requestedSegmentationConcept : prompts[prompts.length - 1];
  return [...prompts.filter((p) => p !== target), ...(target ? [target] : [])];
}

/** The concept the technician should frame next, when the AI asked for a better view of a part it outlined. */
export function targetConcept(fixture?: Fixture | null, assessment?: Assessment | null): string | undefined {
  if (!assessment || assessment.nextAction !== "RequestView") return undefined;
  if (assessment.requestedSegmentationConcept) return assessment.requestedSegmentationConcept;
  const unclear = assessment.hypotheses.find((h) => h.status !== "Observed");
  return fixture?.visualRequirements.find((v) => v.requirementId === unclear?.featureId)?.segmentationPrompt ?? undefined;
}

/** The assessment reduced to findings that cite this photo; undefined when none does. Other photos' findings never describe it. */
export function assessmentForPhoto(assessment?: Assessment | null, photoEvidenceId?: string | null): Assessment | undefined {
  if (!assessment || !photoEvidenceId) return undefined;
  const cited = assessment.hypotheses.filter((h) => h.evidenceIds.includes(photoEvidenceId));
  return cited.length ? { ...assessment, hypotheses: cited } : undefined;
}

const mainBox = (g: SegmentationGeometry): Box | undefined =>
  [...g.boxes].sort((a, b) => (b.confidence ?? 0) - (a.confidence ?? 0))[0] ?? g.masks[0];

/** Pins one tag per outline: the AI's finding for the matching checklist item, or the change verdict after a repair. */
export function arTags(fixture: Fixture | null | undefined, assessment: Assessment | null | undefined, outlines: SegmentationGeometry[], change?: ChangeAnalysis | null): ArTag[] {
  return outlines.flatMap((g): ArTag[] => {
    const box = mainBox(g);
    if (!box) return [];
    const req = fixture?.visualRequirements.find((v) => v.segmentationPrompt === g.concept);
    const name = req ? featureName(req.requirementId) : sentence(g.concept);
    const cropFile = g.crop?.file ?? null;
    if (change && change.photoEvidenceId === g.photoEvidenceId && change.targetPrompt === g.concept) {
      const moved = change.verdict === "Changed";
      return [{ concept: g.concept, box, cropFile, tone: moved ? "success" : change.verdict === "Unscorable" ? "warning" : "failure",
        label: `${name} · ${moved ? "changed since before" : change.verdict === "Unscorable" ? "not compared" : "no change"}`, detail: change.reason }];
    }
    const h = assessment?.hypotheses.find((x) => x.featureId === req?.requirementId && x.evidenceIds.includes(g.photoEvidenceId));
    if (!h) return [{ concept: g.concept, box, cropFile, tone: "primary", label: `${name} · AI outline`, detail: `SAM outlined the ${g.concept}. This is the model's interpretation, not a measurement.` }];
    const tone: Tone = h.status === "Observed" || h.status === "Suspected" ? "primary" : h.status === "Conflicting" ? "failure" : "warning";
    const status = h.status === "Observed" ? "AI: visible" : h.status === "Suspected" ? "AI: likely" : h.status === "Conflicting" ? "conflicting" : "not clear";
    return [{ concept: g.concept, box, cropFile, tone, label: `${name} · ${status}`, detail: h.reason }];
  });
}

/** The single next instruction for a hands-free view, derived from the server's state. */
export function nextStep(i?: Inspection | null, assessment?: Assessment | null): { text: string; hint?: string } {
  if (!i) return { text: "Loading…" };
  if (i.stage === "Inconclusive") return { text: "Inspection closed — inconclusive", hint: "Return to the inspection to review the evidence." };
  const asked = i.evidenceRequests?.find((r) => !r.answeredByPhotoId);
  if (asked && i.stage !== "VerifiedComplete") return { text: asked.note, hint: `Requested by the ${asked.requestedBy.toLowerCase()}. Take a photo to answer.` };
  const diag = i.diagnostics[i.diagnostics.length - 1];
  switch (i.stage) {
    case "VerifiedComplete": return { text: i.outcomeKind === "VerifiedCompleteSimulated" ? "Verified complete · simulated check" : "Verified complete", hint: "The FieldProof receipt is ready." };
    case "VerificationFailed": return { text: "Still failing — check the connection again", hint: "Then take a new photo and verify." };
    case "AwaitingRepair": return { text: "Do the repair", hint: "Then mark the repair complete." };
    case "ReadyToVerify": return i.acceptedPostRepairPhotoId
      ? { text: "Review the new photo", hint: "Mark each item, confirm, then run verification." }
      : { text: "Take a new photo of the repaired connection" };
    case "FaultConfirmed": return { text: "Fault confirmed", hint: "Plan the fix, then do the repair." };
    case "FaultSuspected": return { text: "Fault suspected", hint: "Run the approved equipment check to confirm." };
    default:
      if (i.currentClaimId) return { text: "Take a new photo of the repaired connection", hint: "The same photo as before is not accepted." };
      if (i.photos.length === 0) return { text: "Look at the equipment", hint: "Take a wide photo." };
      if (assessment?.nextAction === "RequestView" && assessment.requestedView) return { text: sentence(assessment.requestedView), hint: "Required to continue." };
      if (!assessment) return { text: "Ask LensQi to look at the photo" };
      if (!diag) return { text: "Run the approved equipment check", hint: "It confirms the fault." };
      return { text: "Continue the investigation" };
  }
}
