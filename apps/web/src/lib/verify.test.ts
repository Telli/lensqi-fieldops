import { expect, it } from "vitest";
import type { EventRecord, Inspection } from "./api";
import { currentAttempt, verifyCard } from "./verify";

let seq = 0;
const e = (kind: string, fields: Record<string, unknown> = {}): EventRecord => ({ kind, sequence: ++seq, occurredAt: new Date(Date.UTC(2026, 9, 30, 10, 0, seq)).toISOString(), ...fields });
const probe = (evidenceId: string, passed: boolean) => e("DiagnosticCompleted", { evidenceId, result: { diagnosticId: "NODE-A-HEALTH", passed, source: "Simulated" } });
const decision = (result: string, reason = "") => e("VerificationEvaluated", { decision: { result, outcomeKind: null, reasons: [reason], verifiedClaims: [], policyVersion: "fieldproof-policy/1.4", fixtureDigest: "x" } });

const attempt = () => [
  e("RepairClaimed", { claimId: "c1" }),
  e("PhotoRecorded", { evidenceId: "p1" }),
  e("ChecklistReviewed", { requirementId: "switch_chassis_visible", status: "Unknown", reviewedByEvidenceId: "p1" }),
  e("ChecklistReviewed", { requirementId: "switch_chassis_visible", status: "Satisfied", reviewedByEvidenceId: "p1" }),
  e("TechnicianAcknowledged", { claimId: "c1" }),
  probe("diag-manual", true),                       // run by hand just before verify
  probe("verify-diag-abc", false),
  e("PredictionScored", {}),
  decision("Denied", "Diagnostic failed after the repair claim"),
  e("StageChanged", { to: "VerificationFailed" }),
];

it("reads the decision, the review it used and only the checks verify itself ran", () => {
  const a = currentAttempt(attempt())!;
  expect(a.decision.result).toBe("Denied");
  expect(a.measured.map((m) => m.passed)).toEqual([false]);
  expect(a.review).toEqual([{ requirementId: "switch_chassis_visible", status: "Satisfied", photoEvidenceId: "p1" }]);
  expect(a.acknowledgedAt).toBeDefined();
});

it("a decision made before any check ran reports no measurement", () => {
  const a = currentAttempt([e("RepairClaimed"), e("PhotoRecorded"), decision("Denied", "Repair has not been acknowledged by the technician")])!;
  expect(a.measured).toEqual([]);
  expect(a.acknowledgedAt).toBeUndefined();
});

it("new evidence, review, confirmation or claim retires the decision from the screen", () => {
  expect(currentAttempt([])).toBeNull();
  for (const kind of ["PhotoRecorded", "ChecklistReviewed", "TechnicianAcknowledged", "RepairClaimed"])
    expect(currentAttempt([...attempt(), e(kind)])).toBeNull();
});

it("the card follows the server's readiness, with a stage fallback for an older server", () => {
  const base = { stage: "ReadyToVerify", currentClaimId: "c1", acceptedPostRepairPhotoId: "p1", technicianAcknowledged: false } as Inspection;
  expect(verifyCard({ ...base, readiness: { ready: false, next: "WaitForAnalysis", blocking: [], checks: [], measuresOnVerify: true } })).toBe("comparing");
  expect(verifyCard({ ...base, readiness: { ready: true, next: "Verify", blocking: [], checks: [], measuresOnVerify: true } })).toBe("run");
  expect(verifyCard({ ...base, readiness: { ready: false, next: "None", blocking: [], checks: [], measuresOnVerify: true } })).toBe("closed");
  expect(verifyCard(base)).toBe("review");
  expect(verifyCard({ ...base, stage: "VerificationFailed" })).toBe("claim");
  expect(verifyCard({ ...base, awaitingAnalysis: ["p2"] })).toBe("comparing");
});
