import { describe, it, expect } from 'vitest';
import { LiveTracker, trackBox, frameQuality, type GrayFrame, type LiveObject } from './live-tracker';
// Deterministic textured object translated over a dark background: tests measure motion, not invented detections.
function frame(id: number, dx = 0, dy = 0, blank = false): GrayFrame {
  const width = 160, height = 120, pixels = new Uint8Array(width * height).fill(24);
  if (!blank) for (let y = 25; y < 85; y++) for (let x = 35; x < 105; x++) pixels[(y + dy) * width + x + dx] = ((x * 719 + y * 173 + x * y * 31) % 200) + 40;
  return { id: `f-${id}`, time: id * 100, width, height, pixels };
}
const object: LiveObject = { id: 'cable', concept: 'cable', label: 'cable', box: { left: 35 / 160, top: 25 / 120, right: 105 / 160, bottom: 85 / 120 } };
describe('live tracking', () => {
  it('moves a detected box with pixels on the next frame', () => {
    const b = trackBox(frame(1), frame(2, 4, -3), object.box)!;
    expect(b).not.toBeNull();
    expect(b.left - object.box.left).toBeCloseTo(4 / 160, 3);
    expect(b.top - object.box.top).toBeCloseTo(-3 / 120, 3);
  });
  it('drops an occluded object instead of freezing its overlay', () => expect(trackBox(frame(1), frame(2, 0, 0, true), object.box)).toBeNull());
  it('rebases delayed detections through subsequent frames', () => {
    const t = new LiveTracker(); t.push(frame(1)); t.push(frame(2, 2)); t.push(frame(3, 4));
    expect(t.seed('f-1', [object])).toBe(true);
    expect(t.objects[0]!.box.left).toBeCloseTo(object.box.left + 4 / 160, 3);
  });
  it('rejects unknown frame results and clears tracks across capture gaps', () => {
    const t = new LiveTracker(); t.push(frame(1)); expect(t.seed('other-session', [object])).toBe(false);
    t.seed('f-1', [object]); t.push(frame(20)); expect(t.objects).toEqual([]);
  });
  it('never creates an object without a detection and expires old tracks', () => {
    const t = new LiveTracker(); t.push(frame(1)); expect(t.objects).toEqual([]);
    t.seed('f-1', [object]); for (let n = 2; n < 205; n++) t.push(frame(n));
    expect(t.objects).toEqual([]);
  });
  it('rejects stale guidance after a scene change and measures darkness locally', () => {
    const t = new LiveTracker(); t.push(frame(1)); t.push(frame(2, 0, 0, true));
    expect(t.current('f-1')).toBe(false);
    expect(frameQuality(frame(1, 0, 0, true)).brightness).toBe(24);
  });
});
