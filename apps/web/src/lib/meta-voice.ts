/** Mono PCM recording for the server-side Meta transcriber. Audio stays in memory. */
export function pcmWav(samples: Float32Array, inputRate: number): Blob {
  const rate = 24000;
  const count = Math.floor(samples.length * rate / inputRate);
  const bytes = new ArrayBuffer(44 + count * 2), v = new DataView(bytes);
  const text = (at: number, s: string) => [...s].forEach((c, i) => v.setUint8(at + i, c.charCodeAt(0)));
  text(0, 'RIFF'); v.setUint32(4, 36 + count * 2, true); text(8, 'WAVEfmt ');
  v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  text(36, 'data'); v.setUint32(40, count * 2, true);
  for (let i = 0; i < count; i++) {
    // Average the input interval when downsampling; clamp before converting to PCM.
    const start = Math.floor(i * inputRate / rate), end = Math.max(start + 1, Math.floor((i + 1) * inputRate / rate));
    let sum = 0; for (let j = start; j < end && j < samples.length; j++) sum += samples[j]!;
    const x = Math.max(-1, Math.min(1, sum / (end - start)));
    v.setInt16(44 + i * 2, Math.round(x * (x < 0 ? 32768 : 32767)), true);
  }
  return new Blob([bytes], { type: 'audio/wav' });
}

export const NOTHING_HEARD = "I didn't hear anything.";

/** The audio held no words: an ordinary outcome, so hands-free keeps listening. Not a failure of the microphone or service. */
export class NoSpeechError extends Error {
  constructor() { super(NOTHING_HEARD); this.name = 'NoSpeechError'; }
}

export async function transcribeVoice(audio: Blob, signal: AbortSignal): Promise<string> {
  const form = new FormData(); form.append('audio', audio, 'speech.wav');
  let response: Response;
  try { response = await fetch('/v1/voice/transcribe', { method: 'POST', body: form, signal }); }
  catch (e) {
    if (signal.aborted) throw e;
    throw new Error('Meta transcription needs a connection. Try again or type instead.');
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    if (body.code === 'no_speech') throw new NoSpeechError();
    throw new Error('Meta transcription is unavailable. Try again or type instead.');
  }
  const body = await response.json();
  if (typeof body.transcript !== 'string' || !body.transcript.trim()) throw new NoSpeechError();
  return body.transcript.trim();
}

export class MetaRecording {
  private stream?: MediaStream;
  private context?: AudioContext;
  private source?: MediaStreamAudioSourceNode;
  private processor?: ScriptProcessorNode;
  private timer?: ReturnType<typeof setTimeout>;
  private samples: Float32Array[] = [];
  private length = 0;
  private spoken = false;
  private lastSpeech = 0;
  private ended = false;
  readonly controller = new AbortController();
  constructor(private finished: (audio: Blob | null) => void) {}
  async start() {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } });
    if (this.ended) { stream.getTracks().forEach(t => t.stop()); return; }
    this.stream = stream;
    const context = new AudioContext(); this.context = context;
    await context.resume();
    if (this.ended) return;
    const source = context.createMediaStreamSource(stream); this.source = source;
    // ScriptProcessor is retained for browser compatibility; output is silence, never microphone monitoring.
    const processor = context.createScriptProcessor(4096, 1, 1); this.processor = processor;
    processor.onaudioprocess = event => {
      if (this.ended) return;
      const chunk = new Float32Array(event.inputBuffer.getChannelData(0));
      this.samples.push(chunk); this.length += chunk.length;
      const rms = Math.sqrt(chunk.reduce((sum, x) => sum + x*x, 0) / chunk.length);
      if (rms > 0.015) { this.spoken = true; this.lastSpeech = this.length / context.sampleRate; }
      const seconds = this.length / context.sampleRate;
      if (seconds >= 60 || (this.spoken && seconds - this.lastSpeech >= 1.2) || (!this.spoken && seconds >= 8)) this.stop();
    };
    source.connect(processor); processor.connect(context.destination);
    this.timer = setTimeout(() => this.stop(), 60000);
  }
  get stopped() { return this.ended; }
  stop() {
    if (this.ended) return;
    const rate = this.context?.sampleRate ?? 24000;
    const joined = new Float32Array(this.length); let offset = 0;
    for (const part of this.samples) { joined.set(part, offset); offset += part.length; }
    this.release();
    this.finished(this.spoken && joined.length > 0 ? pcmWav(joined.subarray(0, rate * 60), rate) : null);
  }
  abort() { this.controller.abort(); this.release(); }
  private release() {
    this.ended = true; clearTimeout(this.timer);
    if (this.processor) this.processor.onaudioprocess = null;
    this.source?.disconnect(); this.processor?.disconnect();
    this.stream?.getTracks().forEach(t => t.stop());
    void this.context?.close().catch(() => {});
    this.samples = [];
  }
}
