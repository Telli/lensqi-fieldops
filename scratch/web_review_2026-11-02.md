# Web client review — 2 November 2026

Scope: `apps/web` on `main` at `c8f8d34` (after PRs 12 and 13). Every finding was checked against the code and, where it mattered, the server in `src/`. `npm test` (37 tests, 10 files) and `npm run build` pass; the build reproduced the committed `wwwroot` exactly. Nothing was exercised in a browser for this review.

## 1. Feature completeness

### Against PRD §9 (Web) and the guided-tasks spec

| Area | State | Notes |
| --- | --- | --- |
| Mode home `/m`, new task, plan, step runner, done, free-form `/live` | Built | Matches the route list. The doc says `?mode=diy\|tech`; the code only recognises `mode=Technician` (anything else is DIY). Links use `Technician`, so only the docs are wrong. |
| Technician home `/m/tech`, start, inspections, assets, asset detail, profile | Built | |
| Inspection hub, scan (camera), glasses, asset, knowledge, diagnostics, verify, receipt | Built | Verify follows `readiness.next` exactly as Android does (`verify.ts` is the reference the Android port mirrors). |
| Ops: overview, guided tasks, inspections + workspace, assets, work orders, knowledge packs, FieldProof reports | Built | Reports re-hash every listed receipt on the server. |
| Evidence requests from ops, answered by the technician's next photo | Built | |
| Prediction before the check (VK-T27) | Built | Web only; Android has no equivalent. |
| Before/after compare slider with outlines | Built | Web only. |
| Offline | Not built, by design | Web is the P0 online surface; `ConnBadge` only reflects `navigator.onLine`. |
| Cancel for 5–20 s model calls | **Missing** | PRD §9 asks for progress and cancel. Investigate, segment, step check, ask and verify have no cancel; `request()` takes no `AbortSignal`. Android has one. |
| Asset label scan | Missing | Same gap Android had; `BarcodeDetector` exists on Chrome Android. |
| Verify preflight, attempt history, clock-skew warning, knowledge source sheet | Missing | Added to Android in PR 13; web shows only attempt dots and title/section citations. |

### Documentation drift
- PRD §9 and `.kiro/specs/guided-tasks/design.md` §199 say `?mode=diy|tech`; the parameter is `Technician`.
- `design.md` §24 says every web POST gets a fresh key "so a client retry is a new command"; that is now true only for inspection commands (`tasks.ts` keeps keys per command), and Android moved away from it in PR 12.

## 2. Bugs and business-logic errors

### High

**W1. Live boxes misaligned on the verify camera.** `components/fo/verify-camera.tsx:110` renders the video `object-cover` in a 3:4 box, while `components/fo/live-overlay.tsx:6` draws the SVG with `preserveAspectRatio="xMidYMid meet"` (contain). A 16:9 or 9:16 stream is cropped by the video but letterboxed by the overlay, so every tracked box is scaled and offset on the one camera that frames post-repair evidence. `scan.tsx`, `step.tsx` and `live.tsx` use `object-contain` and are right. Fix: `object-contain` on the verify video (or compute the cover transform in the overlay).

### Medium

**W2. No cancel, and chains outlive the page.** `lib/api.ts:118` fetches without a signal. `pages/m/scan.tsx:87-119` runs upload → investigate → one segment per prompt with no cancel; Close navigates away but the chain keeps running, toasting on the next page and setting state on an unmounted component. Same for step check, ask and verify. Fix: thread an `AbortController` through `request`, abort on unmount, add a Cancel beside the progress lines (the server ignores a cancel after the model answers, as the Android copy says).

**W3. Inspection commands are not retry-safe.** Every inspection POST mints a fresh key (`request` → `newKey()`), so a verify that times out at the browser and is tapped again records a second attempt, and a claim or review can be duplicated. `tasks.ts` already keeps one key per command until a final answer (`once()`); inspection commands should do the same, scoped as Android now does (claim, accepted photo, review answers, never the event sequence). The spec records the fresh-key rule as a decision, so update it when changing this.

**W4. Task keys are scoped too coarsely.** `tasks.ts:87-90` keys a step check by step only, and `ask` by question only. After a retryable failure (5xx, failed model call) a *new* photo for the same step reuses the old key, and the server re-checks the photo it saved first (that is the documented GT-T32 behaviour for a replayed key), so the new photo is silently ignored; likewise a repeated question with a new frame replays the old frame's answer. Fix: include a digest of the photo or frame bytes in the key (Android does this for `ask`).

**W5. EXIF orientation is never reconciled.** `components/fo/ar-frame.tsx:54-56` maps server pixel geometry through `naturalWidth/Height`, which the browser reports after EXIF rotation; the server stores pre-rotation sizes (`EvidenceFileStore.cs:22`) and nothing in `src/` auto-orients before SAM. A portrait gallery photo (orientation 6) imported on `/m/i/:id/scan` draws rotated and misplaced outlines. Canvas shutter captures carry no EXIF and are fine. Fix: auto-orient server-side before storage or segmentation (fixes Android too), or render with `image-orientation: none` when geometry is in raw space.

**W6. Fake progress on a real measurement.** `pages/m/diagnostics.tsx` animates a percentage (`+14 every 160 ms, capped at 92`) while the probe runs. Nothing measures progress; the figure is invented, on the one screen whose whole point is a measured result. Fix: an indeterminate bar with elapsed seconds.

**W7. Step route edge cases.** `pages/m/task/step.tsx:43,76`: an unknown or stale `stepId` leaves `step` undefined and the page spins forever (the redirect at `:73` fires only when `stepId` is absent), and `tasks.start` failures are swallowed, so the server stays on another step while live coaching and questions target it. Fix: redirect to `currentStepId` when nothing matches; toast on start failure.

**W8. Whole-photo repaint on every render.** `ar-frame.tsx:66-100` re-decodes every RLE mask and repaints a natural-size canvas whenever `outlines` or `tags` change identity, and both are fresh arrays each render (`ar.ts:40`); `glasses.tsx:36-41` invalidates three queries every 4 s, so the hands-free page repaints a 12 MP canvas every few seconds. Fix: memoise by segmentation ids. Related: iOS Safari caps a canvas near 16.7 MP while the server accepts 50 MP, so a large gallery photo silently draws no overlay; cap the canvas at ~4096 px and scale.

**W9. Non-JSON error bodies crash the error path.** `lib/api.ts:122` does `JSON.parse(text)` on every non-empty body; a 502 HTML page from a proxy throws a `SyntaxError` that is not an `ApiError`, so the toast reads "Unexpected token <". Fix: try/parse and fall back to `http_<status>`.

### Low

- **W10.** Step checks send no `source`; on a laptop the file-picker fallback is recorded as a Phone capture (`tasks.ts:87`), while `scan.tsx` and `verify-camera.tsx` apply the `pointer: coarse` heuristic. The server now accepts `source` on that route.
- **W11.** Live boxes and "On screen: …" text carry no AI label (`live-overlay.tsx:21`, `step.tsx:185`), unlike the stored-photo frame's caption.
- **W12.** `AttemptResult` treats a check with no measurement (`passed === null`) as failed (`verify.tsx` "What was measured" `every(m => m.passed === true)`); Android distinguishes unavailable from failed.
- **W13.** `plainReason` and `friendlyError` cover fewer server reasons and codes than the Android copies (no "still being compared", "version mismatch", "not allowed by the fixture policy", `invalid_measured`, `client_contract_mismatch`), so raw policy text reaches the banner in those cases.
- **W14.** Push-to-talk can stick on "listening": `voice.ts:118-120` sets state before `r.start()`, which is outside try/catch, so a throwing `start()` never fires `onend`. TTS also keeps speaking while the recogniser listens (`step.tsx:82`, `live.tsx:55`), so it hears itself; Android holds speech.
- **W15.** Ended camera tracks are not handled (`camera.ts`): iOS ends tracks after backgrounding and the preview goes black while live stays "on"; only `scan` and `verify-camera` offer "Try camera again".
- **W16.** `step.tsx:240` accepts `image/*`, so iOS may hand over HEIC, which the server refuses; the other pickers use `image/jpeg,image/png`.
- **W17.** `NewTask` saves `heightCm: null` over a previously saved height when the text is unparseable, and still submits.
- **W18.** `Start` auto-creates an inspection for `?asset=` inside an effect that StrictMode double-invokes in dev, creating two inspections (production is unaffected).
- **W19.** `Toaster theme="dark"` is hard-coded, so toasts stay dark in light mode. `oklch()` colours passed to canvas draw black on Chrome < 111 and old WebViews.
- **W20.** The secure-context message never says HTTPS is required, so `http://<lan-ip>` users cannot tell what to change.
- **W21.** `Reports` fires up to 40 receipt re-hash requests at once on open.

## 3. What holds up well

- Stage, decision and labels come only from the server; `verify.ts` reads the attempt from the event log and the card from `readiness.next`.
- Live boxes are normalised server-side and validated in `LiveTracker.seed`; no distance is ever shown (the browser has no depth and says so).
- Simulated outcomes are labelled in spoken and visual guidance; mock glasses photos are named as simulated in the ops workspace.
- `connectCamera` stops late-arriving streams; the worker, intervals, aborts and object URLs are released on unmount.
- The RLE decoder matches the COCO reference (delta rule, column-major), though nothing tests it.
- `tasks.ts` `once()` keeps a key until a final answer and releases it on success.

## 4. Test coverage gaps

Covered: AI copy, asset match, AR tag attachment, camera stream lifecycle, labels, live tracker, person height parsing, task retry keys, voice grammar, verify flow. Not covered: `rle.ts`, `use-live-assist` (frame-id matching, busy/due gating, 429 Retry-After), ArFrame and LiveOverlay mapping, `useSpeak`/`usePushToTalk`, `request()` error paths, any page.

## 5. Improvements with real value

1. **Cancel and abort everywhere a model is called**, with elapsed time instead of a fake percentage.
2. **Retry-safe inspection commands** with state-scoped keys, matching Android.
3. **Verify preflight and attempt history** on the web verify page (ports of `verifyPreflight` and `attemptHistory`).
4. **Auto-orient uploads on the server** so both clients draw outlines on the pixels SAM saw.
5. **`ImageCapture.takePhoto()`** on Chrome Android for a true full-resolution still instead of a ≤1080p video frame, with the capture resolution shown.
6. **Keep the last capture and its key in IndexedDB** so scan can "try again with this photo" after a dropped connection.
7. **Tap a live box to ask about that part**, passing its concept as focus (the guide endpoint accepts it); age-fade tracked boxes and show "seen Ns ago".
8. **Label scan** via `BarcodeDetector` with the same site-record matching rule as Android.
9. **"Why not accepted?" sheet** on a rejected repair photo showing the reference photo side by side (`referencePhotoEvidenceId` is in the response).
10. **Hands-free mode**: re-arm recognition after each utterance and hold speech while listening.
