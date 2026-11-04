import { afterEach, expect, it, vi } from "vitest";
import { api, commandScope, isCancelled, type Inspection } from "./api";

afterEach(() => vi.unstubAllGlobals());

it("pins outlining to the selected photo even when another client captures a newer one", async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ segmentation: {}, state: {} })));
  vi.stubGlobal("fetch", fetch);
  await api.segment("inspection", "yellow cable", "photo-before-concurrent-upload");
  const [url, options] = fetch.mock.calls[0]!;
  expect(url).toBe("/v1/inspections/inspection/segment");
  expect(JSON.parse(options.body)).toEqual({ concept: "yellow cable", photoEvidenceId: "photo-before-concurrent-upload" });
});

it("names the photo an assessment is about, so the server refuses when a newer photo has arrived", async () => {
  const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ state: {} }))));
  vi.stubGlobal("fetch", fetch);
  await api.investigate("inspection", "photo/with slash");
  expect(fetch.mock.calls[0]![0]).toBe("/v1/inspections/inspection/investigate?photoEvidenceId=photo%2Fwith%20slash");
  await api.investigate("inspection", null);
  expect(fetch.mock.calls[1]![0]).toBe("/v1/inspections/inspection/investigate");
});

const keyOf = (call: unknown[]) => (call[1] as RequestInit & { headers: Record<string, string> }).headers["Idempotency-Key"];
const inspection = (extra: Partial<Inspection> = {}) =>
  ({ currentClaimId: "claim-1", acceptedPostRepairPhotoId: "photo-9", technicianAcknowledged: false, checklist: [], ...extra }) as Inspection;

it("turns a proxy's HTML error page into a plain API error instead of a syntax error", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>Bad Gateway</html>", { status: 502, statusText: "Bad Gateway" })));
  await expect(api.inspection("i-1")).rejects.toMatchObject({ code: "http_502", status: 502, message: "Bad Gateway" });
});

it("reports an unreadable success body rather than returning garbage", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("not json", { status: 200 })));
  await expect(api.inspection("i-1")).rejects.toMatchObject({ code: "bad_response" });
});

it("ends a cancelled call with its own code, not as offline", async () => {
  vi.stubGlobal("fetch", vi.fn().mockImplementation((_u: string, init: RequestInit) => new Promise((_, reject) => {
    init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
  })));
  const controller = new AbortController();
  const call = api.verify("i-1", "scope", controller.signal);
  controller.abort();
  await expect(call).rejects.toMatchObject({ code: "cancelled" });
  expect(isCancelled(await call.catch((e) => e))).toBe(true);
});

it("keeps the verify key across a lost answer and a cancel, and drops it once the server decides", async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(new Response("{}", { status: 503 }))
    .mockImplementationOnce((_u: string, init: RequestInit) => new Promise((_, reject) => init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")))))
    .mockImplementation(async () => new Response(JSON.stringify({ decision: { result: "Denied" }, state: {} })));
  vi.stubGlobal("fetch", fetch);
  const scope = commandScope(inspection());
  await expect(api.verify("i-1", scope)).rejects.toMatchObject({ status: 503 });
  const controller = new AbortController();
  const cancelled = api.verify("i-1", scope, controller.signal);
  controller.abort();
  await expect(cancelled).rejects.toMatchObject({ code: "cancelled" });
  await api.verify("i-1", scope);
  await api.verify("i-1", scope);
  const keys = fetch.mock.calls.map(keyOf);
  expect(keys[1]).toBe(keys[0]);
  expect(keys[2]).toBe(keys[0]);
  expect(keys[3]).not.toBe(keys[0]);
});

it("makes a command on a changed review a new command, and names the same review the same way whatever the order", () => {
  const a = commandScope(inspection({ checklist: [{ requirementId: "cable", status: "Satisfied", reviewedByEvidenceId: "photo-9" }, { requirementId: "led", status: "Unknown" }] }));
  const b = commandScope(inspection({ checklist: [{ requirementId: "led", status: "Unknown" }, { requirementId: "cable", status: "Satisfied", reviewedByEvidenceId: "photo-9" }] }));
  const changed = commandScope(inspection({ checklist: [{ requirementId: "cable", status: "NotSatisfied", reviewedByEvidenceId: "photo-9" }, { requirementId: "led", status: "Unknown" }] }));
  expect(a).toBe(b);
  expect(changed).not.toBe(a);
  expect(commandScope(inspection({ technicianAcknowledged: true }))).not.toBe(commandScope(inspection()));
  expect(commandScope(undefined)).toBe("");
  expect(a).not.toContain("sequence");
});

it("keeps a failed claim's key only for the same wording under the same claim", async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response("{}", { status: 503 })).mockImplementation(async () => new Response(JSON.stringify({ claimId: "c", state: {} })));
  vi.stubGlobal("fetch", fetch);
  await expect(api.claim("i-1", "reseated the cable", "claim=")).rejects.toMatchObject({ status: 503 });
  await api.claim("i-1", "reseated the cable", "claim=");
  await api.claim("i-1", "replaced the cable", "claim=");
  const keys = fetch.mock.calls.map(keyOf);
  expect(keys[1]).toBe(keys[0]);
  expect(keys[2]).not.toBe(keys[0]);
});

it("declines the requested view under a kept Idempotency-Key, with no body", async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response("{}", { status: 503 })).mockImplementation(async () => new Response(JSON.stringify({ inspectionId: "i-1", stage: "Inconclusive" })));
  vi.stubGlobal("fetch", fetch);
  await expect(api.declineView("i-1", "photo-3")).rejects.toMatchObject({ status: 503 });
  const r = await api.declineView("i-1", "photo-3");
  expect(r.stage).toBe("Inconclusive");
  const [url, init] = fetch.mock.calls[0]! as [string, RequestInit];
  expect(url).toBe("/v1/inspections/i-1/decline-view");
  expect(init.method).toBe("POST");
  expect(init.body).toBeUndefined();
  const keys = fetch.mock.calls.map(keyOf);
  expect(keys[0]).toBeTruthy();
  expect(keys[1]).toBe(keys[0]);
});
