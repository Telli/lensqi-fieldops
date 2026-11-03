import type { LiveFeedback } from '@/lib/use-live-assist';
import type { LiveObject } from '@/lib/live-tracker';
import { cn } from '@/lib/utils';

/** Seconds since the server last confirmed a box, or null when it has not told us. */
export function boxAge(detectedAt: Record<string, number> | undefined, id: string, now: number): number | null {
  const at = detectedAt?.[id];
  return at === undefined ? null : Math.max(0, Math.round((now - at) / 1000));
}

/** Older boxes fade: between detections they are only tracked on the device, and the technician should know how fresh they are. */
export const boxOpacity = (age: number | null) => (age === null || age < 4 ? 1 : age < 8 ? 0.75 : 0.45);

/**
 * Tracked boxes drawn over the live video, in the video frame's pixels. `fit` must match the video's `object-fit`:
 * a contained video letterboxes and a covering one centre-crops, and the boxes only land on the parts when the SVG
 * does the same. Every label is marked as AI; a box that has gone a while without the server confirming it fades and
 * says how long ago it was seen. With `onSelect`, tapping a box hands it to the page (to ask about that part).
 */
export function LiveOverlay({ objects, width, height, targetConcept, fit = "contain", detectedAt, onSelect, selectedId }: LiveFeedback & {
  targetConcept?: string; fit?: "contain" | "cover"; onSelect?: (o: LiveObject) => void; selectedId?: string | null;
}) {
  if (!width || !height) return null;
  const now = typeof performance !== "undefined" ? performance.now() : 0;
  return (
    <svg aria-label="Live object tracking" className={cn("absolute inset-0 size-full", !onSelect && "pointer-events-none")} viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio={fit === "cover" ? "xMidYMid slice" : "xMidYMid meet"} style={{ pointerEvents: onSelect ? undefined : "none" }}>
      {objects.map((o, n) => {
        const x = o.box.left * width, y = o.box.top * height;
        const w = (o.box.right - o.box.left) * width, h = (o.box.bottom - o.box.top) * height;
        const isTarget = !!targetConcept && o.concept.toLowerCase() === targetConcept.toLowerCase();
        const selected = selectedId === o.id;
        const stroke = isTarget ? "#f5b83d" : "#35d6e8";
        const c = Math.max(12, Math.min(w, h) * 0.22);
        const corners = `M ${x} ${y + c} L ${x} ${y} L ${x + c} ${y} M ${x + w - c} ${y} L ${x + w} ${y} L ${x + w} ${y + c} M ${x} ${y + h - c} L ${x} ${y + h} L ${x + c} ${y + h} M ${x + w - c} ${y + h} L ${x + w} ${y + h} L ${x + w} ${y + h - c}`;
        const age = boxAge(detectedAt, o.id, now);
        return (
          <g key={o.id} opacity={boxOpacity(age)} onClick={onSelect ? () => onSelect(o) : undefined} role={onSelect ? "button" : undefined} tabIndex={onSelect ? 0 : undefined}
            onKeyDown={onSelect ? (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onSelect(o); } } : undefined}
            aria-label={onSelect ? `Ask about the ${o.label}` : undefined} style={onSelect ? { pointerEvents: "all", cursor: "pointer" } : undefined}>
            <rect x={x} y={y} width={w} height={h}
              fill={isTarget ? "rgba(245, 184, 61, 0.08)" : "rgba(53, 214, 232, 0.06)"}
              stroke={stroke} strokeOpacity={selected ? 1 : 0.55} strokeDasharray={selected ? undefined : "8 6"} strokeWidth={selected ? 3 : 2} vectorEffect="non-scaling-stroke"
              className="transition-[x,y,width,height] duration-100 ease-out" />
            <path d={corners} fill="none" stroke={stroke} strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
            <text x={x + 6} y={Math.max(24, y - 10)} fill="#fff" stroke="#071014" strokeWidth="4" paintOrder="stroke" fontSize={Math.max(20, width / 40)}>
              {n + 1}. {o.label} · AI{isTarget ? " · target" : ""}{age !== null && age >= 3 ? ` · seen ${age}s ago` : ""}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
