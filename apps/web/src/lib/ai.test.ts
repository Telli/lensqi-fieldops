import { describe, expect, it } from "vitest";
import type { Assessment, Hypothesis, Inspection } from "./api";
import { aiSummary, assetMatch } from "./ai";

const assessment = (assetId: string, ...hs: [string, Hypothesis["status"]][]): Assessment =>
  ({ assetId, hypotheses: hs.map(([featureId, status]) => ({ featureId, status, reason: "reason", evidenceIds: [] })), nextAction: "RequestView", modelId: "m", schemaVersion: "1" }) as Assessment;

// The camera's asset pill claims a match only when LensQi saw some of the equipment, not merely because the model named it.
describe("assetMatch", () => {
  it("is a match when equipment was seen and the id agrees", () =>
    expect(assetMatch(assessment("LAB-SW-01", ["switch_chassis_visible", "Observed"]), "LAB-SW-01")).toBe("match"));
  it("is unseen for a photo with no equipment, even when the model names the asset", () =>
    expect(assetMatch(assessment("LAB-SW-01", ["switch_chassis_visible", "Unknown"], ["yellow_cable_near_end_visible", "Unknown"]), "LAB-SW-01")).toBe("unseen"));
  it("does not count a seen post-repair item as seeing the equipment", () =>
    expect(assetMatch(assessment("LAB-SW-01", ["post_repair_view", "Observed"]), "LAB-SW-01")).toBe("unseen"));
  it("is a mismatch when equipment was seen under another asset id", () =>
    expect(assetMatch(assessment("LAB-NODE-A", ["switch_chassis_visible", "Suspected"]), "LAB-SW-01")).toBe("mismatch"));
  it("has no answer before LensQi has looked", () => expect(assetMatch(undefined, "LAB-SW-01")).toBeNull());
});

it("never says it compared a repair photo the server could not score", () => {
  const change = (verdict: string) => ({ claimId: "c1", photoEvidenceId: "p2", verdict, reason: "" });
  const base = { stage: "ReadyToVerify", currentClaimId: "c1", acceptedPostRepairPhotoId: "p2", photos: [], checklist: [] } as unknown as Inspection;
  expect(aiSummary({ ...base, changeAnalyses: [change("Unscorable")] } as unknown as Inspection)).toContain("couldn't compare");
  expect(aiSummary({ ...base, changeAnalyses: [change("Changed")] } as unknown as Inspection)).toContain("accepted it");
  expect(aiSummary({ ...base, stage: "VerifiedComplete", changeAnalyses: [change("Unscorable")] } as unknown as Inspection)).toMatch(/^Your review of the new photo/);
});
