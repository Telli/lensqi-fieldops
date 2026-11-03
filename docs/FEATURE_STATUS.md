# Feature status

The Android client keeps a server-specific history, confirmed task plans and cited reference images. Pending step updates are shown separately and replay with their original keys. The production web client supports reopening previously loaded jobs using a service worker on HTTPS or localhost. Mobile web Home searches every job status, including stopped work.

A planned job can cite an uploaded reference page per step. Both clients can start a plan from selected photos and answers in a Just ask session. Work-package ZIPs include a readable summary, job photos, their recorded origins and digests, and the integrity-checked FieldProof receipt when linked. Rotated inspection JPEGs recorded with this version keep both the original and the upright derivative. Earlier receipts retain the artifacts that were recorded when they were issued.


As of 31 October 2026 (Pacific), `main` at `4a60d38`. Submission is due 4 November, 9 PM ET.

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
| Evidence receipt with hashes, verifier CLI and tamper detection | **Done** | `docs/evidence/receipt-sample/`, `verify-receipt` |
| Vendor knowledge: packs, applicability, cited retrieval, proposal validation (VK-T01–T06) | **Done** | Tests; recorded genuine calls |
| Predictions, visual change analysis, references in the receipt (VK-T11, VK-T24–T27) | **Done** | Tests |
| Diagnostic-to-fixture binding and documentation gates (VK-T08–T10) | **Open** | No `requiredKnowledgeFactIds` binding in code |
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
| Voice (GT-T18) | **Partial** | Push-to-talk recognised on the S23. Spoken output not checked by ear; "next step" by voice not shown |
| Task linked to an inspection (GT-T21) | **Built** | Tested with fakes. Since GT-T36, the task screens show the linked inspection's stage; the note in the spec predates that. Not run on hardware |
| Spatial AR, plan phases 0–4: safety fixes, held target, off-screen arrow, guided capture, marker registration (PR 7) | **Built** | Unit tests and debug build only; **not run on a phone or emulator**. The marker isn't scored with `arcoreimg` |
| Technician-facing wording, Diagnostics setting, inspection record summary (PR 8) | **Built** | Emulator screenshots in `docs/evidence/android/ux-polish/` cover the original change (`e72843c`). The two review-fix commits (`83edf46`, `31cf3fe`) are unit-tested only. Lint is blocked by a crash in an AndroidX lint check. The S23 acceptance run is pending |
| Knowledge sources in Android (VK-T07) | **Partial** | The knowledge panel labels each fact "Vendor documentation" or "Site setup". There's no sources sheet with section, revision or cached/synthetic status |
| Meta glasses (T6.3, GT-T19) | **Partial** | Mock Device Kit only (`24-*`, `26-*`, `docs/evidence/live-glasses/`). No real glasses tested |
| Push-to-talk repair claim (T6.2, P1) | **In progress, not on `main`** | Uncommitted changes to 8 files in the Codex worktree `~/.codex/worktrees/android-voice-claim` |

## Submission work

| Task | Status | Note |
| --- | --- | --- |
| S23 acceptance run (GT-T25) | **Partial** | Runs 1–3 are recorded. Not yet shown: a shutter step-photo check, "next step" by voice, audible speech, the spatial AR phases and the PR 8 changes |
| Demo video (T7.3, GT-T28) | **Open** | Storyboard ready in `docs/DEMO_STORYBOARD.md` |
| Docs match what ran (GT-T26) | **Partial** | PRD §1 is dated 29 October. It doesn't cover spatial AR, the PR 8 changes or phone runs 2–3 |
| Final checks (GT-T27) | **Partial** | .NET, Android and the secret scan passed on 31 October. Web tests and `clean_room_check.sh` weren't run |
| Freeze, licence scan, tag (T8.1) | **Open** | The README has already been reproduced from a fresh clone |
| Contributions ledger and `PRIOR_ART.md` (T7.4, VK-T14) | **Partial** | The ledger is up to date through `4a60d38`. `PRIOR_ART.md` hasn't been reviewed |
| Public repo and video access, build-period attribution (T8.2) | **Open** | |
| Submit via Cerebral Valley (T8.3, GT-T29) | **Open** | Due 4 November, 9 PM ET / 6 PM PT |

## Shortest path to a complete submission

1. **S23 run.**
   - Re-run the run 2 regression on the spatial AR build.
   - Print the 80 mm marker and check port registration.
   - Do a shutter step-photo check.
   - Say "next step" aloud, with sound on.

   Save the screenshots to `docs/evidence/android/` and `docs/evidence/tasks/`. This one session closes most of rows 3–5 and GT-T25.
2. **SAM outline on a real step part.** One genuine call, recorded under `docs/evidence/tasks/`. This closes GT-T24.
3. **Video.** Record from `docs/DEMO_STORYBOARD.md`, labelling the simulated checks.
4. **Docs.** Update PRD §1 and the README to match what ran (GT-T26). Then run the final checks, including web and `clean_room_check.sh`, freeze, and submit.

These can be cut if time runs short:
- VK-T08–T10, VK-T12 and VK-T15–T18
- the voice repair claim
- real glasses
- the remaining part of VK-T07
