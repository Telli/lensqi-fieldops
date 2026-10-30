# AR and UX improvements — 2026-10-28

Follow-up: continuous camera assistance is now implemented in the browser and native Android phone camera. See [Live AR](LIVE_AR.md) for the architecture, measured results and remaining hardware validation. The photo-review findings below describe the earlier pass.

This pass covers the web camera, hands-free view, inspection hub and shared photo overlays used by technician and operations screens. Changes are on `codex/ar-ux-improvements`. Review focused on interaction defects, evidence identity and truthful guidance; this is not a whole-repository audit.

## Implemented

| Finding | Before | Result |
| --- | --- | --- |
| AR-01 · Photo identity | Outlining defaulted to whichever photo was newest when each request ran. Findings joined by feature name even when they cited another photo. | Requests name the captured photo; mismatched geometry is ignored. Findings and change verdicts require the same source photo. |
| AR-02 · Usable annotations | Labels could clip at the photo edge, several views rendered inert buttons, and the camera flash layer intercepted input. | Numbered 44px markers, independently selectable part labels, readable reasons below the image, keyboard focus states and an original-photo toggle. Shared overlays manage selection when the caller does not. Linked home previews remain noninteractive. |
| AR-03 · Overlay registration | Height-constrained photos used object-contain, but labels and masks spanned the full element including letterboxing. | ResizeObserver measures the contained image rectangle; every overlay uses the same origin and scale. Target shading stays inside the photo, and dark photo surfaces retain readable colors in light mode. |
| AR-04 · Camera ownership and recovery | Delayed permission could leave a camera stream active after navigation; denied access had no retry explanation; preview object URLs accumulated. | Late streams stop immediately; review releases the camera; retake reacquires it. Permission/device errors explain recovery, with a retry action. Object URLs are revoked. |
| AR-05 · Capture and review flow | The photo and result sheet overlapped; requesting a closer view still led to a generic Continue action. | Scrollable photo review and separate result controls; direct Capture the requested view and Take another photo actions; progress text reflects the current operation. |
| AR-06 · Guidance and voice | Suspected faults were called confirmed; simulated completion sounded live; closed inconclusive inspections requested more photos. Voice could continue after leaving. | Separate suspected/confirmed guidance, simulated completion labels, closed-inspection guidance, voice opt-in and cancellation on mute/navigation. Hands-free loading failures have a retry path and connection failures identify cached state. |

## Verification

- `npm run typecheck`, `npm test`, `npm run build`: pass; **15 web tests**. Five new regression cases failed against the original photo/status logic before its correction. Removing the camera disposal guard and the segmentation photo ID each caused their regression test to fail; both mutations were restored.
- `dotnet test --nologo`: **163 passed** (74 Core, 16 Meta, 73 API). An existing duplicate-using warning remains in `InspectionApiTests.cs`. No database changes or containers were needed.
- `scripts/secret_scan.sh` and `git diff --check`: pass.
- `scripts/ar_ux_smoke.mjs`: browser checks cover permission retry, capture, explicit source-photo requests, rejection of foreign geometry, selecting/dismissing parts, hiding/showing outlines, requested-view recapture, voice opt-in/mute, 320px and landscape widths, actual letterboxing at 1000px, light appearance and simulated completion.
- Browser checks replay repository API fixtures and synthetic equipment photos. They do not call model providers, change existing inspection records, or demonstrate real glasses/camera hardware behavior.

To repeat the browser checks, run a local web server and supply Playwright if it is outside this repository:

```sh
AR_SMOKE_URL=http://127.0.0.1:5199 \
AR_SMOKE_CHANNEL=chrome \
PLAYWRIGHT_MODULE=/absolute/path/to/playwright/index.mjs \
node scripts/ar_ux_smoke.mjs
```

Omit `AR_SMOKE_CHANNEL` to use Playwright's bundled Chromium. Screenshots are regenerated in [evidence/ar-ux](evidence/ar-ux/README.md).

## Remaining opportunities

- Native Android parity: its `ArOverlay.kt` still joins findings by feature alone and positions text chips on the image. Apply photo identity checks and the numbered-marker interaction there, then verify with emulator and glasses capture. Native Android was inspected briefly but not changed or built in this pass.
- Add photo zoom/pan for reading port labels while preserving the shared image-to-overlay transform.
- Expose an explicit photo ID for the investigation endpoint too. This pass filters assessments that cite another photo; segmentation already supports an explicit source ID on the server.
- Real live AR tracking needs frame identity, calibration and on-device testing; current overlays intentionally annotate captured photos.
