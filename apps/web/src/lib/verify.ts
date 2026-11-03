import type { ChangeVerdict, ChecklistStatus, Decision, DiagnosticResult, EventRecord, Inspection, NextVerifyAction } from "./api";

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
  return k < 0 ? null : attemptAt(events, k);
}

/** Every verify decision in the record, oldest first, each with the review, confirmation and measurements it rested on. */
export function attemptHistory(events: EventRecord[]): Attempt[] {
  const out: Attempt[] = [];
  events.forEach((ev, k) => { if (ev.kind === "VerificationEvaluated") out.push(attemptAt(events, k)); });
  return out;
}

/** The attempt decided by the VerificationEvaluated event at `k`. */
function attemptAt(events: EventRecord[], k: number): Attempt {
  const decided = events[k]!;
  const measured: DiagnosticResult[] = [];
  for (let j = k - 1; j >= 0 && sameCall.has(events[j]!.kind); j--)
    if (events[j]!.kind === "DiagnosticCompleted" && String(events[j]!["evidenceId"]).startsWith(VERIFY_DIAGNOSTIC_PREFIX))
      measured.unshift(events[j]!["result"] as DiagnosticResult);

  const claimAt = events.slice(0, k).findLastIndex((e) => e.kind === "RepairClaimed");
  const sinceClaim = claimAt < 0 ? events.slice(0, k) : events.slice(claimAt + 1, k);
  const ack = [...sinceClaim].reverse().find((e) => e.kind === "TechnicianAcknowledged");
  const review = new Map<string, Attempt["review"][number]>();
  for (const e of sinceClaim)
    if (e.kind === "ChecklistReviewed")
      review.set(String(e["requirementId"]), { requirementId: String(e["requirementId"]), status: e["status"] as ChecklistStatus, photoEvidenceId: e["reviewedByEvidenceId"] as string | undefined });

  return { sequence: decided.sequence, at: decided.occurredAt, decision: decided["decision"] as Decision, measured, acknowledgedAt: ack?.occurredAt, review: [...review.values()] };
}

/**
 * Which proof decided an attempt, in a word for a list: the measurement, the review, the confirmation or the photo. Read
 * from the server's recorded reason first, in the order its policy checks (confirmation, then the photo, then the review,
 * then the measurement); the attempt's own evidence is only the fallback for a reason in other words.
 */
export function attemptFailedOn(a: Attempt): "measurement" | "review" | "confirmation" | "photo" | null {
  if (a.decision.result === "Verified") return null;
  const reason = (a.decision.reasons[0] ?? "").toLowerCase();
  if (reason.includes("acknowledged")) return "confirmation";
  if (reason.includes("post-repair image") || reason.includes("being compared") || reason.includes("photo")) return "photo";
  if (reason.includes("checklist") || reason.includes("requirement") || reason.includes("review")) return "review";
  if (reason.includes("diagnostic") || reason.includes("measured")) return "measurement";
  if (!a.acknowledgedAt) return "confirmation";
  if (a.measured.length) return a.measured.some((m) => m.passed !== true) ? "measurement" : null;
  return a.review.some((r) => r.status !== "Satisfied") ? "review" : "photo";
}

/** One of the proofs verify needs, as the server's view has it now. `ok` null means pending, or a line that only informs. */
export interface PreflightRow { title: string; ok: boolean | null; detail: string }

const at = (s?: string | null) => { const t = s ? Date.parse(s) : NaN; return Number.isNaN(t) ? null : t; };

/**
 * What stands between the technician and a verify that passes, read from the server's view: the accepted repair photo and
 * its comparison, the checklist for this claim, the confirmation, and how the equipment check will be measured. Nothing
 * here decides; verify measures again and the server's policy rules. A port of the Android `verifyPreflight`.
 */
export function verifyPreflight(i: Inspection, required: string[]): PreflightRow[] {
  const accepted = i.acceptedPostRepairPhotoId;
  const change = accepted ? [...i.changeAnalyses].reverse().find((c) => c.photoEvidenceId === accepted) : undefined;
  const photo: PreflightRow = accepted
    ? { title: "Repair photo", ok: true, detail: change?.verdict === "Changed" ? "Accepted · a change from the earlier view was seen" : change?.verdict === "Unscorable" ? "Accepted on your review · the photos could not be compared" : "Accepted" }
    : i.awaitingAnalysis?.length ? { title: "Repair photo", ok: null, detail: "Being compared with the earlier view" }
    : { title: "Repair photo", ok: false, detail: "Not taken yet" };
  // The policy's rule: an answer counts when it was given after the claim on a photo the change check did not reject;
  // it need not cite the newest accepted photo. Rejected as the server sees it: the last change check of each photo
  // under the current claim; older claims do not count.
  const claimedAt = at(i.repairClaimedAt);
  const lastByPhoto = new Map<string, ChangeVerdict>();
  for (const c of i.changeAnalyses) if (c.claimId === i.currentClaimId) lastByPhoto.set(c.photoEvidenceId, c.verdict);
  const rejected = new Set([...lastByPhoto].filter(([, v]) => v === "DuplicateImage" || v === "NoVisualChange").map(([id]) => id));
  const answers = required.flatMap((req) => {
    const e = i.checklist.find((c) => c.requirementId === req);
    if (!e) return [];
    const reviewed = at(e.reviewedAt);
    const afterClaim = claimedAt === null || reviewed === null || reviewed >= claimedAt;
    return afterClaim && !(e.reviewedByEvidenceId && rejected.has(e.reviewedByEvidenceId)) ? [e] : [];
  });
  const review: PreflightRow = required.length === 0 ? { title: "Your review", ok: true, detail: "No checklist items for this equipment" }
    : answers.length === required.length && answers.every((a) => a.status === "Satisfied") ? { title: "Your review", ok: true, detail: `All ${required.length} items marked as looking right` }
    : answers.some((a) => a.status === "NotSatisfied") ? { title: "Your review", ok: false, detail: "An item is marked not right: the repair is not finished" }
    : answers.length === 0 ? { title: "Your review", ok: false, detail: "Nothing reviewed on the new photo yet" }
    : { title: "Your review", ok: null, detail: `${answers.length} of ${required.length} reviewed${answers.some((a) => a.status !== "Satisfied") ? " · one needs you to decide" : ""}` };
  const confirm: PreflightRow = { title: "Your confirmation", ok: i.technicianAcknowledged, detail: i.technicianAcknowledged ? "You confirmed this evidence is yours" : "Not confirmed yet · a new photo or review resets it" };
  const check = i.readiness?.checks[0];
  const last = check?.lastRunAt ? ` · last ${check.passed === true ? "passed" : check.passed === false ? "failed" : "unavailable"}${check.source ? ` (${check.source.toLowerCase()})` : ""}` : "";
  const measure: PreflightRow = !i.readiness ? { title: "Equipment check", ok: null, detail: "Measured when you verify" }
    : i.readiness.measuresOnVerify ? { title: "Equipment check", ok: null, detail: `Measured again when you verify; an earlier result is not reused${last}` }
    : check?.afterClaim && check.passed === true ? { title: "Equipment check", ok: true, detail: `Passed after the repair claim${last}` }
    : check?.afterClaim ? { title: "Equipment check", ok: false, detail: `Ran after the claim but did not pass${last}` }
    : { title: "Equipment check", ok: false, detail: `Needs a run after the repair claim${last}` };
  return [photo, review, confirm, measure];
}

/** How long to keep asking the server about a photo still being compared before suggesting a retake. */
export const COMPARE_POLL_MS = 2000;
export const COMPARE_GIVE_UP_MS = 90_000;
