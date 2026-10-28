/** Small, on-device patch tracker. Boxes originate only in a server detection; lost tracks are removed. */
export interface LiveBox { left: number; top: number; right: number; bottom: number }
export interface LiveObject { id: string; concept: string; label: string; box: LiveBox; confidence?: number | null }
export interface GrayFrame { id: string; time: number; width: number; height: number; pixels: Uint8Array }
const median = (values: number[]) => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)] ?? 0;
export function difference(a: GrayFrame, b: GrayFrame): number {
  if (a.width !== b.width || a.height !== b.height) return 255;
  let sum = 0; for (let i = 0; i < a.pixels.length; i += 3) sum += (a.pixels[i]! - b.pixels[i]!) ** 2;
  return Math.sqrt(sum / Math.ceil(a.pixels.length / 3));
}
export function frameQuality(f: GrayFrame) {
  let light = 0, edge = 0;
  for (let y = 1; y < f.height - 1; y++) for (let x = 1; x < f.width - 1; x++) {
    const p = y * f.width + x, v = f.pixels[p]!; light += v;
    edge += Math.abs(4 * v - f.pixels[p - 1]! - f.pixels[p + 1]! - f.pixels[p - f.width]! - f.pixels[p + f.width]!);
  }
  const n = (f.width - 2) * (f.height - 2);
  return { brightness: light / n, sharpness: edge / n };
}

export function trackBox(a: GrayFrame, b: GrayFrame, box: LiveBox): LiveBox | null {
  if (a.width !== b.width || a.height !== b.height || b.time - a.time > 700) return null;
  const w = a.width, h = a.height;
  const points: { x: number; y: number; dx: number; dy: number; score: number }[] = [];
  let textured = 0;
  for (let gy = 0; gy < 5; gy++) for (let gx = 0; gx < 5; gx++) {
    const x = Math.round((box.left + (gx + .5) / 5 * (box.right - box.left)) * w);
    const y = Math.round((box.top + (gy + .5) / 5 * (box.bottom - box.top)) * h);
    if (x < 9 || y < 9 || x >= w - 9 || y >= h - 9) continue;
    let sum = 0, sq = 0;
    for (let py = -2; py <= 2; py++) for (let px = -2; px <= 2; px++) { const v = a.pixels[(y + py) * w + x + px]!; sum += v; sq += v * v; }
    if (sq / 25 - (sum / 25) ** 2 < 80) continue;
    textured++;
    let best = Infinity, dx = 0, dy = 0;
    for (let sy = -6; sy <= 6; sy++) for (let sx = -6; sx <= 6; sx++) {
      let score = 0;
      for (let py = -2; py <= 2; py++) for (let px = -2; px <= 2; px++)
        score += Math.abs(a.pixels[(y + py) * w + x + px]! - b.pixels[(y + sy + py) * w + x + sx + px]!);
      // Prefer smaller displacement for exact ties, avoiding arbitrary drift along a flat edge.
      score = score / 25 + .015 * (sx * sx + sy * sy);
      if (score < best) { best = score; dx = sx; dy = sy; }
    }
    if (best < 22) points.push({ x, y, dx, dy, score: best });
  }
  if (points.length < 4 || points.length < textured * .5) return null;
  const dx = median(points.map(p => p.dx)), dy = median(points.map(p => p.dy));
  const consistent = points.filter(p => Math.abs(p.dx - dx) <= 2 && Math.abs(p.dy - dy) <= 2);
  if (consistent.length < 4 || consistent.length < points.length * .6) return null;
  const scales: number[] = [];
  for (let i = 0; i < consistent.length; i++) for (let j = i + 1; j < consistent.length; j++) {
    const p = consistent[i]!, q = consistent[j]!;
    const before = Math.hypot(p.x - q.x, p.y - q.y);
    if (before >= 12) scales.push(Math.hypot(p.x + p.dx - q.x - q.dx, p.y + p.dy - q.y - q.dy) / before);
  }
  const scale = scales.length ? median(scales) : 1;
  if (scale < .85 || scale > 1.15) return null;
  const cx = (box.left + box.right) / 2 + dx / w, cy = (box.top + box.bottom) / 2 + dy / h;
  const halfW = (box.right - box.left) * scale / 2, halfH = (box.bottom - box.top) * scale / 2;
  if (cx - halfW < 0 || cy - halfH < 0 || cx + halfW > 1 || cy + halfH > 1) return null;
  return { left: cx - halfW, top: cy - halfH, right: cx + halfW, bottom: cy + halfH };
}

export class LiveTracker {
  frames: GrayFrame[] = [];
  objects: LiveObject[] = [];
  detectedAt = 0;
  push(frame: GrayFrame) {
    const prev = this.frames.at(-1);
    if (prev && (prev.width !== frame.width || prev.height !== frame.height || frame.time - prev.time > 700)) { this.frames = []; this.objects = []; }
    if (prev && this.objects.length) this.objects = this.advance(prev, frame, this.objects);
    this.frames.push(frame);
    while (this.frames.length > 240 || frame.time - this.frames[0]!.time > 24000) this.frames.shift();
    if (frame.time - this.detectedAt > 20000) this.objects = [];
  }
  seed(frameId: string, objects: LiveObject[]): boolean {
    const index = this.frames.findIndex(f => f.id === frameId);
    if (index < 0) return false;
    this.detectedAt = this.frames[index]!.time;
    let tracked = objects.filter(o => Object.values(o.box).every(Number.isFinite) && o.box.left >= 0 && o.box.top >= 0 && o.box.right <= 1 && o.box.bottom <= 1 && o.box.right > o.box.left && o.box.bottom > o.box.top);
    for (let n = index + 1; n < this.frames.length; n++) tracked = this.advance(this.frames[n - 1]!, this.frames[n]!, tracked);
    this.objects = tracked;
    return true;
  }
  private advance(a: GrayFrame, b: GrayFrame, objects: LiveObject[]) {
    return objects.flatMap(o => { const box = trackBox(a, b, o.box); return box ? [{ ...o, box }] : []; });
  }
  current(frameId: string) {
    const ref = this.frames.find(f => f.id === frameId), last = this.frames.at(-1);
    return !!ref && !!last && last.time - ref.time < 20000 && difference(ref, last) < 32;
  }
}
