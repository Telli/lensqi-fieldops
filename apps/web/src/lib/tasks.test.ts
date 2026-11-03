import { afterEach, expect, it, vi } from "vitest";
import { tasks } from "./tasks";

afterEach(() => vi.unstubAllGlobals());

const keyOf = (call: unknown[]) => (call[1] as RequestInit & { headers: Record<string, string> }).headers["Idempotency-Key"];
const ok = () => new Response(JSON.stringify({ taskId: "T-1" }));

it("retries a command under the same key until the server answers, then starts a new one", async () => {
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError("network")).mockResolvedValueOnce(new Response("{}", { status: 503 }))
    .mockResolvedValueOnce(ok()).mockResolvedValueOnce(ok());
  vi.stubGlobal("fetch", fetch);
  await expect(tasks.complete("T-1", "s1")).rejects.toMatchObject({ code: "offline" });
  await expect(tasks.complete("T-1", "s1")).rejects.toMatchObject({ status: 503 });
  await tasks.complete("T-1", "s1");
  await tasks.complete("T-1", "s1");
  const keys = fetch.mock.calls.map(keyOf);
  expect(keys[1]).toBe(keys[0]);
  expect(keys[2]).toBe(keys[0]);
  expect(keys[3]).not.toBe(keys[0]);
});

it("keeps the key after a failed model call so the retry reuses the saved photo", async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ code: "provider_unavailable", message: "down" }), { status: 409 }))
    .mockImplementation(async () => new Response(JSON.stringify({ answer: "A switch." })));
  vi.stubGlobal("fetch", fetch);
  await expect(tasks.ask("T-1", "What is this?")).rejects.toMatchObject({ code: "provider_unavailable" });
  await tasks.ask("T-1", "What is this?");
  const keys = fetch.mock.calls.map(keyOf);
  expect(keys[1]).toBe(keys[0]);
});

it("drops the key when the server refuses the command, and keeps commands apart", async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ code: "invalid_state", message: "no" }), { status: 409 }))
    .mockImplementation(async () => ok());
  vi.stubGlobal("fetch", fetch);
  await expect(tasks.skip("T-1", "s2")).rejects.toMatchObject({ code: "invalid_state" });
  await tasks.skip("T-1", "s2");
  await tasks.skip("T-1", "s3");
  const keys = fetch.mock.calls.map(keyOf);
  expect(new Set(keys).size).toBe(3);
});

it("keys a step check by the photo too: the same photo retries under one key, a new photo is a new check", async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response("{}", { status: 503 })).mockImplementation(async () => new Response(JSON.stringify({ check: {}, task: {} })));
  vi.stubGlobal("fetch", fetch);
  const first = new Blob(["first photo"]), second = new Blob(["second photo"]);
  await expect(tasks.check("T-1", "s1", first)).rejects.toMatchObject({ status: 503 });
  await tasks.check("T-1", "s1", first);
  await tasks.check("T-1", "s1", second);
  const keys = fetch.mock.calls.map(keyOf);
  expect(keys[1]).toBe(keys[0]);
  expect(keys[2]).not.toBe(keys[0]);
});

it("keys a question by its frame, so a repeated question about a new frame is answered again", async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ code: "provider_unavailable", message: "down" }), { status: 409 }))
    .mockImplementation(async () => new Response(JSON.stringify({ answer: "A switch." })));
  vi.stubGlobal("fetch", fetch);
  await expect(tasks.ask("T-1", "What is this?", new Blob(["frame a"]))).rejects.toMatchObject({ code: "provider_unavailable" });
  await tasks.ask("T-1", "What is this?", new Blob(["frame b"]));
  const keys = fetch.mock.calls.map(keyOf);
  expect(keys[1]).not.toBe(keys[0]);
});

it("passes the caller's abort signal to the request", async () => {
  const fetch = vi.fn().mockImplementation((_u: string, init: RequestInit) => new Promise((_, reject) => init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")))));
  vi.stubGlobal("fetch", fetch);
  const controller = new AbortController();
  const call = tasks.ask("T-1", "Slow question", null, { signal: controller.signal });
  await Promise.resolve(); await Promise.resolve();
  controller.abort();
  await expect(call).rejects.toMatchObject({ code: "cancelled" });
});

it("keys a question by the step it is asked on, so a lost answer on one step is not replayed on the next", async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response("{}", { status: 503 })).mockImplementation(async () => new Response(JSON.stringify({ answer: "Yes." })));
  vi.stubGlobal("fetch", fetch);
  await expect(tasks.ask("T-1", "Is this done?", null, { stepId: "a" })).rejects.toMatchObject({ status: 503 });
  await tasks.ask("T-1", "Is this done?", null, { stepId: "a" });
  await tasks.ask("T-1", "Is this done?", null, { stepId: "b" });
  const keys = fetch.mock.calls.map(keyOf);
  expect(keys[1]).toBe(keys[0]);
  expect(keys[2]).not.toBe(keys[0]);
});
