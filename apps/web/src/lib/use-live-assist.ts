import { useEffect, useRef, useState, type RefObject } from 'react';
import type { LiveObject } from './live-tracker';

export interface LiveQualityTelemetry {
  brightness: number;
  sharpness: number;
  motion: number;
}

export interface LiveFeedback {
  objects: LiveObject[]; status: string; recommendation: string; updatedAt: number | null;
  width: number; height: number; fps: number; telemetry: LiveQualityTelemetry | null;
  /** Error or status text the AI read off a screen in view, while its guidance is current. */
  screenText: string | null;
  /** When the server last confirmed each tracked box (performance.now()); between detections the box is only tracked on the device. */
  detectedAt: Record<string, number>;
}
const initial: LiveFeedback = { objects: [], status: 'Ready', recommendation: 'Keep the work area in view.', updatedAt: null, width: 0, height: 0, fps: 0, telemetry: null, screenText: null, detectedAt: {} };

/** Local tracking never waits for HTTP. Detection and guidance each have one cancellable request in flight. */
/** `endpoint` is the session the frames belong to, e.g. `/v1/tasks/T-1` or `/v1/inspections/I-1`. */
export function useLiveAssist(video: RefObject<HTMLVideoElement | null>, active: boolean, endpoint: string) {
  const [enabled, setEnabled] = useState(false);
  const [feedback, setFeedback] = useState<LiveFeedback>(initial);
  const generation = useRef(0);
  useEffect(() => {
    if (!enabled || !active) { setFeedback(initial); return; }
    const run = ++generation.current;
    const worker = new Worker(new URL('./live-tracker.worker.ts', import.meta.url), { type: 'module' });
    const capture = document.createElement('canvas'), small = document.createElement('canvas');
    const ctx = capture.getContext('2d')!, gray = small.getContext('2d', { willReadFrequently: true })!;
    const pending = new Set<AbortController>();
    const busy = { detect: false, guide: false }, due = { detect: 0, guide: 0 };
    let serial = 0, disposed = false, processing = false, last = 0, ticks = 0, fpsAt = performance.now(), fps = 0;
    let objects: LiveObject[] = [], guidance = '', screenText: string | null = null, pendingScreen: string | null = null, guidanceAt = 0, guidanceFrame = '', detected = false, scanned = false, error = '';
    let waitingGuidance: { frameId: string; text: string; time: number } | null = null;
    let detectedAt: Record<string, number> = {};
    const reset = () => {
      generation.current++; pending.forEach(c => c.abort()); worker.terminate();
      setFeedback(initial);
    };
    worker.onmessage = (e) => {
      if (disposed || generation.current !== run) return;
      const data = e.data;
      if (data.kind === 'guidance-current') {
        if (waitingGuidance && waitingGuidance.frameId === data.frameId) {
          if (data.current) { guidance = waitingGuidance.text; screenText = pendingScreen; guidanceAt = waitingGuidance.time; guidanceFrame = data.frameId; }
          waitingGuidance = null;
        } else if (guidanceFrame === data.frameId && !data.current) { guidance = ''; screenText = null; guidanceAt = 0; }
        return;
      }
      if (data.kind === 'detection') {
        scanned ||= data.accepted; detected ||= data.accepted && data.objects.length > 0; objects = data.objects;
        if (data.accepted) { const now = performance.now(); detectedAt = Object.fromEntries((data.objects as LiveObject[]).map(o => [o.id, now])); }
        return;
      }
      processing = false; objects = data.objects;
      const now = performance.now();
      if (guidanceAt && now - guidanceAt > 20000) { guidance = ''; screenText = null; guidanceAt = 0; }
      const quality = data.brightness < 35 && !guidance ? 'Add light so the parts and labels are visible.'
        : data.motion > 32 ? 'Move more slowly so I can keep track of the parts.'
        : data.sharpness < 3 && !guidance ? 'Hold steady and bring the parts into focus.' : '';
      setFeedback({ objects, width: video.current?.videoWidth ?? 0, height: video.current?.videoHeight ?? 0, fps,
        telemetry: { brightness: data.brightness, sharpness: data.sharpness, motion: data.motion },
        status: error || (objects.length ? `Tracking ${objects.length} part${objects.length === 1 ? '' : 's'}` : busy.detect ? 'Finding parts…' : detected ? 'Tracking lost · finding parts again' : scanned ? 'No parts found · keep scanning' : 'Looking for equipment…'),
        recommendation: quality || guidance || (objects.length ? 'Keep these parts in view.' : 'Aim at the work area and hold steady.'),
        updatedAt: guidanceAt || null, screenText, detectedAt });
    };
    worker.onerror = () => { processing = false; setEnabled(false); setFeedback({ ...initial, status: 'Live tracking could not start. Try again.' }); };
    const request = async (kind: 'detect' | 'guide', blob: Blob, frameId: string, capturedAt: number) => {
      const abort = new AbortController(); pending.add(abort); busy[kind] = true;
      const timeout = setTimeout(() => abort.abort(), 22000);
      const minimum = kind === 'detect' ? 4000 : 12000;
      due[kind] = capturedAt + minimum;
      try {
        const form = new FormData(); form.append('image', blob, 'preview.jpg'); form.append('frameId', frameId);
        const response = await fetch(`${endpoint}/live/${kind}`, { method: 'POST', body: form, signal: abort.signal });
        if (disposed || generation.current !== run) return;
        if (!response.ok) {
          const retry = Number(response.headers.get('Retry-After'));
          due[kind] = performance.now() + (retry > 0 ? retry * 1000 : 5000);
          throw new Error(response.status === 429 ? 'AI cooling down · local tracking continues' : 'AI unavailable · retrying');
        }
        const result = await response.json();
        if (result.frameId !== frameId || performance.now() - capturedAt > 20000) return;
        // The server cooldown begins after network transit. Wait from completion to avoid racing that boundary.
        error = ''; due[kind] = performance.now() + Math.max(minimum, result.refreshAfterMs ?? minimum);
        if (kind === 'detect') worker.postMessage({ kind: 'detect', frameId, objects: result.objects });
        else { waitingGuidance = { frameId, text: result.recommendation, time: capturedAt }; pendingScreen = typeof result.screenText === 'string' ? result.screenText : null; worker.postMessage({ kind: 'guide', frameId }); }
      } catch (e) {
        if (!disposed) { error = e instanceof Error && e.name !== 'AbortError' ? e.message : 'AI timed out · retrying'; due[kind] = Math.max(due[kind], performance.now() + 5000); }
      } finally { clearTimeout(timeout); pending.delete(abort); busy[kind] = false; }
    };
    const tick = () => {
      const v = video.current, now = performance.now();
      if (disposed || generation.current !== run || document.hidden || !v || v.readyState < 2 || !v.videoWidth || processing || now - last < 100) return;
      last = now; ticks++;
      if (now - fpsAt >= 1000) { fps = Math.round(ticks * 1000 / (now - fpsAt)); ticks = 0; fpsAt = now; }
      const ratio = Math.min(1, 640 / Math.max(v.videoWidth, v.videoHeight));
      capture.width = Math.round(v.videoWidth * ratio); capture.height = Math.round(v.videoHeight * ratio);
      ctx.drawImage(v, 0, 0, capture.width, capture.height);
      const scale = 160 / Math.max(capture.width, capture.height);
      small.width = Math.round(capture.width * scale); small.height = Math.round(capture.height * scale);
      gray.drawImage(capture, 0, 0, small.width, small.height);
      const rgba = gray.getImageData(0, 0, small.width, small.height).data, pixels = new Uint8Array(small.width * small.height);
      for (let p = 0; p < pixels.length; p++) pixels[p] = (rgba[p * 4]! * 77 + rgba[p * 4 + 1]! * 150 + rgba[p * 4 + 2]! * 29) >> 8;
      const frameId = `live-${run}-${++serial}`;
      processing = true;
      worker.postMessage({ kind: 'frame', frame: { id: frameId, time: now, width: small.width, height: small.height, pixels } }, [pixels.buffer]);
      if (guidanceFrame && serial % 10 === 0) worker.postMessage({ kind: 'guide', frameId: guidanceFrame });
      const kinds = (['detect', 'guide'] as const).filter(k => !busy[k] && now >= due[k]);
      if (kinds.length) {
        kinds.forEach(k => { busy[k] = true; });
        capture.toBlob(blob => {
          if (disposed || !blob) { kinds.forEach(k => { busy[k] = false; }); return; }
          kinds.forEach(k => { void request(k, blob, frameId, now); });
        }, 'image/jpeg', .8);
      }
    };
    const timer = setInterval(tick, 40);
    // A hidden/background camera must not keep sending frames or replay old responses when resumed.
    const visibility = () => { if (document.hidden) { reset(); setEnabled(false); } };
    document.addEventListener('visibilitychange', visibility);
    return () => { disposed = true; clearInterval(timer); document.removeEventListener('visibilitychange', visibility); reset(); };
  }, [enabled, active, endpoint, video]);
  return { enabled, setEnabled, ...feedback };
}
