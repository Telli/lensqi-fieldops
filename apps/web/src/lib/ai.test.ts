import { describe, expect, it } from "vitest";
import type { Assessment, Hypothesis } from "./api";
import { assetMatch } from "./ai";

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
