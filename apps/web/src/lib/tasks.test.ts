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
