import { expect, it } from "vitest";
import type { KnowledgeFact, ReasonResponse } from "./api";
import { advice, adviceNextStep } from "./advisor";

const fact = (factId: string, statement: string) => ({ factId, statement } as KnowledgeFact);
const facts = [fact("LAB-PORT-MAP-01", "Port 2 carries the uplink to Node A."), fact("LAB-HEALTH-CHECK-01", "The health endpoint answers 200 when the node is reachable.")];
const base = { proposalId: "prop-1", modelId: "muse", replayed: false, rejections: [], state: {} } as unknown as ReasonResponse;

it("turns an accepted proposal into the hypothesis, its sources, the check, the prediction and one next step", () => {
  const a = advice({
    ...base, accepted: true,
    proposal: { nextAction: "RunApprovedProbe", hypothesis: "the uplink on port 2 is unseated", requestedVisualEvidence: null, usedFactIds: ["LAB-PORT-MAP-01", "GHOST-FACT"], proposedDiagnosticId: "NODE-A-HEALTH" },
    prediction: { predictionId: "pred-1", diagnosticId: "NODE-A-HEALTH", predictedValue: "Unreachable", basedOnFactIds: ["LAB-PORT-MAP-01"] },
  }, facts);
  expect(a.kind).toBe("accepted");
  expect(a.hypothesis).toBe("The uplink on port 2 is unseated");
  // A cited fact is shown by its statement; a cited id the pack does not list stays visible as the id, never invented.
  expect(a.basedOn).toEqual(["Port 2 carries the uplink to Node A.", "GHOST-FACT"]);
  expect(a.proposedCheck).toBe("NODE-A-HEALTH");
  expect(a.prediction).toBe("LensQi predicts the check will report unreachable");
  expect(a.nextStep).toBe("Run the approved check");
  expect(a.refusals).toEqual([]);
});

it("asks for the view in plain words and names no check when none was proposed", () => {
  const a = advice({ ...base, accepted: true, replayed: true, proposal: { nextAction: "RequestView", hypothesis: null, requestedVisualEvidence: "show the connector from behind the rack", usedFactIds: [] } }, facts);
  expect(a.replayed).toBe(true);
  expect(a.hypothesis).toBeUndefined();
  expect(a.requestedView).toBe("Show the connector from behind the rack");
  expect(a.proposedCheck).toBeUndefined();
  expect(a.prediction).toBeUndefined();
  expect(a.nextStep).toBe("Take the view it asks for");
});

it("shows a refusal as the server's codes and messages, with nothing of the model's text", () => {
  const a = advice({ ...base, accepted: false, proposal: null, rejections: [{ code: "unknown_fact", message: "Cited fact X is not in the pack", offendingIds: ["X"] }, { code: "probe_not_approved", message: "Diagnostic Y is not approved for this asset", offendingIds: ["Y"] }] }, facts);
  expect(a.kind).toBe("refused");
  expect(a.refusals.map((x) => x.code)).toEqual(["unknown_fact", "probe_not_approved"]);
  expect(a.hypothesis).toBeUndefined();
  expect(a.nextStep).toBeUndefined();
});

it("has a plain next step for every action the server can accept, none of them a verdict", () => {
  expect(Object.keys(adviceNextStep).sort()).toEqual(["PrepareWorkOrder", "RequestHumanReview", "RequestView", "RunApprovedProbe", "SegmentRegion", "StopInconclusive"]);
  for (const step of Object.values(adviceNextStep)) expect(step).not.toMatch(/verified|confirmed|fault found/i);
});
