import type { KnowledgeFact, NextAction, ReasonResponse } from "./api";
import { probeValue, sentence } from "./labels";

/**
 * The grounded advisor's answer, in rows a screen can draw. Everything here is the model's suggestion as the server
 * recorded it (accepted by the validator, or refused with the reasons); none of it is evidence and none of it is a stage.
 */
export interface Advice {
  kind: "accepted" | "refused";
  /** The server answered from its record instead of calling the model again. */
  replayed: boolean;
  hypothesis?: string;
  /** Statements of the pack facts the proposal cited; a cited id the pack does not list is shown as the id itself. */
  basedOn: string[];
  requestedView?: string;
  /** The fixture-approved check the proposal asks to run, by id. */
  proposedCheck?: string;
  /** "LensQi predicts the check will report X"; recorded before the check, scored after. */
  prediction?: string;
  nextStep?: string;
  refusals: { code: string; message: string }[];
}

/** The proposal's next action in the technician's words. Each is a thing to do, not a verdict. */
export const adviceNextStep: Record<NextAction, string> = {
  RequestView: "Take the view it asks for",
  SegmentRegion: "Outline the part in the photo",
  RunApprovedProbe: "Run the approved check",
  PrepareWorkOrder: "Plan the repair",
  RequestHumanReview: "Ask a person to review",
  StopInconclusive: "The documentation doesn't settle this; no model-specific advice",
};

export const adviceDisclaimer = "A suggestion grounded in the documentation, not evidence. Your photos, your review and the equipment check decide.";
export const predictionNote = "recorded before the check; the measurement decides, never the prediction";

export function advice(r: ReasonResponse, facts: KnowledgeFact[]): Advice {
  if (!r.accepted || !r.proposal) {
    return { kind: "refused", replayed: r.replayed, basedOn: [], refusals: r.rejections.map((x) => ({ code: x.code, message: x.message })) };
  }
  const p = r.proposal;
  const byId = new Map(facts.map((f) => [f.factId, f.statement] as const));
  const basedOn = (p.usedFactIds ?? []).map((id) => byId.get(id) ?? id);
  const out: Advice = { kind: "accepted", replayed: r.replayed, basedOn, nextStep: adviceNextStep[p.nextAction] ?? p.nextAction, refusals: [] };
  if (p.hypothesis) out.hypothesis = sentence(p.hypothesis);
  if (p.requestedVisualEvidence) out.requestedView = sentence(p.requestedVisualEvidence);
  const prediction = p.nextAction === "RunApprovedProbe" ? r.prediction : null;
  const check = prediction?.diagnosticId;
  if (check) out.proposedCheck = check;
  if (prediction) out.prediction = `LensQi predicts the check will report ${probeValue(prediction.predictedValue)}`;
  return out;
}
