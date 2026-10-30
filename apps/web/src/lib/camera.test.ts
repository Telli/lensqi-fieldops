import { describe, expect, it, vi } from "vitest";
import { cameraError, connectCamera } from "./camera";

describe("camera ownership", () => {
  it("stops a stream that arrives after leaving the camera", async () => {
    let resolve!: (s: MediaStream) => void;
    const stop = vi.fn(), attach = vi.fn();
    const stream = { getTracks: () => [{ stop }] } as unknown as MediaStream;
    const dispose = connectCamera(() => new Promise((r) => { resolve = r; }), attach, vi.fn());
    dispose();
    resolve(stream);
    await Promise.resolve();
    expect(stop).toHaveBeenCalledOnce();
    expect(attach).not.toHaveBeenCalled();
  });
  it("stops an attached camera when reviewing a capture or navigating away", async () => {
    const stop = vi.fn(), attach = vi.fn();
    const stream = { getTracks: () => [{ stop }] } as unknown as MediaStream;
    const dispose = connectCamera(() => Promise.resolve(stream), attach, vi.fn());
    await Promise.resolve();
    expect(attach).toHaveBeenCalledWith(stream);
    dispose();
    expect(stop).toHaveBeenCalledOnce();
  });
  it("surfaces device errors only while mounted", async () => {
    const error = new DOMException("busy", "NotReadableError"), onError = vi.fn();
    connectCamera(() => Promise.reject(error), vi.fn(), onError);
    await Promise.resolve(); await Promise.resolve();
    expect(onError).toHaveBeenCalledWith(error);
    expect(cameraError(error)).toContain("busy");
    const ignored = vi.fn();
    connectCamera(() => Promise.reject(error), vi.fn(), ignored)();
    await Promise.resolve(); await Promise.resolve();
    expect(ignored).not.toHaveBeenCalled();
  });
});
