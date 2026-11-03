import type { ChangeVerdict, ChecklistStatus, DiagnosticSource, ObservationStatus, Stage } from "./api";

/** Evidence states shown as badges; the vocabulary of the design, mapped from server facts. */
export type EvidenceState = "unknown" | "hypothesis" | "evidence-required" | "human-confirmed" | "verified" | "failed";

export const stageLabel: Record<Stage, string> = {
  Created: "Started",
  InitialScan: "Inspecting",
  NeedsEvidence: "Needs more evidence",
  FaultSuspected: "Fault suspected",
  FaultConfirmed: "Fault confirmed",
  AwaitingRepair: "Awaiting repair",
  ReadyToVerify: "Ready to verify",
  VerificationFailed: "Verification failed",
  VerifiedComplete: "Verified complete",
  Inconclusive: "Inconclusive",
};

export const stageState: Record<Stage, EvidenceState> = {
  Created: "unknown",
  InitialScan: "hypothesis",
  NeedsEvidence: "evidence-required",
  FaultSuspected: "hypothesis",
  FaultConfirmed: "human-confirmed",
  AwaitingRepair: "human-confirmed",
  ReadyToVerify: "evidence-required",
  VerificationFailed: "failed",
  VerifiedComplete: "verified",
  Inconclusive: "unknown",
};

export const observationState: Record<ObservationStatus, { state: EvidenceState; label: string }> = {
  Observed: { state: "hypothesis", label: "AI: visible" },
  Suspected: { state: "hypothesis", label: "AI hypothesis" },
  Unknown: { state: "evidence-required", label: "Can't see it yet" },
  Conflicting: { state: "failed", label: "Conflicting" },
};

export const changeLabel: Record<ChangeVerdict, { label: string; state: EvidenceState; detail: string }> = {
  Changed: { label: "Change detected", state: "human-confirmed", detail: "Something in the target area changed since the earlier photo." },
  NoVisualChange: { label: "No change", state: "failed", detail: "The target area looks the same as before. Take a new photo after the repair." },
  DuplicateImage: { label: "Same photo", state: "failed", detail: "This is the same picture as an earlier one. Take a new photo after the repair." },
  Unscorable: { label: "Not compared", state: "unknown", detail: "There was no earlier outline of the part to compare against. Your review decides." },
};

export const checklistLabel: Record<ChecklistStatus, string> = {
  Satisfied: "Looks right",
  NotSatisfied: "Not right",
  Unknown: "Can't tell",
  Conflicting: "Conflicting",
};

export const sourceLabel: Record<DiagnosticSource, string> = {
  LiveLocal: "Live",
  Simulated: "Simulated",
  RecordedReplay: "Replay",
  VendorReadOnly: "Vendor read-only",
};

/** "yellow_cable_near_end_visible" → "Yellow cable near end". */
export function featureName(id: string): string {
  const t = id.replace(/_(visible|captured|present)$/i, "").replace(/_/g, " ");
  return t.charAt(0).toUpperCase() + t.slice(1);
}

export function fmtTime(iso?: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

export function fmtWhen(iso?: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  const today = new Date();
  return d.toDateString() === today.toDateString() ? `Today ${fmtTime(iso)}` : d.toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

export function greeting(): string {
  const h = new Date().getHours();
  return h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
}

/** Turn a policy reason into a short sentence for the banner; the full text stays in the details. */
export function plainReason(reason: string): string {
  if (reason.startsWith("Diagnostic failed after the repair claim")) return "The equipment still fails its check after the repair.";
  if (reason.includes("post-repair image")) return "A new photo taken after the repair is needed.";
  if (reason.includes("acknowledged")) return "Confirm that you reviewed the evidence.";
  if (reason.includes("has not been reviewed")) return "Review every checklist item against the new photo.";
  if (reason.includes("not satisfied")) return "A checklist item is marked not right.";
  if (reason.includes("remains unknown") || reason.includes("conflicting")) return "A checklist item needs a person to decide.";
  if (reason.includes("unavailable")) return "The equipment check could not reach the endpoint.";
  if (reason.includes("change analysis rejected")) return "The reviewed photo was not accepted. Review against the new photo.";
  return reason;
}

/** A before-repair photo can never show the post-repair view; that item is expected to be unknown and is not "unclear". */
/** Photos in a receipt: a preserved original is the same photo's bytes under another name, and a mask is not a photo. */
export const countPhotos = (artifacts: { kind?: string }[]) => artifacts.filter((a) => (a.kind ?? "photo") === "photo").length;

export const isPostRepairItem = (featureId: string) => /post_?repair/i.test(featureId);

/** First letter up; model sentences sometimes start lower-case. */
export const sentence = (t?: string | null) => (t ? t.charAt(0).toUpperCase() + t.slice(1) : "");

/** A probe value from the closed prediction sets, in words: "Reachable" → "reachable", "LinkReportedUp" → "link reported up". */
export const probeValue = (v?: string | null) => (v ? v.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase() : "—");

/** What a check measured: the status a server answered with, else the closed-set value. */
export const measuredValue = (r: { normalizedValue?: string | null; httpStatus?: number | null }) =>
  r.httpStatus != null ? `HTTP ${r.httpStatus}` : probeValue(r.normalizedValue);

export const predictionOutcome: Record<string, { label: string; state: EvidenceState }> = {
  Confirmed: { label: "Prediction confirmed", state: "verified" },
  Falsified: { label: "Prediction falsified", state: "failed" },
  Unscorable: { label: "Couldn't be scored", state: "unknown" },
  Pending: { label: "Waiting for the check", state: "hypothesis" },
};
