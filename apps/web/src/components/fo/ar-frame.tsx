import { useEffect, useId, useRef, useState } from "react";
import { Eye, EyeOff, X, ZoomIn, ZoomOut } from "lucide-react";
import { cn } from "@/lib/utils";
import { decodeRle } from "@/lib/rle";
import type { SegmentationGeometry, Mask } from "@/lib/api";
import { containedRect, outlineFrame, outlinesInRawOrientation, type ArTag, type Tone } from "@/lib/ar";

const tagTone: Record<Tone, string> = {
  primary: "border-primary/60 text-primary",
  warning: "border-warning/60 text-warning",
  failure: "border-failure/60 text-failure",
  success: "border-success/60 text-success",
};

/** The photo as laid out: its size on first load (before any orientation change, see below) and the box it is shown in. */
type Measured = { src: string; nw: number; nh: number; cw: number; ch: number };

/** Browsers refuse canvases past about 16.7 million pixels; a 12 MP photo's outlines look the same drawn at this size. */
const MAX_CANVAS_SIDE = 4096;

/**
 * Decoded, tinted mask tiles, so a re-render or a polling refresh never decodes the RLE or repaints a tile again. SAM
 * returns each mask cropped to its object, so most tiles are small; one covering a whole 12 MP photo is drawn at the
 * display cap, and the cache keeps a byte budget rather than an entry count.
 */
const tiles = new Map<string, HTMLCanvasElement>();
const TILE_BUDGET_BYTES = 64 * 1024 * 1024;
let tileBytes = 0;
const bytesOf = (c: HTMLCanvasElement) => c.width * c.height * 4;
function remember(key: string, tile: HTMLCanvasElement) {
  const size = bytesOf(tile);
  if (size > TILE_BUDGET_BYTES) return;
  const old = tiles.get(key);
  if (old) { tiles.delete(key); tileBytes -= bytesOf(old); }
  for (const [k, c] of tiles) {
    if (tileBytes + size <= TILE_BUDGET_BYTES) break;
    tiles.delete(k); tileBytes -= bytesOf(c);
  }
  tiles.set(key, tile); tileBytes += size;
}
function tintedTile(segmentationId: string, m: Mask, color: string): HTMLCanvasElement | null {
  const key = `${segmentationId}:${m.objectId}:${color}`;
  const hit = tiles.get(key);
  if (hit) return hit;
  const [mh, mw] = m.rle.size;
  const bits = decodeRle(m.rle.counts, mh, mw);
  const full = document.createElement("canvas");
  full.width = mw; full.height = mh;
  const f = full.getContext("2d");
  if (!f) return null;
  const pixels = f.createImageData(mw, mh);
  for (let p = 0; p < bits.length; p++) pixels.data[p * 4 + 3] = bits[p] ? 255 : 0;
  f.putImageData(pixels, 0, 0);
  // Tint, scaling down to the display cap; the full-size decode above is let go once drawn.
  const scale = Math.min(1, MAX_CANVAS_SIDE / Math.max(mw, mh));
  const tile = scale === 1 ? full : document.createElement("canvas");
  if (tile !== full) {
    tile.width = Math.max(1, Math.round(mw * scale)); tile.height = Math.max(1, Math.round(mh * scale));
    tile.getContext("2d")?.drawImage(full, 0, 0, tile.width, tile.height);
  }
  const t = tile.getContext("2d");
  if (!t) return null;
  t.globalCompositeOperation = "source-in";
  t.fillStyle = color;
  t.fillRect(0, 0, tile.width, tile.height);
  remember(key, tile);
  return tile;
}

function resolveCropUrl(src: string, cropFile?: string | null): string | null {
  if (!cropFile) return null;
  if (src.includes("/artifacts/")) return `${src.slice(0, src.lastIndexOf("/") + 1)}${encodeURIComponent(cropFile)}`;
  if (typeof window !== "undefined") {
    const m = window.location.pathname.match(/\/(?:m\/i|ops\/inspections)\/([^/]+)/);
    if (m?.[1]) return `/v1/inspections/${encodeURIComponent(m[1])}/artifacts/${encodeURIComponent(cropFile)}`;
  }
  return null;
}

/** Photo-bound overlays share the image's contained rectangle, including its letterboxing. */
export function ArFrame({ src, outlines, tags, guide, selected, onSelect, interactive = true, className, imgClassName, children }: {
  src: string; outlines: SegmentationGeometry[]; tags: ArTag[]; guide?: { concept: string; label: string } | null;
  selected?: string | null; onSelect?: (concept: string | null) => void; interactive?: boolean;
  className?: string; imgClassName?: string; children?: React.ReactNode;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const img = useRef<HTMLImageElement>(null);
  const [measured, setMeasured] = useState<Measured | null>(null);
  const [selection, setSelection] = useState<{ src: string; concept: string | null } | null>(null);
  const [hidden, setHidden] = useState(false);
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const [failedCrop, setFailedCrop] = useState<string | null>(null);
  const [zoomedCrop, setZoomedCrop] = useState(false);
  const detailId = useId();
  const current = selected !== undefined ? selected : selection?.src === src ? selection.concept : null;
  const active = tags.find((t) => t.concept === current);
  const cropUrl = resolveCropUrl(src, active?.cropFile);
  const showCrop = !!cropUrl && failedCrop !== cropUrl;
  const select = (concept: string | null) => { setSelection({ src, concept }); setZoomedCrop(false); onSelect?.(concept); };

  // The outlines were computed on the stored pixels. When the browser would show the photo rotated (EXIF), it is shown
  // raw instead, so the outlines land where SAM drew them; the frame then takes the outlines' size.
  const m = measured?.src === src ? measured : null;
  const frame = outlineFrame(outlines);
  const rawOrientation = !!m && outlinesInRawOrientation(frame, m.nw, m.nh);
  const ready = m ? { w: rawOrientation ? frame!.w : m.nw, h: rawOrientation ? frame!.h : m.nh, ...containedRect(rawOrientation ? frame!.w : m.nw, rawOrientation ? frame!.h : m.nh, m.cw, m.ch) } : null;

  useEffect(() => {
    const i = img.current;
    if (!i) return;
    const measure = () => {
      if (!i.complete || !i.naturalWidth || !i.clientWidth || !i.clientHeight) return;
      setMeasured((prev) => {
        // The natural size is kept from the first load of a photo: once it is shown in its raw orientation, browsers
        // report the size transposed, and that must not flip the decision back.
        const same = prev?.src === src;
        const next = { src, nw: same ? prev.nw : i.naturalWidth, nh: same ? prev.nh : i.naturalHeight, cw: i.clientWidth, ch: i.clientHeight };
        return prev && (Object.keys(next) as (keyof Measured)[]).every((k) => prev[k] === next[k]) ? prev : next;
      });
    };
    i.addEventListener("load", measure);
    const observer = new ResizeObserver(measure);
    observer.observe(i);
    measure();
    return () => { i.removeEventListener("load", measure); observer.disconnect(); };
  }, [src]);

  // Repaint only when the outlines, their tones, the frame or the selection change, not on every render: the
  // hands-free view refreshes its queries every few seconds and the arrays are new each time.
  const outlineIds = outlines.map((g) => g.segmentationId).join("|");
  const toneKey = tags.map((t) => `${t.concept}=${t.tone}`).join("|");
  useEffect(() => {
    const c = canvas.current;
    if (!c || !ready) return;
    const scale = Math.min(1, MAX_CANVAS_SIDE / Math.max(ready.w, ready.h));
    c.width = Math.round(ready.w * scale); c.height = Math.round(ready.h * scale);
    const ctx = c.getContext("2d");
    if (!ctx) return;
    ctx.setTransform(scale, 0, 0, scale, 0, 0);
    const css = getComputedStyle(c);
    for (const g of outlines) {
      const tone = tags.find((t) => t.concept === g.concept)?.tone ?? "primary";
      const color = css.getPropertyValue(`--${tone}`).trim() || "#35d6e8";
      const dim = current && current !== g.concept;
      for (const mask of g.masks) {
        const tile = tintedTile(g.segmentationId, mask, color);
        if (!tile) continue;
        ctx.globalAlpha = dim ? 0.08 : 0.28;
        ctx.drawImage(tile, mask.left, mask.top, mask.right - mask.left, mask.bottom - mask.top);
      }
      ctx.globalAlpha = dim ? 0.3 : 1;
      ctx.strokeStyle = color;
      ctx.lineWidth = Math.max(3, ready.w / 320);
      ctx.setLineDash([ready.w / 90, ready.w / 140]);
      for (const b of g.boxes) ctx.strokeRect(b.left, b.top, b.right - b.left, b.bottom - b.top);
    }
    ctx.globalAlpha = 1;
  }, [outlineIds, toneKey, ready?.w, ready?.h, current]); // eslint-disable-line react-hooks/exhaustive-deps

  const pct = (v: number, of: number) => `${(100 * v) / of}%`;
  const guideBox = guide ? tags.find((t) => t.concept === guide.concept)?.box : undefined;
  const failed = failedSrc === src;

  return (
    <figure className={cn("dark relative overflow-hidden bg-overlay text-overlay-foreground", className)}>
      <div className="relative overflow-hidden" data-ar-photo>
        <img ref={img} src={src} alt="Inspection photo" className={cn("block w-full object-contain", imgClassName)} onError={() => setFailedSrc(src)}
          style={rawOrientation ? { imageOrientation: "none" } : undefined} />
        {failed && <p role="status" className="p-4 text-sm">Photo could not load. Reopen the inspection to try again.</p>}
        {ready && !failed && (
          <div data-ar-overlay className={cn("pointer-events-none absolute", hidden && "invisible")} style={{ left: ready.left, top: ready.top, width: ready.width, height: ready.height }}>
            <canvas ref={canvas} aria-hidden="true" className="absolute inset-0 size-full" />
            {guideBox && <div className="absolute rounded-lg border-2 border-dashed border-primary shadow-[0_0_0_9999px_color-mix(in_oklab,var(--overlay)_45%,transparent)]"
              style={{ left: pct(guideBox.left, ready.w), top: pct(guideBox.top, ready.h), width: pct(guideBox.right - guideBox.left, ready.w), height: pct(guideBox.bottom - guideBox.top, ready.h) }} />}
            {active && (
              <div className="absolute rounded-md border border-primary/80 ring-2 ring-primary/30 transition-all duration-200"
                style={{ left: pct(active.box.left, ready.w), top: pct(active.box.top, ready.h), width: pct(active.box.right - active.box.left, ready.w), height: pct(active.box.bottom - active.box.top, ready.h) }} />
            )}
            {tags.map((t, k) => {
              const style = { left: `clamp(22px, ${pct(t.box.left, ready.w)}, calc(100% - 22px))`, top: `clamp(22px, ${pct(t.box.top, ready.h)}, calc(100% - 22px))` };
              const pinClass = cn("absolute grid size-11 -translate-x-1/2 -translate-y-1/2 place-items-center rounded-full border-2 bg-overlay/95 text-sm font-semibold shadow-lg", tagTone[t.tone], current === t.concept && "ring-2 ring-overlay-foreground ring-offset-2 ring-offset-overlay");
              return interactive ? (
                <button key={t.concept} type="button" aria-label={`Part ${k + 1}: ${t.label}`} aria-pressed={current === t.concept} aria-controls={detailId}
                  onClick={(e) => { e.stopPropagation(); select(current === t.concept ? null : t.concept); }}
                  className={cn(pinClass, "pointer-events-auto focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-primary")} style={style}>{k + 1}</button>
              ) : <span key={t.concept} className={pinClass} style={style} aria-label={t.label}>{k + 1}</span>;
            })}
          </div>
        )}
        {children}
      </div>
      {interactive && tags.length > 0 && !failed && (
        <figcaption className="border-t border-overlay-foreground/15 bg-overlay px-3 pb-3">
          <div className="flex items-center justify-between gap-2 text-[11px]">
            <span className="text-overlay-foreground/65">AI outlines · captured photo</span>
            <button type="button" onClick={() => setHidden(!hidden)} aria-pressed={!hidden} className="flex min-h-11 shrink-0 items-center gap-1.5 rounded-lg px-2 text-xs hover:bg-overlay-foreground/10 focus-visible:outline-2 focus-visible:outline-primary">
              {hidden ? <Eye className="size-4" /> : <EyeOff className="size-4" />}{hidden ? "Show outlines" : "Hide outlines"}
            </button>
          </div>
          {guide && guideBox && <p className="mb-2 break-words text-xs text-primary">{guide.label}</p>}
          <div className="flex gap-2 overflow-x-auto pb-1" aria-label="Outlined parts">
            {tags.map((t, k) => <button key={t.concept} type="button" onClick={() => select(current === t.concept ? null : t.concept)}
              aria-pressed={current === t.concept} aria-controls={detailId}
              className={cn("flex min-h-11 shrink-0 items-center gap-2 rounded-xl border px-3 text-xs focus-visible:outline-2 focus-visible:outline-primary", tagTone[t.tone], current === t.concept ? "bg-overlay-foreground/10" : "bg-transparent")}>
              <span className="font-semibold">{k + 1}</span>{t.label}
            </button>)}
          </div>
          <div id={detailId} aria-live="polite">
            {active ? (
              <div className="mt-2 space-y-2.5 rounded-xl bg-overlay-foreground/8 p-3 text-xs leading-relaxed">
                <div className="flex items-start gap-2">
                  <div className="min-w-0 flex-1">
                    <p className="font-medium">{active.label}</p>
                    <p className="mt-1 text-overlay-foreground/80">{active.detail}</p>
                  </div>
                  <button type="button" onClick={() => select(null)} aria-label="Close part details" className="-mr-2 -mt-2 grid size-11 shrink-0 place-items-center rounded-lg focus-visible:outline-2 focus-visible:outline-primary"><X className="size-4" /></button>
                </div>
                {showCrop && cropUrl && (
                  <div className="overflow-hidden rounded-lg border border-overlay-foreground/15 bg-black/40 p-2" data-ar-loupe>
                    <div className="mb-1.5 flex items-center justify-between gap-2 text-[11px] text-overlay-foreground/75">
                      <span className="font-mono text-[10px] uppercase tracking-wider text-primary">High-Res ROI Loupe · Original pixels</span>
                      <button type="button" onClick={() => setZoomedCrop(!zoomedCrop)} aria-pressed={zoomedCrop}
                        className="inline-flex min-h-8 items-center gap-1 rounded-md border border-overlay-foreground/20 px-2 py-0.5 text-[11px] hover:bg-overlay-foreground/10">
                        {zoomedCrop ? <ZoomOut className="size-3.5" /> : <ZoomIn className="size-3.5" />}
                        {zoomedCrop ? "1× Fit" : "2× Magnify"}
                      </button>
                    </div>
                    <div className={cn("overflow-auto rounded border border-overlay-foreground/10 bg-black/60", zoomedCrop ? "max-h-56" : "max-h-32")}>
                      <img src={cropUrl} alt={`High-resolution crop of ${active.concept}`} onError={() => setFailedCrop(cropUrl)}
                        className={cn("mx-auto block transition-transform duration-200", zoomedCrop ? "w-[180%] max-w-none" : "max-h-28 w-auto object-contain")} />
                    </div>
                  </div>
                )}
              </div>
            ) : <p className="mt-2 text-[11px] text-overlay-foreground/65">Select a numbered part to read the finding.</p>}
          </div>
        </figcaption>
      )}
    </figure>
  );
}
