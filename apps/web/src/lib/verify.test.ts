import { expect, it } from "vitest";
import type { EventRecord, Inspection } from "./api";
import { attemptFailedOn, attemptHistory, currentAttempt, verifyCard, verifyPreflight } from "./verify";

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

it("lists every attempt in the record with the proof that decided it", () => {
  const events = [...attempt(), e("RepairClaimed", { claimId: "c2" }), e("PhotoRecorded", { evidenceId: "p2" }), decision("Denied", "Repair has not been acknowledged by the technician"),
    e("ChecklistReviewed", { requirementId: "switch_chassis_visible", status: "Satisfied", reviewedByEvidenceId: "p2" }), e("TechnicianAcknowledged", { claimId: "c2" }), probe("verify-diag-def", true), decision("Verified")];
  const history = attemptHistory(events);
  expect(history.map((a) => a.decision.result)).toEqual(["Denied", "Denied", "Verified"]);
  // The second attempt was denied for the missing confirmation: the server checks that before the photo, and the reason says so.
  expect(history.map(attemptFailedOn)).toEqual(["measurement", "confirmation", null]);
  expect(attemptFailedOn({ ...history[1]!, decision: { ...history[1]!.decision, reasons: ["A new post-repair image is required"] }, acknowledgedAt: "x" })).toBe("photo");
  expect(attemptFailedOn({ ...history[1]!, decision: { ...history[1]!.decision, reasons: ["Checklist requirement switch_chassis_visible is Unknown"] }, acknowledgedAt: "x" })).toBe("review");
  expect(history[1]!.review).toEqual([]);
  expect(history[2]!.measured.map((m) => m.passed)).toEqual([true]);
  // The newest attempt is the one the screen shows
  expect(currentAttempt(events)!.sequence).toBe(history[2]!.sequence);
});

const view = (extra: Partial<Inspection> = {}): Inspection => ({
  stage: "ReadyToVerify", currentClaimId: "c1", repairClaimedAt: "2026-11-02T10:00:00Z", acceptedPostRepairPhotoId: "p1", technicianAcknowledged: false,
  photos: [], diagnostics: [], checklist: [], segmentations: [], changeAnalyses: [{ claimId: "c1", photoEvidenceId: "p1", verdict: "Changed", reason: "", thresholdsVersion: "v" }],
  readiness: { ready: false, next: "ReviewChecklist", blocking: [], checks: [{ diagnosticId: "d", afterClaim: false, lastRunAt: "2026-11-02T09:00:00Z", passed: false, source: "Simulated" }], measuresOnVerify: true },
  ...extra,
} as Inspection);

it("preflight reads the four proofs from the server's view without deciding anything", () => {
  const rows = verifyPreflight(view(), ["a", "b"]);
  expect(rows.map((r) => [r.title, r.ok])).toEqual([["Repair photo", true], ["Your review", false], ["Your confirmation", false], ["Equipment check", null]]);
  expect(rows[0]!.detail).toContain("a change from the earlier view was seen");
  expect(rows[3]!.detail).toContain("last failed (simulated)");
});

it("preflight counts an answer only when given after the claim on a photo the change check did not reject", () => {
  const checklist = [
    { requirementId: "a", status: "Satisfied", reviewedAt: "2026-11-02T10:05:00Z", reviewedByEvidenceId: "p1" },
    { requirementId: "b", status: "Satisfied", reviewedAt: "2026-11-02T09:59:00Z", reviewedByEvidenceId: "p0" },   // before the claim
  ] as Inspection["checklist"];
  expect(verifyPreflight(view({ checklist }), ["a", "b"])[1]).toMatchObject({ ok: null, detail: "1 of 2 reviewed" });
  const rejected = view({ checklist: [{ requirementId: "a", status: "Satisfied", reviewedAt: "2026-11-02T10:05:00Z", reviewedByEvidenceId: "p1" }] as Inspection["checklist"],
    changeAnalyses: [{ claimId: "c1", photoEvidenceId: "p1", verdict: "NoVisualChange", reason: "", thresholdsVersion: "v" }] });
  expect(verifyPreflight(rejected, ["a"])[1]).toMatchObject({ ok: false, detail: "Nothing reviewed on the new photo yet" });
  const done = view({ technicianAcknowledged: true, checklist: [{ requirementId: "a", status: "Satisfied", reviewedAt: "2026-11-02T10:05:00Z", reviewedByEvidenceId: "p1" }] as Inspection["checklist"] });
  expect(verifyPreflight(done, ["a"]).map((r) => r.ok)).toEqual([true, true, true, null]);
  expect(verifyPreflight(view({ acceptedPostRepairPhotoId: null, awaitingAnalysis: ["p2"] }), [])[0]).toMatchObject({ ok: null });
});
