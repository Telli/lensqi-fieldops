import type { Assessment, Inspection } from "./api";
import { changeLabel, featureName, isPostRepairItem, plainReason, sentence } from "./labels";

/** What the single primary button does next. Every value maps to a server command or a screen; nothing is decided here. */
export type MoveKind = "scan" | "assess" | "check" | "plan" | "repair" | "verify" | "receipt" | "retry";
export interface Move { kind: MoveKind; title: string; hint?: string }

const list = (xs: string[]) => (xs.length <= 1 ? xs[0] ?? "" : `${xs.slice(0, -1).join(", ")} and ${xs[xs.length - 1]}`);

/** LensQi's one-sentence read of the latest photo, in the first person, from the assessment's own findings. */
export function aiSummary(i?: Inspection | null, assessment?: Assessment | null): string {
  if (!i) return "";
  // After a repair claim the story is the verification, told from the server's decision and change check.
  if (i.currentClaimId) {
    const own = [...i.changeAnalyses].reverse().find((c) => c.claimId === i.currentClaimId && c.photoEvidenceId === i.acceptedPostRepairPhotoId);
    if (i.stage === "VerifiedComplete")
      return own?.verdict === "Unscorable" ? "Your review of the new photo and a fresh equipment check both passed." : "The new photo and a fresh equipment check both passed.";
    if (i.stage === "VerificationFailed" && i.lastDecision) return plainReason(i.lastDecision.reasons[0] ?? "");
    const change = [...i.changeAnalyses].reverse().find((c) => c.claimId === i.currentClaimId);
    if (i.acceptedPostRepairPhotoId) {
      return own?.verdict === "Unscorable"
        ? "I couldn't compare your repair photo with the earlier one, so the visual part rests on your review."
        : "I've compared your repair photo with the earlier one and accepted it.";
    }
    if (change) return changeLabel[change.verdict].detail;
    return "Show me the repaired connection so I can compare it with before.";
  }
  if (i.photos.length === 0) return "Point the camera at the equipment and I'll tell you what I can see.";
  if (!assessment) return "I have your photo. Ask me to look at it.";
  const hs = assessment.hypotheses.filter((h) => !isPostRepairItem(h.featureId));
  const seen = hs.filter((h) => h.status === "Observed" || h.status === "Suspected").map((h) => featureName(h.featureId).toLowerCase());
  const unclear = hs.filter((h) => h.status === "Unknown").map((h) => featureName(h.featureId).toLowerCase());
  const odd = hs.filter((h) => h.status === "Conflicting").map((h) => featureName(h.featureId).toLowerCase());
  const parts = [
    seen.length ? `I can see the ${list(seen)}.` : "",
    unclear.length ? `I can't make out the ${list(unclear)} yet.` : "",
    odd.length ? `The ${list(odd)} doesn't match what I expect.` : "",
  ].filter(Boolean);
  return parts.join(" ") || "I couldn't make out the parts I need in this photo.";
}

/** The one thing to do next, from the server's stage and the latest assessment. */
export function nextMove(i?: Inspection | null, assessment?: Assessment | null): Move | null {
  if (!i) return null;
  const asked = i.evidenceRequests?.find((r) => !r.answeredByPhotoId);
  const diag = i.diagnostics[i.diagnostics.length - 1];
  switch (i.stage) {
    case "VerifiedComplete": return { kind: "receipt", title: i.outcomeKind === "VerifiedCompleteSimulated" ? "Verified complete · simulated check" : "Verified complete", hint: "The FieldProof receipt is ready." };
    case "Inconclusive": return null;
    case "VerificationFailed": return { kind: "retry", title: "Still failing", hint: "Check the connection again, then verify." };
    case "AwaitingRepair": return { kind: "repair", title: "Do the repair", hint: "Then tell me what you did." };
    case "ReadyToVerify": return { kind: "verify", title: i.acceptedPostRepairPhotoId ? "Review and verify" : "Photograph the repair", hint: "A new photo and a fresh check both have to pass." };
    case "FaultConfirmed": return { kind: "plan", title: "Fault confirmed", hint: "Say how you'll fix it." };
    case "FaultSuspected": return { kind: "check", title: "Fault suspected", hint: "Run the approved check to confirm it on the equipment." };
  }
  if (i.currentClaimId) return { kind: "verify", title: "Photograph the repair", hint: "The same photo as before won't count." };
  if (asked) return { kind: "scan", title: asked.note, hint: `Requested by the ${asked.requestedBy.toLowerCase()}.` };
  if (i.photos.length === 0) return { kind: "scan", title: "Scan the equipment", hint: "A wide photo first." };
  if (!assessment) return { kind: "assess", title: "Let LensQi look", hint: "I'll describe the photo and outline the parts." };
  if (assessment.nextAction === "RequestView" && assessment.requestedView) return { kind: "scan", title: sentence(assessment.requestedView), hint: "I need this view to continue." };
  if (!diag) return { kind: "check", title: "Run the approved check", hint: "It confirms the fault on the equipment itself." };
  return { kind: "scan", title: "Take another look", hint: "A new photo refreshes what I can see." };
}

/** Five coarse steps for the progress bar: how far along this inspection is. */
export function progress(i?: Inspection | null, assessment?: Assessment | null): number {
  if (!i) return 0;
  if (i.stage === "VerifiedComplete") return 5;
  if (i.currentClaimId) return 4;
  if (i.diagnostics.length) return 3;
  if (assessment) return 2;
  return i.photos.length ? 1 : 0;
}
export const progressSteps = ["Capture", "Observe", "Check", "Repair", "Verify"] as const;

/** The four gates of repair verification, in order. The server enforces each one; the bar only shows where you are. */
export const verifySteps = ["Photo", "Review", "Confirm", "Verify"] as const;
export type VerifyGate = "photo" | "review" | "confirm" | "run";
export const verifyGateIndex: Record<VerifyGate, number> = { photo: 0, review: 1, confirm: 2, run: 3 };

/**
 * Whether the photo shows the inspection's asset. The model always names an asset, even when the photo shows none of
 * it, so a match counts only when LensQi saw at least one part of the equipment. Otherwise the asset is "unseen".
 */
export type AssetMatch = "match" | "mismatch" | "unseen";
export function assetMatch(a: Assessment | null | undefined, expectedAssetId: string | undefined): AssetMatch | null {
  if (!a) return null;
  const saw = a.hypotheses.some((h) => !isPostRepairItem(h.featureId) && (h.status === "Observed" || h.status === "Suspected"));
  if (!saw || !a.assetId) return "unseen";
  return expectedAssetId && a.assetId !== expectedAssetId ? "mismatch" : "match";
}
