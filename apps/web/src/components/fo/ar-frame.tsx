import { useEffect, useRef, useState } from "react";
import { cn } from "@/lib/utils";
import { decodeRle } from "@/lib/rle";
import type { SegmentationGeometry } from "@/lib/api";
import type { ArTag, Tone } from "@/lib/ar";

const tagTone: Record<Tone, string> = {
  primary: "border-primary/50 text-primary",
  warning: "border-warning/60 text-warning",
  failure: "border-failure/60 text-failure",
  success: "border-success/50 text-success",
};

/**
 * A captured frame with the AI's outlines drawn on the original pixels and a label pinned to each outlined part.
 * Overlays sit on the still photo they were computed from; they are never projected onto a moving camera feed.
 * `guide` darkens everything except the outlined part the AI wants a better view of.
 */
export function ArFrame({ src, outlines, tags, guide, selected, onSelect, className, imgClassName, children }: {
  src: string; outlines: SegmentationGeometry[]; tags: ArTag[]; guide?: { concept: string; label: string } | null;
  selected?: string | null; onSelect?: (concept: string | null) => void; className?: string; imgClassName?: string; children?: React.ReactNode;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const img = useRef<HTMLImageElement>(null);
  const [size, setSize] = useState<{ w: number; h: number } | null>(null);

  useEffect(() => {
    const c = canvas.current;
    if (!c || !size) return;
    c.width = size.w; c.height = size.h;
    const ctx = c.getContext("2d")!;
    ctx.clearRect(0, 0, size.w, size.h);
    const css = getComputedStyle(c);
    for (const g of outlines) {
      const tone = tags.find((t) => t.concept === g.concept)?.tone ?? "primary";
      const color = css.getPropertyValue(`--${tone}`).trim() || "#35d6e8";
      const dim = selected && selected !== g.concept;
      for (const m of g.masks) {
        const [mh, mw] = m.rle.size;
        const bits = decodeRle(m.rle.counts, mh, mw);
        const tile = document.createElement("canvas");
        tile.width = mw; tile.height = mh;
        const t = tile.getContext("2d")!;
        t.fillStyle = color;
        for (let y = 0; y < mh; y++) for (let x = 0; x < mw; x++) if (bits[y * mw + x]) t.fillRect(x, y, 1, 1);
        ctx.globalAlpha = dim ? 0.12 : 0.34;
        ctx.drawImage(tile, m.left, m.top, m.right - m.left, m.bottom - m.top);
      }
      ctx.globalAlpha = dim ? 0.35 : 1;
      ctx.strokeStyle = color;
      ctx.lineWidth = Math.max(3, size.w / 320);
      ctx.setLineDash([size.w / 90, size.w / 140]);
      for (const b of g.boxes) ctx.strokeRect(b.left, b.top, b.right - b.left, b.bottom - b.top);
    }
    ctx.globalAlpha = 1;
  }, [outlines, tags, size, selected]);

  const pct = (v: number, of: number) => `${(100 * v) / of}%`;
  const guideBox = guide && size ? tags.find((t) => t.concept === guide.concept)?.box : undefined;
  const active = tags.find((t) => t.concept === selected);

  return (
    <figure className={cn("relative overflow-hidden", className)} onClick={() => onSelect?.(null)}>
      <img ref={img} src={src} alt="Inspection photo" className={cn("block w-full", imgClassName)}
        onLoad={(e) => setSize({ w: e.currentTarget.naturalWidth, h: e.currentTarget.naturalHeight })} />
      <canvas ref={canvas} className={cn("pointer-events-none absolute inset-0 size-full animate-mask", imgClassName)} />
      {size && guideBox && (
        <div className="pointer-events-none absolute animate-mask rounded-lg border-2 border-dashed border-primary shadow-[0_0_0_9999px_color-mix(in_oklab,var(--overlay)_55%,transparent)]"
          style={{ left: pct(guideBox.left, size.w), top: pct(guideBox.top, size.h), width: pct(guideBox.right - guideBox.left, size.w), height: pct(guideBox.bottom - guideBox.top, size.h) }}>
          <span className="absolute inset-0 rounded-lg animate-ring" />
        </div>
      )}
      {size && guideBox && (
        // Outside the box so the reveal animation's clip never cuts the caption.
        <span className="glass pointer-events-none absolute whitespace-nowrap rounded-md border border-primary/50 px-2 py-1 text-[10px] font-medium text-primary"
          style={{ left: `calc(${pct(guideBox.left, size.w)} + 4px)`, top: `calc(${pct(guideBox.bottom, size.h)} - 1.75rem)` }}>{guide!.label}</span>
      )}
      {size && tags.map((t, k) => {
        const above = t.box.top / size.h > 0.12;
        return (
          <button key={t.concept} type="button" onClick={(e) => { e.stopPropagation(); onSelect?.(selected === t.concept ? null : t.concept); }}
            className={cn("glass absolute flex max-w-[70%] items-center gap-1.5 truncate rounded-md border px-2 py-1 text-[10.5px] font-medium animate-rise", tagTone[t.tone], selected === t.concept && "ring-2 ring-primary/60")}
            style={{
              left: `min(${pct(t.box.left, size.w)}, 62%)`,
              top: above ? `calc(${pct(t.box.top, size.h)} - 1.9rem)` : `calc(${pct(t.box.bottom, size.h)} + 0.35rem)`,
              animationDelay: `${0.5 + k * 0.15}s`,
            }}>
            <span className="size-1.5 shrink-0 rounded-full bg-current" />
            <span className="truncate">{t.label}</span>
          </button>
        );
      })}
      {active && (
        <figcaption className="glass absolute inset-x-2 bottom-2 animate-rise rounded-lg border border-border p-2.5 text-xs" onClick={(e) => e.stopPropagation()}>
          <div className="font-medium">{active.label}</div>
          <div className="mt-0.5 opacity-80">{active.detail}</div>
        </figcaption>
      )}
      {children}
    </figure>
  );
}
