import { LiveTracker, frameQuality, difference, type GrayFrame, type LiveObject } from './live-tracker';
const tracker = new LiveTracker();
self.onmessage = (event: MessageEvent<{ kind: string; frame?: GrayFrame; frameId?: string; objects?: LiveObject[] }>) => {
  const m = event.data;
  if (m.kind === 'frame' && m.frame) {
    const previous = tracker.frames.at(-1);
    const motion = previous ? difference(previous, m.frame) : 0;
    tracker.push(m.frame);
    self.postMessage({ kind: 'tracking', frameId: m.frame.id, objects: tracker.objects, ...frameQuality(m.frame), motion });
  } else if (m.kind === 'detect') {
    const accepted = tracker.seed(m.frameId!, m.objects ?? []);
    self.postMessage({ kind: 'detection', accepted, objects: tracker.objects });
  } else if (m.kind === 'guide') self.postMessage({ kind: 'guidance-current', frameId: m.frameId, current: tracker.current(m.frameId!) });
};
