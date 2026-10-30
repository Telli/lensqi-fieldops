import { expect, it } from "vitest";
import { predictionOutcome, probeValue } from "./labels";

it("reads closed-set probe values as words", () => {
  expect(probeValue("Reachable")).toBe("reachable");
  expect(probeValue("LinkReportedUp")).toBe("link reported up");
  expect(probeValue(null)).toBe("—");
});

it("labels every prediction outcome, including one still waiting for its check", () => {
  expect(Object.keys(predictionOutcome).sort()).toEqual(["Confirmed", "Falsified", "Pending", "Unscorable"]);
  expect(predictionOutcome.Falsified!.state).toBe("failed");
});
