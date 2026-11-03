import { describe, expect, it } from "vitest";
import { arTags, assessmentForPhoto, containedRect, nextStep, outlineFrame, outlinesInRawOrientation } from "./ar";
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

describe("photo-bound findings", () => {
  it("keeps only the findings that cite the photo being shown", () => {
    const mixed: Assessment = { ...assessment, hypotheses: [
      { featureId: "cable", status: "Observed", reason: "In A", evidenceIds: ["photo-a"] },
      { featureId: "switch", status: "Unknown", reason: "In B", evidenceIds: ["photo-b"] },
      { featureId: "label", status: "Suspected", reason: "Cites nothing", evidenceIds: [] },
    ] };
    expect(assessmentForPhoto(mixed, "photo-b")?.hypotheses.map((h) => h.reason)).toEqual(["In B"]);
    expect(assessmentForPhoto(mixed, "photo-c")).toBeUndefined();
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

describe("outline orientation", () => {
  const cropped = { objectId: "o", width: 326, height: 382, left: 414, top: 586, right: 740, bottom: 968, rle: { size: [382, 326] as [number, number], counts: "" } };
  it("reads the frame from the photo size the server recorded, never from a mask cropped to its object", () => {
    expect(outlineFrame([{ ...outline, photoWidth: 4000, photoHeight: 3000, masks: [cropped] }])).toEqual({ w: 4000, h: 3000 });
    expect(outlineFrame([{ ...outline, masks: [cropped], crop: { file: "c.jpg", transform: { sourceWidth: 1600, sourceHeight: 1000 } } }])).toEqual({ w: 1600, h: 1000 });
    expect(outlineFrame([{ ...outline, masks: [cropped] }])).toBeUndefined();
  });
  it("shows a photo raw only when the browser would turn it a quarter turn against its outlines", () => {
    const frame = { w: 4000, h: 3000 };
    expect(outlinesInRawOrientation(frame, 3000, 4000)).toBe(true);
    expect(outlinesInRawOrientation(frame, 4000, 3000)).toBe(false);
    expect(outlinesInRawOrientation({ w: 2000, h: 2000 }, 2000, 2000)).toBe(false);
    expect(outlinesInRawOrientation(undefined, 3000, 4000)).toBe(false);
  });
  it("letterboxes a picture inside its box", () => {
    expect(containedRect(4000, 3000, 400, 400)).toEqual({ left: 0, top: 50, width: 400, height: 300 });
    expect(containedRect(3000, 4000, 400, 400)).toEqual({ left: 50, top: 0, width: 300, height: 400 });
  });
});
