# Android UX polish — October 31, 2026

Implemented on `codex/android-ux-polish`, based on `9505613`. No merge, deployment, credential changes or backend contract changes. Application changes are Android presentation/preferences only; verification engine, event formats, evidence uploads, target association and marker registration remain intact.

## What changed

- Camera: a single guidance surface, task-language status, explicit Details, wrapping controls, optional technical overlays and clearer capture/AR failure recovery. Selected-equipment wording requires an actually selected/tracked target. Lost-target advice replaces the previous target instruction for display and speech. Stale frame acceptance rules are unchanged.
- Inspection: short outcomes and next actions; photo/review/measurement breakdown under Details; Verify record is inside that breakdown. A record verification failure remains visible even after closing Details. Simulated/replay source badges remain visible.
- Record: a native readable summary of the existing receipt JSON, check history with timestamps and source labels, plus a deeper link to the original technical document. Stored receipts, formats, identifiers and signatures are unchanged. Unknown results/time values do not echo raw payloads or become success.
- Settings → Diagnostics: persistent opt-in, off by default. Enables technical details and camera performance overlays. A bounded on-device diagnostic history and structured Logcat entries contain operation codes/status only, not response bodies, URLs, credentials, photos or audio. No telemetry or automatic uploads added.
- Shared wording: backend errors/reasons, dynamic model text, task answers, voice and glasses speech use common presentation helpers. Electrical safety language such as “grounding wire” is deliberately preserved.
- Accessibility: shared action buttons grow with text with at least 48 dp touch height; checklist choices have at least 48 dp height; Details has a meaningful click label; shutter and camera suggestions have accessibility labels. Safety and stop conditions are not truncated; active safety details remain visible when other camera chrome is hidden. Attempt history no longer relies on colored dots.

## Examples

| Before | After |
|---|---|
| Live 2D assistance · AI inferred | Camera assistance |
| Tracking N parts / ARCore | Equipment in view, or Keeping your selected equipment in view when confirmed |
| Tracking lost | Point the camera back at the equipment |
| AI cooling down | Analysis paused briefly · retrying |
| 3D AR: On/Off | Spatial guidance: On/Off |
| Pass — endpoint reachable | Connection check passed |
| View FieldProof receipt | View inspection record |
| Check receipt integrity | Verify record, inside Details |
| Raw HTTP/provider message | Plain explanation and a recovery action; code/status in Diagnostics |

## Validation actually run

- Android offline Gradle `:app:testDebugUnitTest :app:assembleDebug`: 169 tests passed, no failures/errors/skips; debug APK assembled.
- Added presentation tests for unknown/raw failures, truthful outcomes, simulated results, prerequisites, dynamic vocabulary, safety wording, lost-target guidance/recovery, diagnostic defaults/log bounds, receipt route parsing, unknown receipt data, immutable record projection and invalid timestamps. Updated the existing queued-check test to expect the user-facing error rather than raw server text.
- Existing Android suites exercise stale tracking, target identity, registration invalidation, offline queue disposition, verification readiness and event-backed outcomes.
- Backend `dotnet test --nologo`: 303 passed (Core 152, API 133, Meta 18), including verification/evidence/retry regressions. No backend sources changed.
- Lint attempted: blocked by an AndroidX lint/Kotlin analysis `IncompatibleClassChangeError`; see lint-blocker.txt. No suppression or dependency upgrade was used to make this appear green.
- `git diff --check`: passed.

## Emulator checks and evidence

Used Medium_Phone_API_36.1, Android emulator; no physical phone was connected. Inspected the camera's virtual scene, denied/re-granted camera permission, backgrounded/resumed the app, and checked 1.0 and 1.5 font scales. Inspected Settings/Diagnostics and switched diagnostic mode on and off. Inspected completion, Details and native record summary with the repository's recorded simulated API responses.

The recorded-response UI server accepted only reads; its request log showed zero POST/PUT/DELETE calls while opening Details, toggling Diagnostics and viewing the record. This is a UI/read-only integration check, not a new physical repair verification. Recorded artifact images were unavailable in this fixture server, so completion screenshots are not evidence of camera/photo matching.

Screenshots: [camera](camera.png), [camera at 1.5 font scale](camera-large-text.png), [permission recovery](camera-permission.png), [Settings/Diagnostics at 1.5 font scale](settings-large-text.png), [completion at 1.5 font scale](verification-large-text.png), [record at 1.5 font scale](record-large-text.png).

Screenshots in this folder are genuine emulator captures. Camera images show Android's virtual scene. Record/completion screenshots use recorded simulated responses and retain the Simulated label. Build success is not S23 acceptance.

## Remaining S23 acceptance

- Normal/large text and TalkBack on the physical screen, including checklist, safety warnings, long task instructions and keyboard interaction.
- Actual ARCore tracking loss/recovery, marker invalidation, selected-target identity, depth/no-depth fallback and camera handoff at the shutter.
- Spoken guidance in a noisy environment; ensure no technical metric or stale target instruction is spoken. Glasses HUD/audio needs connected hardware.
- Airplane mode, reconnect, process death, queue recovery and explicit acknowledgement on the device.
- Full fixture walkthrough: duplicate/fake fix rejected, changed photo with failed diagnostic remains unverified, real fix with fresh approved diagnostic gets the correct source-labelled result, and record verification failure remains visible.
- Full lint run after the AndroidX/Kotlin lint compatibility issue is resolved.
