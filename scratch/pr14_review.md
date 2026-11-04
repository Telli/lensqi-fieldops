# Review of PR #14: Fix the web review findings: live-box fit, cancel, retry-safe keys, orientation, repaint (W1–W9)

## Summary & Architectural Evaluation

This pull request addresses the full set of web client review findings (W1–W9), improving camera overlay alignment, model request cancellation, idempotency key retention, orientation handling for gallery photos, and canvas rendering performance on mobile browsers:

- **Live Tracking Overlay Fit (W1):** `LiveOverlay` now accepts a `fit` parameter (`contain` | `cover`). `VerifyCamera` passes `cover` (`preserveAspectRatio="xMidYMid slice"`), resolving the misalignment where a contain-fit SVG overlay was positioned over an `object-cover` video element.
- **Abortable Model Calls & Cancellation UX (W2):**
  - `request()` accepts an optional `AbortSignal` and throws `ApiError(0, "cancelled", "Cancelled.")` on abort.
  - Scan, verify camera, look, verify, diagnostic probe, and task check/ask flows wire up `AbortController`, abort automatically on component unmount, display elapsed seconds during pending operations, and gracefully suppress error toasts upon cancellation.
- **Retry-Safe Idempotency Keys (W3 & W4):**
  - Extracted and centralized `once()` in `apps/web/src/lib/api.ts`.
  - Scopes inspection command keys via `commandScope(i)` (carrying `currentClaimId`, `acceptedPostRepairPhotoId`, checklist answers, and confirmation state), ensuring retries replay the recorded outcome rather than creating duplicated server events or second verify attempts.
  - Task photo checks and live asks compute a SHA-256 byte digest (`digestOf`), preventing retries with a new photo from colliding with the first photo's key.
  - Server-side support: `POST /v1/inspections` checks `store.FindCreated(key)` to safely return earlier created inspections upon retried calls (including across concurrent requests and server restarts).
- **EXIF Rotation Alignment (W5):**
  - `ArFrame` detects when browser EXIF rotation transposes display dimensions relative to the unrotated pixel frame that SAM computed masks on. In those cases, it sets `imageOrientation: "none"` to align outlines with raw pixels.
  - Server records `photoWidth` and `photoHeight` in `SegmentationEndpoints` and `ChangeEvidenceService.SaveGeometryAsync`.
- **Diagnostics Progress & Transparency (W6):** Replaced synthetic percentage increments with an indeterminate progress bar, elapsed seconds timer, and a cancel action.
- **Canvas Memory & Repaint Performance (W8):**
  - `ArFrame` introduces `MAX_CANVAS_SIDE = 4096` to stay within iOS/Safari canvas memory limits.
  - Replaces per-render RLE mask re-decoding with a byte-budgeted LRU tile cache (`TILE_BUDGET_BYTES = 64MB`).
  - Gated repaint triggers on outline IDs, tones, and frame dimensions, eliminating wasteful repaints on polling intervals.
- **Defensive Error Body Parsing (W9):** Handles proxy HTML error pages and cut-off bodies gracefully, returning `bad_response` rather than throwing an unhandled `SyntaxError`.

---

## Code Quality & Verification

- **Web Unit Tests (`npm test`):** All 51 tests pass (including 13 new unit tests covering cancellations, HTML error bodies, kept verify/claim keys, scope ordering, photo-digest keys, overlay fit, and orientation helpers).
- **Web Typecheck & Build (`npm run build`):** TypeScript checks pass and Vite production bundle rebuilds cleanly into `wwwroot`.
- **.NET Tests (`dotnet test`):** All 312 tests pass (including `A_retried_create_returns_the_inspection_the_first_attempt_made` and `A_create_retried_after_a_restart_still_returns_the_inspection_its_key_made`).
- **Secret Scan (`scripts/secret_scan.sh`):** PASS.

**Recommendation:** Approve. PR #14 resolves the web client review items comprehensively and adheres strictly to repository invariants.
