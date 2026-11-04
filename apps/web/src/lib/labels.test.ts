import { expect, it } from "vitest";
import { countPhotos, excludedReason, measuredValue, plainReason, predictionOutcome, probeValue } from "./labels";

it("reads closed-set probe values as words", () => {
  expect(probeValue("Reachable")).toBe("reachable");
  expect(probeValue("LinkReportedUp")).toBe("link reported up");
  expect(probeValue(null)).toBe("—");
});

it("shows the status a live server answered with instead of calling it unreachable", () => {
  expect(measuredValue({ normalizedValue: "Unreachable", httpStatus: 404 })).toBe("HTTP 404");
  expect(measuredValue({ normalizedValue: "Unreachable" })).toBe("unreachable");
  expect(measuredValue({ normalizedValue: null, httpStatus: null })).toBe("—");
});

it("labels every prediction outcome, including one still waiting for its check", () => {
  expect(Object.keys(predictionOutcome).sort()).toEqual(["Confirmed", "Falsified", "Pending", "Unscorable"]);
  expect(predictionOutcome.Falsified!.state).toBe("failed");
});

it("counts a photo once even when its original bytes are preserved beside the turned copy", () => {
  expect(countPhotos([{ kind: "photo" }, { kind: "original" }, { kind: "segmentation" }, {}])).toBe(2);
});

it("explains a review of a pre-claim photo in plain words", () => {
  expect(plainReason("Visual requirement 'a' was reviewed against p0, which was taken before the repair claim")).toContain("taken before the repair");
});

it("says in plain words why a fact was not applied to this unit, and never hides an unknown code", () => {
  expect(excludedReason("firmware_unknown")).toBe("Unverified: firmware not known");
  expect(excludedReason("firmware_mismatch")).toBe("Not applicable: different firmware");
  expect(excludedReason("hardware_revision_unknown")).toBe("Unverified: hardware revision not known");
  expect(excludedReason("hardware_revision_mismatch")).toBe("Not applicable: different hardware revision");
  expect(excludedReason("manufacturer_mismatch")).toBe("Not applicable: different equipment");
  expect(excludedReason("model_mismatch")).toBe("Not applicable: different equipment");
  expect(excludedReason("knowledge_conflict")).toBe("Conflicting sources; needs review");
  expect(excludedReason("some_new_code")).toBe("Not applied: some new code");
  expect(excludedReason("")).toBe("Not applied");
});

it("tells the technician a declined view closed the inspection, in their own terms", () => {
  expect(plainReason("technician declined the requested view")).toBe("You declined the requested view.");
  expect(plainReason("view budget of 3 exhausted")).toContain("didn't settle");
  expect(plainReason("model proposed StopInconclusive")).toBe("LensQi couldn't settle this from the photos.");
  const held = "Required vendor facts are not confirmed for this equipment (ApplicabilityUnverified): LAB-PORT-MAP-01";
  expect(plainReason(held)).toContain("documentation couldn't be confirmed");
  expect(plainReason(`The last check sent the claim back for a new photo: ${held}`)).toContain("documentation couldn't be confirmed");
});
