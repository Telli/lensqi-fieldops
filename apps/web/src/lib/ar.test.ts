import { describe, expect, it } from "vitest";
import { arTags, nextStep } from "./ar";
import { nextMove } from "./ai";
import type { Assessment, ChangeAnalysis, Fixture, Inspection, SegmentationGeometry } from "./api";

const fixture = { visualRequirements: [{ requirementId: "cable", mandatory: true, segmentationPrompt: "yellow cable" }] } as Fixture;
const outline: SegmentationGeometry = {
  segmentationId: "seg-a", photoEvidenceId: "photo-a", concept: "yellow cable", model: "sam", elapsedSeconds: 1,
  matched: true, boxes: [{ objectId: "cable", left: 10, top: 20, right: 80, bottom: 90 }], masks: [], label: "inferred",
};
const assessment: Assessment = {
  assetId: "asset", hypotheses: [{ featureId: "cable", status: "Observed", reason: "Visible in photo A", evidenceIds: ["photo-a"] }],
  nextAction: "RunApprovedProbe", modelId: "muse", schemaVersion: "1",
};
const inspection = (stage: Inspection["stage"], extra: Partial<Inspection> = {}) => ({ stage, photos: [], diagnostics: [], ...extra }) as Inspection;

describe("photo-bound AR labels", () => {
  it("uses a finding that cites the outlined photo", () => {
    expect(arTags(fixture, assessment, [outline])[0]?.detail).toBe("Visible in photo A");
  });
  it("does not attach a previous photo's finding to a new outline", () => {
    const tag = arTags(fixture, assessment, [{ ...outline, photoEvidenceId: "photo-b" }])[0];
    expect(tag?.label).toContain("AI outline");
    expect(tag?.detail).not.toContain("Visible in photo A");
  });
  it("does not attach another photo's change verdict", () => {
    const change = { photoEvidenceId: "photo-b", targetPrompt: "yellow cable", verdict: "Changed", reason: "Moved in B" } as ChangeAnalysis;
    expect(arTags(fixture, undefined, [outline], change)[0]?.tone).toBe("primary");
    expect(arTags(fixture, undefined, [{ ...outline, photoEvidenceId: "photo-b" }], change)[0]?.tone).toBe("success");
  });
  it("preserves the high-res ROI crop artifact filename on the tag when present", () => {
    const withCrop = { ...outline, crop: { file: "seg-a-crop.jpg" } };
    expect(arTags(fixture, assessment, [withCrop])[0]?.cropFile).toBe("seg-a-crop.jpg");
    expect(arTags(fixture, assessment, [outline])[0]?.cropFile).toBeNull();
  });
});

describe("honest AR guidance", () => {
  it("keeps suspicion distinct from probe confirmation", () => {
    expect(nextStep(inspection("FaultSuspected")).text).toBe("Fault suspected");
    expect(nextMove(inspection("FaultSuspected"))?.kind).toBe("check");
    expect(nextMove(inspection("FaultSuspected"))?.title).not.toContain("confirmed");
    expect(nextStep(inspection("FaultConfirmed")).text).toBe("Fault confirmed");
  });
  it("labels simulated completion in spoken and visual guidance", () => {
    const i = inspection("VerifiedComplete", { outcomeKind: "VerifiedCompleteSimulated" });
    expect(nextStep(i).text.toLowerCase()).toContain("simulated");
    expect(nextMove(i)?.title.toLowerCase()).toContain("simulated");
    expect(nextStep(inspection("VerifiedComplete", { outcomeKind: "VerifiedCompleteLive" })).text).toBe("Verified complete");
  });
  it("does not request more photos after an inconclusive closure", () => {
    const i = inspection("Inconclusive", { evidenceRequests: [{ requestId: "r", note: "Take a photo", requestedBy: "Operator", requestedAt: "now" }] });
    expect(nextStep(i).text).toBe("Inspection closed — inconclusive");
    expect(nextMove(i)).toBeNull();
  });
});
