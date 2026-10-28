import type { LiveFeedback } from '@/lib/use-live-assist';

export function LiveOverlay({ objects, width, height, targetConcept }: LiveFeedback & { targetConcept?: string }) {
  if (!width || !height) return null;
  return (
    <svg aria-label="Live object tracking" className="pointer-events-none absolute inset-0 size-full" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="xMidYMid meet">
      {objects.map((o, n) => {
        const x = o.box.left * width, y = o.box.top * height;
        const w = (o.box.right - o.box.left) * width, h = (o.box.bottom - o.box.top) * height;
        const isTarget = !!targetConcept && o.concept.toLowerCase() === targetConcept.toLowerCase();
        const stroke = isTarget ? "#f5b83d" : "#35d6e8";
        const c = Math.max(12, Math.min(w, h) * 0.22);
        const corners = `M ${x} ${y + c} L ${x} ${y} L ${x + c} ${y} M ${x + w - c} ${y} L ${x + w} ${y} L ${x + w} ${y + c} M ${x} ${y + h - c} L ${x} ${y + h} L ${x + c} ${y + h} M ${x + w - c} ${y + h} L ${x + w} ${y + h} L ${x + w} ${y + h - c}`;
        return (
          <g key={o.id}>
            <rect x={x} y={y} width={w} height={h}
              fill={isTarget ? "rgba(245, 184, 61, 0.08)" : "rgba(53, 214, 232, 0.06)"}
              stroke={stroke} strokeOpacity={0.55} strokeDasharray="8 6" strokeWidth="2" vectorEffect="non-scaling-stroke"
              className="transition-[x,y,width,height] duration-100 ease-out" />
            <path d={corners} fill="none" stroke={stroke} strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
            <text x={x + 6} y={Math.max(24, y - 10)} fill="#fff" stroke="#071014" strokeWidth="4" paintOrder="stroke" fontSize={Math.max(20, width / 40)}>
              {n + 1}. {o.label}{isTarget ? " · target" : ""}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
