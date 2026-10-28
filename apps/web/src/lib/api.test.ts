import { afterEach, expect, it, vi } from "vitest";
import { api } from "./api";

afterEach(() => vi.unstubAllGlobals());

it("pins outlining to the selected photo even when another client captures a newer one", async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ segmentation: {}, state: {} })));
  vi.stubGlobal("fetch", fetch);
  await api.segment("inspection", "yellow cable", "photo-before-concurrent-upload");
  const [url, options] = fetch.mock.calls[0]!;
  expect(url).toBe("/v1/inspections/inspection/segment");
  expect(JSON.parse(options.body)).toEqual({ concept: "yellow cable", photoEvidenceId: "photo-before-concurrent-upload" });
});
