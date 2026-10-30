import type { ChecklistStatus, Decision, DiagnosticResult, EventRecord, Inspection, NextVerifyAction } from "./api";

/** Which card the verify screen shows. The server's readiness picks it; this only names it. */
export type VerifyCard = "claim" | "photo" | "comparing" | "review" | "confirm" | "run" | "closed";

const cardFor: Record<NextVerifyAction, VerifyCard> = {
  ClaimRepair: "claim", CapturePhoto: "photo", WaitForAnalysis: "comparing", ReviewChecklist: "review",
  Acknowledge: "confirm", Verify: "run", None: "closed",
};

/**
 * The card for the server's next step. An older server without readiness falls back to the stage and the accepted photo,
 * but the server still refuses anything out of order.
 */
export function verifyCard(i: Inspection): VerifyCard {
  if (i.readiness) return cardFor[i.readiness.next];
  if (i.stage === "VerifiedComplete" || i.stage === "Inconclusive") return "closed";
  if (i.stage === "VerificationFailed" || !i.currentClaimId) return "claim";
  if (i.awaitingAnalysis?.length) return "comparing";
  if (!i.acceptedPostRepairPhotoId || i.stage === "NeedsEvidence") return "photo";
  return i.technicianAcknowledged ? "run" : "review";
}

/** Progress dot for a card: Photo, Review, Confirm, Verify. */
export const verifyCardStep: Record<VerifyCard, number> = { claim: 0, photo: 0, comparing: 0, review: 1, confirm: 2, run: 3, closed: 4 };

/** One verify call as the event log recorded it: the decision, what the human had confirmed, and what was measured. */
export interface Attempt {
  sequence: number;
  at: string;
  decision: Decision;
  /** Fixture checks the verify call ran for this attempt; empty when the photo, review or confirmation decided first. */
  measured: DiagnosticResult[];
  /** When the technician confirmed the evidence this attempt used. */
  acknowledgedAt?: string;
  /** The checklist as it stood when the decision was made. */
  review: { requirementId: string; status: ChecklistStatus; photoEvidenceId?: string }[];
}

/** Evidence ids of checks the verify call ran itself (InspectionEndpoints.VerifyDiagnosticPrefix); a manual run is "diag-". */
export const VERIFY_DIAGNOSTIC_PREFIX = "verify-diag-";

/** Events the verify call records in one batch with its decision. */
const sameCall = new Set(["DiagnosticCompleted", "PredictionScored", "StageChanged"]);
/** Events that make an earlier decision stale for the screen: new evidence, a new review or confirmation, or a new claim. */
const supersedes = new Set(["RepairClaimed", "PhotoRecorded", "ChecklistReviewed", "TechnicianAcknowledged"]);

/**
 * The decision for the evidence on screen now, read from the event log, or null when there is none: no verify yet, or
 * a new claim, photo, review or confirmation since. Measurements are only those recorded by that verify call, so a check
 * run earlier is never shown as this attempt's.
 */
export function currentAttempt(events: EventRecord[]): Attempt | null {
  let k = events.length - 1;
  while (k >= 0 && events[k]!.kind !== "VerificationEvaluated") {
    if (supersedes.has(events[k]!.kind)) return null;
    k--;
  }
  if (k < 0) return null;
  const decided = events[k]!;
  const measured: DiagnosticResult[] = [];
  for (let j = k - 1; j >= 0 && sameCall.has(events[j]!.kind); j--)
    if (events[j]!.kind === "DiagnosticCompleted" && String(events[j]!["evidenceId"]).startsWith(VERIFY_DIAGNOSTIC_PREFIX))
      measured.unshift(events[j]!["result"] as DiagnosticResult);

  const claimAt = [...events.slice(0, k)].reverse().findIndex((e) => e.kind === "RepairClaimed");
  const sinceClaim = claimAt < 0 ? events.slice(0, k) : events.slice(k - claimAt, k);
  const ack = [...sinceClaim].reverse().find((e) => e.kind === "TechnicianAcknowledged");
  const review = new Map<string, Attempt["review"][number]>();
  for (const e of sinceClaim)
    if (e.kind === "ChecklistReviewed")
      review.set(String(e["requirementId"]), { requirementId: String(e["requirementId"]), status: e["status"] as ChecklistStatus, photoEvidenceId: e["reviewedByEvidenceId"] as string | undefined });

  return { sequence: decided.sequence, at: decided.occurredAt, decision: decided["decision"] as Decision, measured, acknowledgedAt: ack?.occurredAt, review: [...review.values()] };
}

/** How long to keep asking the server about a photo still being compared before suggesting a retake. */
export const COMPARE_POLL_MS = 2000;
export const COMPARE_GIVE_UP_MS = 90_000;
