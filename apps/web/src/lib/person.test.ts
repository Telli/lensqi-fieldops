import { expect, it } from "vitest";
import { parseHeightCm } from "./person";

it("reads heights the way people say them, matching the Android app", () => {
  for (const s of [`6'2"`, "6' 2", "6 ft 2 in", "6ft2", "74 in", "188", "188 cm", "1.88 m", "1.88"]) expect(parseHeightCm(s)).toBe(188);
  expect(parseHeightCm("tall")).toBeNull();
  expect(parseHeightCm("30")).toBeNull();
});
