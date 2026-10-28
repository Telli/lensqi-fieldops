// COCO compressed RLE (size [h, w], column-major runs, 6-bit chars offset 48, delta-coded two back), as emitted by
// meta-sam-parser. Returns a row-major boolean mask. Same algorithm as Core's CocoRle.
export function decodeRle(counts: string, height: number, width: number): Uint8Array {
  const runs: number[] = [];
  let p = 0;
  while (p < counts.length) {
    let x = 0, k = 0, more = true;
    while (more) {
      const c = counts.charCodeAt(p) - 48;
      x |= (c & 0x1f) << (5 * k);
      more = (c & 0x20) !== 0;
      p++; k++;
      if (!more && (c & 0x10) !== 0) x |= -1 << (5 * k);
    }
    if (runs.length > 2) x += runs[runs.length - 2]!;
    runs.push(x);
  }
  const col = new Uint8Array(height * width);
  let pos = 0, value = 0;
  for (const run of runs) { if (value) col.fill(1, pos, Math.min(pos + run, col.length)); pos += run; value ^= 1; }
  const out = new Uint8Array(height * width);
  for (let i = 0; i < out.length; i++) out[i] = col[(i % width) * height + Math.floor(i / width)]!;
  return out;
}
