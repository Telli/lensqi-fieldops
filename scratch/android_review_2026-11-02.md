# Android client review — 2 November 2026

Scope: `apps/android` on `main` at `619a56a`. Every finding below was checked against the code (and, where it mattered, against the server in `src/`). `./gradlew :app:testDebugUnitTest` was run locally: **211 tests, 0 failures**. Nothing was run on an emulator or phone for this review.

## 1. Feature completeness

### Against PRD §9 (Android) and the Track B plan

| Area | State | Notes |
| --- | --- | --- |
| Select fixture → create inspection | Built | `StartScreen`, `AssetDetailScreen`. **QR scan (ML Kit) is not implemented**; no barcode dependency exists, yet the QR-scanner icon is used on the Start button and the home hero, which promises a scanner that isn't there. |
| Capture, full-res JPEG, `source`, `observedAt`, idempotency key | Built | `CameraScreen` → `CommandQueue.enqueuePhoto`. Dimensions are read by the server from the file header, so the client not sending them is fine. |
| Investigate + SAM overlay + crop | Built | `ReviewScreen`, `ArOverlay`, `CocoRle` (verified column-major, matches server placement). |
| Verify & receipt | Built | `VerifyFlow` mirrors `apps/web/src/lib/verify.ts` exactly (readiness-driven card, attempt read from the event log). Receipt WebView is same-origin locked; bundle share/save works. |
| Offline-first | **Partial** | Only photos (`UploadPhoto`) and task step checks (`StepCheckPhoto`) are queued. Claim, checklist, acknowledge, work order, verify, create-inspection all fail with "Can't connect" offline. The plan text says "captures and commands". |
| Cached knowledge with `Cached` label and pack digest | Built | `Knowledge()` panel. No sources sheet (section/revision/synthetic) — FEATURE_STATUS already lists this as partial. |
| Guided tasks (home, new task, plan, step runner, free-form, done) | Built | Full flow, voice commands, pause/resume, offline step checks, reach advice from ARCore height. |
| Voice (GT-T18) | Built, not hardware-verified | Spoken step text, push-to-talk, typed fallback, mic hold. Spec box still open pending a real-mic run. |
| Technician task → FieldProof link (GT-T21) | Built | Done screen shows linked stage and simulated label. Spec note is stale (says it doesn't). |
| Dictated repair claim (T6.2 P1) | Built | `ClaimField` dictation. FEATURE_STATUS still says "not on main" — stale. |
| Meta glasses | Mock only | Real glasses never tested (documented). |
| Spatial AR phases 0–4 | Built, unit-tested only | Not run on a phone (documented). |
| Teaching recorder | Built | Emulator evidence exists. |

### Documentation drift to fix
- `.kiro/specs/guided-tasks/tasks.md` GT-T21 note says the task detail does not show the inspection stage; it does (GT-T36).
- `docs/FEATURE_STATUS.md` says the push-to-talk repair claim is "not on main"; `ClaimField` dictation is on main.
- The Track B skill still says min SDK 26; `build.gradle.kts` is 31 (Meta DAT requirement).

## 2. Bugs and business-logic errors

Ranked by impact on a technician.

### High

**B1. Glasses live assist keeps coaching the previous session.** `wearables/GlassesLiveService.kt:61` — `if (running) return START_NOT_STICKY` ignores the new `LiveSession`. Because the glasses screen does not stop the service on ON_STOP while live is active (`ui/glasses/GlassesScreen.kt:128`), opening the glasses for inspection B or for a task step after running them on inspection A shows "Pause live assist" and speaks/renders A's guidance against B's prompt. Fix: when `sessionOf(intent)` differs from `store.state.value.session`, tear down the current `LiveCameraSession` and restart with the new one; have the screen compare `live.session` with its own.

### Medium

**B2. Glasses photo origin is dropped on the task step route.** `ui/FieldOpsNavHost.kt:155` `onCaptured = { file, _ -> vm.check(step.id, file) }`; `POST /v1/tasks/{id}/steps/{stepId}/check` has no `source` part. A Mock Device Kit photo becomes a step check with no `MockWearables` label, which breaks the "never show a simulated result without the badge" rule. Needs a `source` form field on the server and the origin passed through; until then refuse mock photos on that route.

**B3. Returning from the Meta AI app loses the screen.** `AndroidManifest.xml` declares the `lensqi-fieldops` VIEW intent filter but `MainActivity` has no `launchMode` and no `onNewIntent`. The return deep link starts a new `MainActivity` on the task home rather than resuming the Glasses screen. Set `singleTask`/`singleTop` and handle `onNewIntent`.

**B4. POST_NOTIFICATIONS denial is ignored.** `ui/glasses/GlassesScreen.kt:134` starts live regardless of the result. On Android 13+ the foreground service then has no visible notification, so the advertised "Stop from the notification" and Mute/Stop actions are unreachable on a locked phone.

**B5. Health OK but fixtures call fails → assets wiped.** `ui/home/HomeViewModel.kt:refresh()` writes `fixtures = (apiCall { api.fixtures() } as? Ok)?.value.orEmpty()`, overwriting the previous list with empty. Start screen then shows "Loading assets…" forever because `reachable` is true. Keep the previous list (and the snapshot) on failure, as the inspections list already does.

**B6. Inspection commands have no retry idempotency.** `InspectionViewModel.key()` mints a fresh UUID per tap for claim, checklist, acknowledge and verify. A verify that times out at the client (model calls run 5–20 s) and is tapped again creates a second recorded attempt. `TaskViewModel` already does this right (`keyFor(action)` kept until a non-retryable answer); apply the same pattern.

**B7. Pending uploads rendered twice in verification mode.** `ui/inspection/InspectionScreen.kt`: the top-level `PendingUploads` item and the Photo card's `PendingUploads(s, vm)` both render for the same commands, so Retry/Remove buttons appear twice.

### Low

- **B8.** `TaskViewModel.init` re-sets `error` for every refused check on every queue emission (`ui/task/TaskViewModels.kt`), so the refused message reappears after the user dismisses it.
- **B9.** A queued (offline) step check never speaks its verdict when it later syncs; only `lastCheck` updates on refresh.
- **B10.** `SettingsStore.currentBase()` is `runBlocking` on the main thread and is called at every ViewModel construction and in the `FieldOpsApi` provider.
- **B11.** Imported photos (`copyToCache`) get `observedAt = import time`, not EXIF capture time. Server tolerates skew, but the Before/After picker in `verificationItems` uses `observedAt` and can mis-pick the "before" photo.
- **B12.** `whenText` uses `Instant.parse` after rewriting only `+00:00`; any other offset falls through to the raw ISO string. `instant()` in InspectionScreen already uses `OffsetDateTime`; use it everywhere.
- **B13.** `WaitForAnalysis` (Comparing) card has no polling; web polls every 2 s for 90 s. Rare on Android because the server analyses inside the upload, but a view that arrives in that state (e.g. after a web upload) sits until manual Refresh.
- **B14.** `ReviewScreen` renders empty for the ~100–300 ms between shutter and `enqueuePhoto` finishing (hash of a 12 MB JPEG) because `s.review` is still null.
- **B15.** `captureForReview` decides `afterRepair` from the local view; if offline and the claim was recorded elsewhere, the synced photo triggers an automatic Muse investigate on a post-repair photo (wasted model call; server still decides).
- Camera/AR/wearables (from the delegated review, each confirmed in code): ARCore availability can stick at `Checking` (`CameraScreen.kt:196`); analyzer exceptions are uncaught on the single-thread executor (`CameraScreen.kt:301-313`); detect upload/capture race drops anchoring for one cycle (`ArRenderer.kt:190-214`); every glasses photo ends live assist with no resume (`GlassesViewModel.kt:50`); negative `heightAboveFloorCm` renders "-12 cm up"; permanently denied camera has no Settings path; teaching export filters only a top-level `person` key and zips inspection events unfiltered.

## 3. What holds up well

- The app never decides a stage or outcome: every banner, card and label is derived from `readiness.next`, `lastDecision` or the event log. `VerifyFlow` is a faithful port of the web.
- Idempotency keys on queued photos survive restarts; the server replays by key and the client checks the returned digest.
- Provenance labels (Live / Simulated / Replay / Vendor, mock glasses, imported) are consistent; release builds cannot produce `MockWearables`.
- Distance is only ever shown from measured ARCore depth; the 2D path says depth is not measured.
- COCO RLE decode, EXIF rotation (all 8 orientations) and unproject math are correct and tested.

## 4. Test coverage gaps

Covered well: queue/dispositions/migrations, verify flow, task view model, RLE, AR geometry, speech turns, teaching store. Not covered: `LiveCameraSession` scheduling (frame staleness, 429 Retry-After), `GlassesLiveService` session switching (would have caught B1), `DatGlassesSource` state machine, the CameraX crop/rotate/letterbox mapping, `HomeViewModel.refresh` failure paths (would have caught B5), `InspectionViewModel.analyze` lifecycle (close review mid-analysis, retry after reject), navigation deep-link return.

## 5. Improvements with real field value

1. **Queue the JSON commands offline, not just photos.** Claim, checklist answers and acknowledge are small, already idempotent server-side, and are exactly what a technician wants to record in a comms room with no signal. Replay in capture order; verify stays online-only (it measures).
2. **Asset QR/label scan.** Honour the existing icon: ML Kit barcode → asset id → `POST /v1/inspections`. Removes the fixture list scroll and mis-taps on sites with many look-alike switches.
3. **Verify preflight card.** Before enabling "Verify the repair", show the three proofs' current state (photo accepted, review complete, confirmed) with the server's `readiness.checks` freshness, so a failed attempt is rarer and never a surprise.
4. **Clock-skew warning.** The server rejects post-repair photos whose `observedAt` is far behind the claim. Compare the device clock with the server `Date` header on `/health` and warn before capture.
5. **Resume glasses live assist after a capture**, and show "Assist for: <asset / step>" on the HUD and notification (also makes B1 visible).
6. **Attempt history view.** The record already keeps every attempt; the screen says "N attempts kept" but only shows the current one. A list with decision, reasons and which proof failed helps a supervisor review in the field.
7. **Store measured notes with the photo.** Persist the last ARCore distance/height from the capture confirmation as a measured note on the observation, since the still has no depth.
8. **Settings deep links for denied permissions** (camera, microphone, notifications) with the consequence spelled out.
9. **Spoken verdict for synced step checks** and a per-step "checks waiting" count on the plan screen.
10. **Sources sheet for knowledge facts** (section, revision, cached/synthetic) to finish VK-T07.
