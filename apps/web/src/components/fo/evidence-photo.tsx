import { useEffect, useRef } from "react";
import { cn } from "@/lib/utils";
import { decodeRle } from "@/lib/rle";
import type { SegmentationGeometry } from "@/lib/api";

/**
 * A stored photo with SAM's outline drawn on the original pixels. The overlay is the model's interpretation and is
 * labelled as such by the caller; nothing is drawn when SAM found no match.
 */
export function EvidencePhoto({ src, geometry, tone = "primary", className, imgClassName, children }: {
  src: string; geometry?: SegmentationGeometry | null; tone?: "primary" | "success" | "warning" | "failure"; className?: string; imgClassName?: string; children?: React.ReactNode;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const img = useRef<HTMLImageElement>(null);

  useEffect(() => {
    const c = canvas.current, i = img.current;
    if (!c || !i) return;
    const draw = () => {
      const w = i.naturalWidth, h = i.naturalHeight;
      if (!w || !h) return;
      c.width = w; c.height = h;
      const ctx = c.getContext("2d")!;
      ctx.clearRect(0, 0, w, h);
      if (!geometry?.matched) return;
      const color = getComputedStyle(c).getPropertyValue(`--${tone}`).trim() || "#35d6e8";
      for (const m of geometry.masks) {
        const [mh, mw] = m.rle.size;
        const bits = decodeRle(m.rle.counts, mh, mw);
        const tile = document.createElement("canvas");
        tile.width = mw; tile.height = mh;
        const tctx = tile.getContext("2d")!;
        tctx.fillStyle = color;
        tctx.globalAlpha = 0.38;
        for (let y = 0; y < mh; y++) for (let x = 0; x < mw; x++) if (bits[y * mw + x]) tctx.fillRect(x, y, 1, 1);
        ctx.drawImage(tile, m.left, m.top, m.right - m.left, m.bottom - m.top);
      }
      ctx.strokeStyle = color;
      ctx.lineWidth = Math.max(3, w / 320);
      ctx.setLineDash([w / 90, w / 140]);
      for (const b of geometry.boxes) ctx.strokeRect(b.left, b.top, b.right - b.left, b.bottom - b.top);
    };
    if (i.complete) draw(); else i.onload = draw;
  }, [src, geometry, tone]);

  return (
    <figure className={cn("relative overflow-hidden", className)}>
      <img ref={img} src={src} alt="Inspection photo" className={cn("block w-full", imgClassName)} />
      <canvas ref={canvas} className={cn("pointer-events-none absolute inset-0 size-full animate-mask", imgClassName)} />
      {children}
    </figure>
  );
}
