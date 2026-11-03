# Response to the 3 November 2026 mobile review (`main` @ `55c117c`)

An external review of the Android app, the mobile web surface and the shared backend arrived on 3 November. Every claim was checked against the code before anything was changed; this note records which held, which did not, and what shipped on `codex/mobile-review-nov3`. The review's percentage verdict and its feature-parity matrix are not adopted: "built" is not "verified on hardware", and the open hardware gates in `docs/FEATURE_STATUS.md` stand.

## Verification

Each claim was read against the cited lines and their callers. Verdicts: **valid** (holds as written), **partial** (the mechanism is real but the scope or trigger is narrower than stated), **invalid** (does not hold).

The review's one failing automated gate did not reproduce: `./gradlew :app:testDebugUnitTest --rerun` on `55c117c` passed 240 of 240. The teardown race it describes (ViewModel coroutines resuming on `Dispatchers.Main` after `resetMain()`) is a real mechanism, so the test class now cancels every ViewModel it created before resetting the dispatcher.

### Backend (all valid)

| Claim | Verdict | What shipped |
|---|---|---|
| H1 checklist review citing a pre-claim photo satisfies verification | valid | `VerifyBlocker.ReviewCitesPreClaimPhoto` in `VerificationPolicyEvaluator`; readiness sends the technician back to the checklist. Policy is now `fieldproof-policy/1.5`. The command still records the review (an existing test depends on that); the gate refuses it. |
| H9.1 task creation publishes the idempotency key before the plan call | valid | `KeyedGate` held from the replay check through `Plan()`; `TaskStore.SameCreate` refuses the same key with a different mode, goal or site. |
| H9.2 a reused key on `/check` returns 500 and leaves an orphan photo; on `/link-inspection` leaves an orphan inspection | valid | Both refuse with `409 invalid_state` ("used for a different command") before any side effect. |
| H9.3 `/ask` records every frame as a phone photo | valid | `/ask` reads `source` like `/check` does; Android sends it. |
| H6.2 task photos reach the model without EXIF orientation | valid | `/check`, `/ask` and manual plan photos are turned server-side with the same guards as inspection uploads. |
| 2C.1 the web receipt counts preserved originals as photos | valid | Counted by kind `photo` only. |
| 2C.6 `api.investigate` drops `photoEvidenceId` | valid | The query is sent, so `409 photo_superseded` applies. |

### Android

| Claim | Verdict | What shipped |
|---|---|---|
| H2.1 simulated verification shown as plain "Verified complete" | valid | `stageLabel(stage, outcomeKind)` says "Verified complete · simulated" on the inspection header, the home cards, the list and the asset page; the closed card shows the SIMULATED badge offline too. |
| H2.2 the server's change check labelled "inferred" | valid | Labelled "Change check · compared by the server", matching the web. |
| H3.1 leaving the glasses screen kills the foreground live service | valid | `onCleared` stops the stream only when the service does not own it. |
| H3.2 live assist restarts mid-capture and then dies on pop | valid | The resume request is set after the still is taken; a pure gate never resumes while capturing. |
| H3.3 mock phone-camera feed cannot capture | valid | Fixed. |
| H4.1 a step photo leaves live coaching off | valid | Live state is restored after delivery in embedded mode. |
| H4.2 shutter before the camera is bound | partial (an `IllegalStateException` before init, not an `ImageCaptureException`) | Shutter waits for the stream. |
| H4.3 ARCore session leak / resume crash | partial (leak real, crash unlikely) | Session closed on configure failure; any resume exception reports instead of crashing. |
| H5.1 a refused step row blocks Done/Skip forever | valid | Only a Pending row holds a tap, and the hold is said aloud. |
| H5.2 an online model failure wedges the whole server's queue, no cap, no remove | valid | A retryable server answer holds only that task; after five attempts the row is refused with the server's code; pending step photos can be withdrawn. |
| H5.3 later steps replay after a refusal; `requireNotNull` crashes | partial (ordering valid; the crash needs a row no current code can write) | A refused row holds the task's later rows until it is removed; an unreadable row is refused on the phone. |
| H6.1 review still shown sideways | valid | EXIF-aware decode off the main thread. |
| H6.3 unbounded artifact decode | partial (bound needed; EXIF is not) | Decode bounded to 2048 px; the overlay scales from the source width. |
| H6.4 HEIC import leaves a blank review screen; null stream crashes | valid | Navigation only after the review is prepared; import errors shown with a Close button. |
| H7 "accepted" text above "Photo not accepted"; After shows the accepted photo; Before loses outlines | valid, server behaviour confirmed | Summary leads with the rejection; After prefers the rejected photo and says so; outlines cached per photo. |
| H8.1 record screen never verifies; "Try again" clears a tamper error | partial (verify exists on the inspection screen) | Export 409 is named and kept apart from load errors. |
| H8.2 done screen hides export and link errors | valid | Shown under the work-package button. |
| M1 cold-start speech lost; no mute button; repeated hint dropped; false permanent denial | partial (the repeated-hint rule is by design) | Startup buffer, mute button on both task screens, rationale checked before and after the prompt. |
| M2 vocabulary pass rewrites model text | valid | Model and plan text is shown and spoken as written; the pass stays for internal status strings. |
| M3 check on a finished step; `check()`/`linkInspection()` do not remember; refresh wipes a refusal; review Cancel targets the wrong job | valid (b overstated) | All four fixed. |
| M4 scan screen clipped, keeps scanning, trailing punctuation, offline asset page, duplicate inspection | mostly valid | Fixed; the scanner-reuse case has no practical trigger. |
| M5 teaching recorder unbind, swallowed Back, blank correction | partial / partial / valid | Blank correction gated; a stop during start is applied once recording begins; unbind order unchanged. |
| M6 pinned Retrofit base vs dynamic library base; main-thread I/O; temp files; nav state | mostly valid (the Just-Ask hand-off and the truncated-answer prefill are invalid) | Requests re-point at send time; clock offset reset on switch; snapshot I/O on the IO dispatcher; capture and manual files deleted; `rememberSaveable`; `restoreState` off for the new-task tab; AI-named hint on the free-form header. |
| M7 deterministic rerun failure | not reproduced; mechanism plausible | ViewModels cancelled in teardown. |

### Mobile web (all valid)

| Claim | What shipped |
|---|---|
| 2C.2 no way to start an inspection once every asset has an open one | Permanent "New inspection" link in the technician header. |
| 2C.3 offline-completed task bounces off the step route | `taskRoute()` opens the summary when every step was finished on the device. |
| 2C.4 "pause" keeps reopening the microphone | Hands-free holds while paused; only "resume" is heard. |
| 2C.5 queued updates vanish from online answers; mode-filtered lists go stale | The overlay applies online while a row is queued; mode lists are rewritten on save (both proven by the worker smoke script). |

## Second pass

An independent read of the combined change found five regressions, all fixed before commit: the five-attempt cap counted sync runs made while the server was unreachable; the microphone Settings hint disappeared once Android stopped asking; a hands-free pause could only be ended by a tap; a review valid for the previous claim was denied instead of asked for again; and a saved job rebuilt the mode-filtered history lists from a list that may never have been cached.

## Not adopted

- Priority-list features (grounded advisor and prediction cards on Android, hands-free mode on Android, tap-to-ask on live outlines, site pack download, OCR tags, `decline-view`) are product scope, not defects. They remain open in `.kiro/specs`.
- The feature-completeness percentage. Hardware acceptance (audible TTS, voice commands, shutter checks, SAM outlines on a real part, glasses) is still unsigned, as `docs/FEATURE_STATUS.md` records.

## Gates on the branch

.NET 330 tests (Core 158, Meta 18, Api 154); web typecheck, 67 tests, production build reproducing `wwwroot`, worker smoke in headless Chrome; Android 270 unit tests with debug assembly and debug and release lint; secret scan PASS. No physical Android device, live model or production deployment was used.
