import { expect, it } from "vitest";
import { countPhotos, measuredValue, predictionOutcome, probeValue } from "./labels";

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
