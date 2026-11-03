import { expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { LiveOverlay } from "./live-overlay";

const feedback = {
  objects: [{ id: "a", concept: "yellow cable", label: "Yellow cable", box: { left: 0.1, top: 0.2, right: 0.5, bottom: 0.6 } }] as never,
  status: "", recommendation: "", updatedAt: null, width: 1920, height: 1080, fps: 0, telemetry: null, screenText: null,
};

it("fits the boxes the way the video is fitted: letterboxed by default, centre-cropped for a covering video", () => {
  expect(renderToStaticMarkup(<LiveOverlay {...feedback} />)).toContain('preserveAspectRatio="xMidYMid meet"');
  expect(renderToStaticMarkup(<LiveOverlay {...feedback} fit="cover" />)).toContain('preserveAspectRatio="xMidYMid slice"');
});
