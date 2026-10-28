---
name: fieldops-android
description: Plan and rules for the P1 Android (Kotlin/Compose) FieldOps client against the existing inspection API, including offline-first capture, provenance labelling, and what must not be claimed. Load before creating or editing anything under apps/android.
---

# FieldOps Android client (Track B — starts October 28 in parallel with the backend; see docs/OPUS_HANDOFF_PLAN.md)

## Non-negotiables
- The app never decides a stage or an outcome; it renders `GET /v1/inspections/{id}` and sends commands. Every banner text comes from the API decision.
- Camera capture is user-triggered, full resolution preserved on device, uploaded as the original JPEG with `source=Phone`, `observedAt` (device time) and dimensions. The server timestamp wins for freshness.
- Labels: photo (evidence), Muse (inferred), SAM (inferred), probe (measured, with LIVE / SIMULATED / REPLAY badge), documentation (documented / site configured). Never show a simulated result without the badge.
- No private LensIQ source. Reimplement from behaviour: frame source interface, permission handling for camera only, Retrofit client, TTS (P1 voice).
- Do not claim an Android build in any document, video or ledger row until it runs on a device or emulator with screenshots in `docs/evidence/android/`. Android is never on the critical path: a missed gate drops the Android segment, it is never faked.
- Work in your own git worktree and API port (`--urls http://127.0.0.1:5201`, `Storage__DataDirectory` of your own); the emulator reaches the host at `10.0.2.2:<port>`. Do not run `scripts/ledger_add.py`; the integrator does after merging.

## Stack
Kotlin, Jetpack Compose, CameraX (`ImageCapture` full-res JPEG), Hilt, Retrofit + kotlinx.serialization (string enums), Room for the offline queue, WorkManager for sync, DataStore for settings. Package `com.lensqi.fieldops`. Min SDK 26, target latest.

## Screens (PRD §9)
1. Select fixture: `GET /v1/fixtures`, QR scan optional (ML Kit barcode) → asset select → `POST /v1/inspections`.
2. Capture: preview + shutter; thumbnail strip from state photos; upload with `Idempotency-Key = <uuid per capture>`.
3. Investigate & reticle: `POST /investigate` result with status badges; `POST /segment` → draw box/mask on the original (decode COCO RLE; the JS decoder in `wwwroot/app.js` is the reference); crop viewer.
4. Verify & receipt: work order, claim, checklist per `visualRequirements`, acknowledge, verify; banner from the decision; open `receipt.html` in a WebView; share the JSON bundle.

## Offline-first
- Captures and commands go into a Room queue with the idempotency key; WorkManager replays them in order when online. Server replies replace local state; conflicts (409) surface as-is with the API code.
- While offline the UI shows "awaiting upload" and never a provisional verification. Cached knowledge facts show a `Cached` label with the pack digest.

## API contract notes
- All enums are PascalCase strings. 409 bodies are `{code, message}`; show the code to the technician.
- Model calls can take 5–20 s; show progress and allow cancel (the server ignores cancellation after the model returns; the event is appended only on success).
- Artifacts: `GET /v1/inspections/{id}/artifacts/{name}` for photos and crops.

## Daily gates (Track B)
Oct 28 emulator hits `/health` · Oct 29 photo from the emulator appears in the API state · Oct 30 Muse assessment + SAM overlay screenshot · Oct 31 fake fix denied and real fix verified on the emulator · Nov 1 cold-install hero flow incl. airplane-mode sync · Nov 2–3 push-to-talk claim via a new Muse Voice Transcribe endpoint (transcript confirmed by the technician before it becomes the claim).

## Testing
Unit tests for the queue and state mapping (JUnit5, Turbine), contract tests against recorded API JSON, one instrumented smoke on an emulator for capture → upload. CI runs `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
