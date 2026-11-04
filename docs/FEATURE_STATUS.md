# Feature status

## As of 4 November 2026 (Pacific), `claude/android-elevenlabs-speech` (Android spoken output through ElevenLabs)

Android reads each line with an ElevenLabs voice through a new server route, `POST /v1/voice/speak`, which holds the key. The phone's TextToSpeech reads the line if the server has no key, takes over 3 seconds or fails, and is used for the next minute after a failure. The route has its own per-IP limit, stores no audio and doesn't log the text. The camera notice says spoken replies may use ElevenLabs.

Verified on the emulator with a local stand-in for the provider, and on a Galaxy S23 Ultra with the real ElevenLabs voice and with no key, where a person heard the phone's own voice instead (`docs/evidence/android/speech-output/`). A provider failure or slow response hasn't been run on a physical phone, and listening through Bluetooth hasn't been checked.

## As of 4 November 2026 (Pacific), `claude/android-camera-layout` (Android camera questions and AR display)

A question asked on the Android camera now carries what the camera sees, whether camera assistance is on or off. Before this, a question sent no image unless assistance was running, and assistance starts off, so the model answered without seeing anything. The phone keeps one recent frame in memory, about once a second, and sends it only when the person asks; the screen says so, and says when no frame was ready.

The camera screen is laid out again. The picture sits above the controls at the camera's own 4:3. **Start camera assistance** is a fixed bar, the text panel is one scrolling area with an answer scrolled into view, and the first safety line stays on screen with a count of the rest. Part labels are two lines marked as AI suggestions or the person's target, stay inside the visible picture and clear of each other, and outlines are steadier and readable on pale surfaces. The task list retries by itself after a lost connection, and the camera says when the server can't be reached.

Verified on the emulator's virtual scene with genuine Muse Spark and SAM 3.1 calls, and on a Galaxy S23 Ultra with a person's voice and a real ceiling smoke detector (`docs/evidence/android/camera-layout/`): ARCore ran, labels tracked the detector with estimated distances, and spoken questions carried a frame and were answered from the view. On the same phone, Muse Voice misheard "reseated" on all three takes that used it, so the review step mattered. Three stills showing only the detector and ceiling are committed; the full recordings are not, because they show the person's home.

Gates: Android 335 unit tests with debug and release assembly and debug and release lint (no errors); secret scan PASS. No server or web code changed.

## As of 4 November 2026 (Pacific), `codex/meta-voice-input` (speech input through Meta)

Web and Android speech input now goes through LensQi to `muse-voice-transcribe-1.0`, with no fallback to browser or phone recognition; spoken output is unchanged. General speech input has its own per-IP limit, so hands-free cannot use up the budget for plans and questions. The repair claim keeps one voice path, recording with review, beside typing.

Verified with genuine provider calls on synthetic speech through the web client's real recorder and upload code, using file-fed audio, and on the Android emulator with silence (`docs/evidence/meta-voice-input/`). A person's voice on a real browser or phone microphone has not been tested.

Gates: 428 .NET tests (Core 228, Meta 24, Api 176); web typecheck, 85 tests, production build; Android 322 unit tests with debug and release assembly and debug and release lint (no errors); secret scan PASS.

## As of 4 November 2026 (Pacific), `claude/android-voice-claim` (repair claims recorded with Muse Voice)

Android can record a repair claim and transcribe it with `muse-voice-transcribe-1.0` through a new draft-only endpoint, beside the existing typing and phone dictation. The person reviews, edits or rejects the transcript; confirming sends it through the normal claim path. The endpoint records no event and stores no audio, and it refuses a closed inspection before any model call. Silence returns `no_speech`.

Verified with genuine provider calls on synthetic speech and on the emulator (`docs/evidence/android/voice-claim/`). The emulator microphone provided no speech, so the review screens used a local proxy that substituted the synthetic speech. A physical phone microphone and a person's voice have not been tested. See `docs/ANDROID_VOICE.md`.

## As of 4 November 2026 (Pacific), `main` (the last three findings from the 3 November application review)

A fixture that does not require a fresh equipment check can no longer verify anything: the policy (now `fieldproof-policy/1.7`) denies it, and the Core invariants, the schema and the Python validator refuse such a fixture at load. A live outcome now needs a passing `LiveLocal` measurement, even if a fixture names another source. The supplied fixture already required a fresh check, so its outcomes do not change. The web task summary no longer offers Verify with FieldProof on a stopped task, while an existing linked inspection stays reachable; Android already did this. Web step coaching and the Android camera and glasses notes now say that a frame sent with a question is saved. The wording was not checked on a device.

Gates: 413 .NET tests (Core 228, Meta 18, Api 167); web typecheck, 79 tests, production build; Android 311 unit tests with debug and release assembly and debug and release lint (no errors); twin validators and secret scan PASS.

## As of 3 November 2026 (Pacific), `claude/verification-and-safety-gates` (response to the 3 November application review)

A closed inspection refuses new checklist reviews and acknowledgements. Restricted work stays restricted on follow-up questions, live guidance and step checks, and restricted plans are checked in the title, cautions, stop conditions, photo checks, the summary and the professional reason. Verification is held while a fact the site requires is unconfirmed for the bound asset (VK-T10, policy 1.6). From receipt format 1.3 the verifier, the API check and the export all require the exact rendering of the receipt. Not addressed from that review: the live label when a fixture turns off fresh diagnostics, the saved question-frame wording, the stopped-task verify button, and the seven-day retention requirement.

Gates on the branch: 407 .NET tests (Core 222, Meta 18, Api 167); web typecheck, 78 tests, production build reproducing `wwwroot`; Android 311 unit tests with debug and release assembly and debug and release lint (no errors); secret scan PASS. No physical Android device, browser run, live model or production deployment was used.

## As of 3 November 2026 (Pacific), `codex/open-product-scope` (the six open product-scope items)

The features the 3 November review found on the server or web but missing on mobile were built; `docs/quality-pass/2026-11-03-open-scope.md` lists each with its files. Android gains the grounded advisor (`/knowledge/reason`) with excluded facts and a prediction card (`/predictions`), an opt-in continuous hands-free mode with a speech-privacy badge, tap-to-ask on live outlines with age-faded boxes, a "Download site pack" that stores the site record, every asset's vendor knowledge and the open inspections for work out of signal (new `GET /v1/fixtures/{fixtureId}/pack`), on-device OCR of printed asset tags with a typed fallback and the scanner on the technician's new-task screen, and "I can't safely get this view" closing a NeedsEvidence inspection as inconclusive. Mobile web gains the same decline, the advisor panel and the excluded facts, warnings, origin badge and pack digest on the knowledge page. All of it is **Built**: unit tests and compilation only, no phone, microphone, speech engine or camera run.

Gates on the branch at merge: 339 .NET tests (Core 159, Meta 18, Api 162); web typecheck, 78 tests, production build reproducing `wwwroot`; Android 311 unit tests with debug and release assembly and debug and release lint (no errors); secret scan PASS. No physical Android device, live model or production deployment was used.

## As of 3 November 2026 (Pacific), `codex/mobile-review-nov3` (response to the 3 November mobile review)

Every claim in the external mobile review was checked against the code; the verdicts and what shipped are in `docs/quality-pass/2026-11-03-mobile-review-response.md`. Policy `fieldproof-policy/1.5` refuses a checklist review that cites a photo taken before the repair claim. Task creation, step checks and inspection links refuse a reused idempotency key for a different command, task photos are turned by their EXIF orientation before the model sees them, and a question's frame records its capture device. On Android a simulated verification is labelled as such everywhere it appears, the server's change check is labelled as compared by the server, a rejected later post-repair photo is reported ahead of the earlier acceptance, the glasses live service survives leaving its screen, live coaching returns after a step photo, model and plan text is spoken as written, and the outbox holds only the task a retryable server answer concerns, refuses a row after five attempts with the server's code, holds a task's later rows behind a refused one until it is removed, and lets a waiting step photo be withdrawn. Mobile web keeps a queued step visible in online answers, opens an offline-finished job on its summary, holds hands-free while paused, counts receipt photos once and names the photo an assessment is about.

Gates on the branch: 332 .NET tests, 68 web tests with the production build reproducing `wwwroot` and the worker smoke in headless Chrome, 271 Android unit tests with debug assembly and debug and release lint, secret scan. No physical Android device, live model or production deployment was used.

## PR 17 `codex/offline-work-packages`

The Android client keeps a server-specific history, confirmed task plans and cited reference images. Pending step updates are shown separately and replay with their original keys. The production web client supports reopening previously loaded jobs using a service worker on HTTPS or localhost; saved jobs are bounded to the newest 50 and a finished or stopped job is dropped after 14 days with its reference pages, unless an update is still queued; the offline history lists only jobs whose record is still saved, and a job whose every step was finished offline opens its summary with a pending label, never a completed phase. Mobile web Home searches every job status, including stopped work.

A planned job can cite an uploaded reference page per step. Both clients can start a plan from selected photos and answers in a Just ask session. Work-package ZIPs include a readable summary, job photos, their recorded origins and digests, and, when a FieldProof receipt has already been issued for the linked inspection, that issued receipt by version after its integrity check; downloading a package never issues a receipt, and the summary says when the inspection has recorded more since. A title the AI named from a free-form conversation is labelled as such in history rows, the summary and the work package. Rotated inspection JPEGs recorded with this version keep both the original and the upright derivative. Earlier receipts retain the artifacts that were recorded when they were issued. An observation retried under its key with different photo bytes is refused with `key_reused`.

Gates on the branch: 322 .NET tests, 64 web tests with the production build reproducing `wwwroot`, 240 Android unit tests with debug assembly and debug and release lint, secret scan. A Chrome regression test exercised offline document reload, history, plan and reference recovery, repeated submission of a pending step, and a single replay on reconnect. No physical Android device, live model or production deployment was used.

As of 31 October 2026 (Pacific), `main` at `36725f6`. Submission is due 4 November, 9 PM ET.

This status was assembled from:
- the three task lists in `.kiro/specs/`
- the success definition and scope in `PRD.md`
- the recorded evidence in `docs/evidence/`
- the merged PRs 2–8

The status words mean:

- **Done:** built, tested and shown with recorded evidence.
- **Built:** code and tests exist, but it hasn't been shown end to end where the spec asks for that.
- **Partial:** some of the task is built or shown.
- **Open:** not started.

## Checks on `main`

| Check | Result | When |
| --- | --- | --- |
| `dotnet test --nologo` | 303 passed (Core 152, Meta 18, Api 133) | 31 Oct, this Mac |
| `./gradlew :app:testDebugUnitTest :app:assembleDebug` | 174 passed, debug APK built | 31 Oct, this Mac |
| `scripts/secret_scan.sh` | PASS | 31 Oct |
| Web tests | 60 passed | 2 Nov, this Mac (web review fixes and improvements) |
| GitHub Actions | Jobs don't start. GitHub reports failed payments or a spending limit | Since 30 Oct |

Because of the Actions problem, the test results for PRs 7 and 8 come from local runs only.

## Hackathon success definition (PRD §3)

| # | Item | Status | Evidence | Still missing |
| --- | --- | --- | --- | --- |
| 1 | DIY plan from a goal | **Done** | Genuine Muse plans: `docs/evidence/tasks/plan-oil.json`, `plan-cam.json` | |
| 2 | Safety boundary | **Partial** | A restricted goal (breaker panel) gave an observe-only plan with a referral (`plan-panel.json`). The validator rejects hands-on steps in tests | No genuine run has rejected a hands-on plan, because the model never produced one |
| 3 | Guided step with live AR | **Partial** | S23 run 2: ARCore held three parts with distances on a real ceiling detector (`s23-phone-run-2/`) | No SAM outline on a real step part has been recorded. Spoken instructions have not been heard: the phone was on silent |
| 4 | Voice | **Partial** | S23 run 2: a spoken question was recognised and answered from the camera view | "Next step" by voice hasn't been shown. Spoken output hasn't been checked by ear |
| 5 | Step check suggests, the user decides | **Built** | Genuine check (`check-oil.json`) and a full-resolution check from the emulator (`android-step-check.png`). Tests show a check never completes a step | A shutter step-photo check on a phone |
| 6 | Technician task to FieldProof | **Built** | `TechnicianJourneyTests` with fake models and simulated checks (`docs/evidence/tasks/technician-journey.md`). The FieldProof loop also ran live on the S23 (`docs/evidence/live-phone/`) | A technician task run by a person with genuine models |

## Acceptance gates (PRD §3)

| Gate | Status |
| --- | --- |
| Genuine calls: two DIY plans, one step check, one voice question, SAM on step targets | **Partial.** Recorded: plans, step check, typed and spoken questions. Not recorded: SAM on a real step part (GT-T24) |
| Restricted rule rejects hands-on steps in tests and in at least one genuine run | **Partial.** Tests pass. No genuine run has rejected a plan, for the reason in row 2 above |
| A step check never completes a step; a technician task never shows "verified" without a FieldProof decision | **Done** (tests) |
| FieldProof v1.1 gates still pass | **Done.** Tests, evaluation `docs/evaluation/eval-20261028T061752Z.md`, receipt tamper tests |
| Public code reproduces the simulated demo and receipt checks | **Partial.** `scripts/clean_room_check.sh` exists, but it hasn't been run against the current `main`. The public repo and video access check (T8.2) is open |

## By area

### Backend and web

| Area | Status | Evidence or note |
| --- | --- | --- |
| FieldProof inspection loop: Muse assessment, SAM outlines, fixture-approved diagnostics, fake fix denied, real fix verified | **Done** | `InspectionApiTests`; evaluation run; live loopback run on the S23 (`docs/evidence/live-phone/`) |
| Evidence receipt with hashes, verifier CLI and tamper detection | **Done** | `docs/evidence/receipt-sample/`, `verify-receipt`. From format 1.3 the page must be the exact rendering of the receipt; older bundles keep the digest check and say so |
| Vendor knowledge: packs, applicability, cited retrieval, proposal validation (VK-T01–T06) | **Done** | Tests; recorded genuine calls |
| Predictions, visual change analysis, references in the receipt (VK-T11, VK-T24–T27) | **Done** | Tests |
| Documentation gate on verification (VK-T10) | **Done** | Policy 1.6 holds a passing inspection while a required fact is unconfirmed. Judged on the installed pack at verify time. `RequiredKnowledgeGateTests`, `VerificationIntegrityApiTests` |
| Diagnostic-to-fixture binding and result constraints (VK-T08, VK-T09) | **Partial** | The `requiredKnowledgeFactIds` binding loads and is cross-checked against its pack; neither task is signed off against its full acceptance |
| Full vendor-knowledge negative-case suite (VK-T12) | **Open** | Many cases are covered by existing tests, but the suite as specified isn't checked off |
| RouterOS read-only probe (VK-T15–T18, P1) | **Open** | Optional, after the other vendor-knowledge P0 work |
| Guided-task core, plan validator, planner, coach, checker, `/v1/tasks` (GT-T01–T08) | **Done** | Core and API tests; genuine calls in `docs/evidence/tasks/` |
| Web DIY flow, free-form help, ops Tasks list (GT-T09–T14, T23, T30) | **Done** | Checked in the browser at phone size. The browser pane had no camera or microphone |
| Web review fixes (2 Nov): aligned live boxes, Cancel on every model call, kept and scoped idempotency keys, rotated-photo outlines, honest progress | **Done** | PR 14; `apps/web` tests and `InspectionApiTests` (create replay, geometry frame) |
| Web improvements (2 Nov): verify preflight and attempt history, uploads stored upright (server), full-resolution stills, kept capture for retry, tap-to-ask live boxes with freshness, asset-label scan, why-not-accepted sheet, hands-free mode | **Done, not browser-checked** | 60 web tests, 314 .NET tests; the auto-orient script was run against Pillow 11 on a turned JPEG. Camera, BarcodeDetector, ImageCapture and speech paths were not exercised in a browser |

### Android

The repo's rule is that no Android build is claimed until it has run on a device or emulator with screenshots in `docs/evidence/android/`. The **Shown on** column follows that rule.

| Area | Status | Shown on |
| --- | --- | --- |
| Inspection flow: capture, Muse, SAM overlay, fake fix denied, real fix verified, receipt | **Done** | Emulator, `docs/evidence/android/01–23` and `41–43` |
| Offline capture and sync, cold install | **Done** | Emulator, `10–15` |
| Live verify on a phone camera (step C) | **Done** | S23, `docs/evidence/live-phone/` |
| Guided-task screens, step camera, queue and glasses generalisation, technician tasks, home (GT-T15–T17, T19, T20, T22) | **Done** | Emulator (`docs/evidence/tasks/android-*.png`); S23 runs 1–3 |
| Voice (GT-T18) | **Partial** | Push-to-talk recognised on the S23. Spoken output not checked by ear; "next step" by voice not shown. Hands-free mode (3 Nov) built, not run on a device |
| Task linked to an inspection (GT-T21) | **Built** | Tested with fakes. Since GT-T36, the task screens show the linked inspection's stage; the note in the spec predates that. Not run on hardware |
| Spatial AR, plan phases 0–4: safety fixes, held target, off-screen arrow, guided capture, marker registration (PR 7) | **Built** | Unit tests and debug build only; **not run on a phone or emulator**. The marker isn't scored with `arcoreimg` |
| Technician-facing wording, Diagnostics setting, inspection record summary (PR 8) | **Built** | Emulator screenshots in `docs/evidence/android/ux-polish/` cover the original change (`dabe67b`). The two review-fix commits (`e9efc27`, `d18a3ed`) are unit-tested only. Lint is blocked by a crash in an AndroidX lint check. The S23 acceptance run is pending |
| Knowledge sources in Android (VK-T07) | **Built** | Source sheet with section, revision, digest, cached and synthetic status (2 Nov); excluded facts with reasons, the grounded advisor and the prediction card (3 Nov). Unit tests and the emulator navigation evidence of 2 Nov; the 3 Nov additions are not device-checked |
| Meta glasses (T6.3, GT-T19) | **Partial** | Mock Device Kit only (`24-*`, `26-*`, `docs/evidence/live-glasses/`). No real glasses tested |
| Push-to-talk repair claim (T6.2, P1) | **In progress, not on `main`** | Uncommitted changes to 8 files in the Codex worktree `~/.codex/worktrees/android-voice-claim` |
