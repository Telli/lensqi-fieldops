import { expect, it } from "vitest";
import { clearCapture, loadCaptures, saveCapture } from "./captures";

it("without browser storage nothing is kept and nothing throws", async () => {
  const capture = { inspectionId: "I-1", blob: new Blob(["x"]), name: "a.jpg", source: "Phone" as const, key: "k", observedAt: "2026-11-02T10:00:00Z", savedAt: 1 };
  expect(await saveCapture(capture)).toBe(false);
  expect(await loadCaptures("I-1")).toEqual([]);
  await expect(clearCapture("I-1", "k")).resolves.toBeUndefined();
});
