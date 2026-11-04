import { afterEach, describe, expect, it, vi } from 'vitest';
import { MetaRecording, pcmWav, transcribeVoice } from './meta-voice';

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });
describe('Meta speech input', () => {
  it('encodes mono 24 kHz PCM with exact sizes and clamped amplitudes', async () => {
    const v = new DataView(await pcmWav(new Float32Array([-2, 0, 2]), 24000).arrayBuffer());
    expect(v.byteLength).toBe(50); expect(v.getUint32(4, true)).toBe(42);
    expect(v.getUint32(24, true)).toBe(24000); expect(v.getUint16(22, true)).toBe(1);
    expect(v.getInt16(44, true)).toBe(-32768); expect(v.getInt16(48, true)).toBe(32767);
    expect((await pcmWav(new Float32Array(48000), 48000).arrayBuffer()).byteLength).toBe(48044);
  });
  it('posts audio to the application server with cancellation and no client API key', async () => {
    const fetch = vi.fn().mockResolvedValue({ok: true, json: async () => ({transcript: ' next step '})});
    vi.stubGlobal('fetch', fetch);
    const signal = new AbortController().signal;
    expect(await transcribeVoice(pcmWav(new Float32Array(2400), 24000), signal)).toBe('next step');
    const [url, request] = fetch.mock.calls[0]!;
    expect(url).toBe('/v1/voice/transcribe'); expect(request.signal).toBe(signal);
    expect(request.headers).toBeUndefined(); expect(request.body.get('audio').type).toBe('audio/wav');
  });
  it('does not expose upstream messages or invent a transcript on failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ok:false,json:async()=>({message:'secret'})}));
    await expect(transcribeVoice(new Blob(), new AbortController().signal)).rejects.toThrow('Meta transcription is unavailable');
  });
  it('releases a microphone granted after cancellation without delivering audio', async () => {
    let grant!: (s: MediaStream) => void;
    const stop = vi.fn(), done = vi.fn();
    vi.stubGlobal('navigator', {mediaDevices:{getUserMedia:()=>new Promise<MediaStream>(resolve=>{grant=resolve;})}});
    const recorder = new MetaRecording(done); const pending = recorder.start(); recorder.abort();
    grant({getTracks:()=>[{stop}]} as unknown as MediaStream); await pending;
    expect(stop).toHaveBeenCalledOnce(); expect(done).not.toHaveBeenCalled();
    expect(recorder.controller.signal.aborted).toBe(true);
  });
});
