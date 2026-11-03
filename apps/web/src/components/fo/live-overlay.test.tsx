import { expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { boxAge, boxOpacity, LiveOverlay } from "./live-overlay";

const feedback = {
  objects: [{ id: "a", concept: "yellow cable", label: "Yellow cable", box: { left: 0.1, top: 0.2, right: 0.5, bottom: 0.6 } }] as never,
  status: "", recommendation: "", updatedAt: null, width: 1920, height: 1080, fps: 0, telemetry: null, screenText: null, detectedAt: {},
};

it("fits the boxes the way the video is fitted: letterboxed by default, centre-cropped for a covering video", () => {
  expect(renderToStaticMarkup(<LiveOverlay {...feedback} />)).toContain('preserveAspectRatio="xMidYMid meet"');
  expect(renderToStaticMarkup(<LiveOverlay {...feedback} fit="cover" />)).toContain('preserveAspectRatio="xMidYMid slice"');
});

it("marks every label as AI and says how long ago an ageing box was last confirmed, fading it", () => {
  const html = renderToStaticMarkup(<LiveOverlay {...feedback} detectedAt={{ a: performance.now() - 6200 }} />);
  expect(html).toContain("Yellow cable · AI · seen 6s ago");
  expect(html).toContain('opacity="0.75"');
  expect(boxAge({ a: 1000 }, "a", 4200)).toBe(3);
  expect(boxAge({}, "a", 4200)).toBeNull();
  expect([boxOpacity(null), boxOpacity(2), boxOpacity(5), boxOpacity(12)]).toEqual([1, 1, 0.75, 0.45]);
});

it("boxes can be tapped only when the page asks to know", () => {
  expect(renderToStaticMarkup(<LiveOverlay {...feedback} />)).not.toContain('role="button"');
  const tappable = renderToStaticMarkup(<LiveOverlay {...feedback} onSelect={() => undefined} />);
  expect(tappable).toContain('aria-label="Ask about the Yellow cable"');
  expect(tappable).toContain('tabindex="0"');
});
