# Handoff plan for the next agent (Opus) — finish the hackathon entry by November 4

> **Superseded in part (October 29, 2026).** This plan predates the guided-task re-architecture. The product is now DIY, Technician and free-form modes on one guided-task core, with FieldProof as the technician closer: see `PRD.md` v2.0 §1 and `.kiro/specs/guided-tasks/tasks.md` for current status. The FieldProof tracks below remain accurate as history.

**Written:** October 28, 2026 (UTC) at commit `24d286a`. **Deadline:** November 4, 2026, 9:00 PM Eastern.
**Repository:** <https://github.com/Telli/lensqi-fieldops>, branch `main`, 29 event-period commits, 104 passing tests.
**Read first, in this order:** `README.md`, `docs/STATUS.md` (once it exists), `PRD.md` §3–§8 and §17, `.kiro/specs/lensqi-fieldops/tasks.md`, `docs/HACKATHON_CONTRIBUTIONS.md`, then the project skills under `.claude/skills/` (they encode the conventions and the traps already hit).

## 1. Where the project stands

Done and verified against the running system with genuine Meta calls:

| Area | State | Proof |
|---|---|---|
| Core state machine, append-only events, idempotent commands, dual-proof gate | Done | `tests/LensQi.FieldOps.Core.Tests/Inspection*Tests.cs` (PRD §17 ten negatives) |
| Vendor knowledge packs, exact applicability, bounded cited retrieval, proposal validation | Done | `KnowledgeActionValidatorTests`, Stage One gate evidence |
| Muse Spark client (chat/completions, strict schema, reasoning budget), visual assessment, grounded proposal | Done | `docs/evidence/stage-one-gate-*.json`, UI screenshots |
| SAM 3.1 client (responses API) + Python sidecar parse/crop, ROI transform, canvas overlay | Done | `docs/evidence/sam-spike-*`, `docs/evidence/ui/04-*` |
| Probes: simulated, replay, guarded live HTTP; dispatcher; inspection API; receipts + verify CLI | Done | `InspectionApiTests`, `ReceiptTests`, `docs/evidence/receipt-sample/` |
| Browser P0 UI served by the API | Done | `docs/evidence/ui/01-03` |
| Evaluation runner, 25 labelled scenarios, first report | Done | `docs/evaluation/eval-20261028T032756Z.md` |

Not done: hardening pass (T6.1), change evidence (VK-T26), live owned endpoint smoke (T3.3 live half), the Android client (starts now as Track B, claimed only when it runs), demo video (T7.3), final attribution pass (T7.4), freeze and submit (T8). The browser UI remains the guaranteed P0 surface. Child-spec items VK-T07/T08/T10/T12/T13/T14 and the prediction/change-evidence tasks VK-T24–T27 are partly implemented inside the parent work (predictions are registered and scored; change analysis VK-T26 is **not** built).

## 2. Hard rules that must not be broken

1. Only `InspectionSession` changes `InspectionStage`. No endpoint, model output, probe or UI may set a stage.
2. The model never chooses a URL, host, command, path, credential or verification outcome. Diagnostics run only from a fixture-approved ID through a server-configured alias.
3. Simulated and replayed results are labelled everywhere and can never produce `VerifiedCompleteLive`.
4. Every evidence item keeps its origin, server timestamp and SHA-256. Receipts hash canonical JSON; verification recomputes.
5. No private LensIQ source is copied (PRD §4A). Every commit is event-period work and goes into the ledger.
6. Never claim a live measurement, a hardware test, a photograph or an Android build that did not happen. The video shows only what exists.

## 3. Parallel tracks (revised October 28: ambitious, not serial)

The serial day plan assumed one agent on one checkout. That is the bottleneck. Run **four tracks in parallel** as separate sessions or subagents, each in its own git worktree and on its own API port, with one integrator merging to `main` and writing ledger rows. Android starts **today**, alongside the backend work, not after submission. The browser UI remains the guaranteed P0 surface; the Android app is claimed only when it runs on an emulator or device with screenshots in `docs/evidence/android/`.

### How to run tracks without colliding

| Bottleneck | Fix |
|---|---|
| One checkout, shared `data/`, `bin/`, `.env` | `git worktree add ../fieldops-<track> -b track/<name>`; copy `.env` into each worktree (gitignored); each API instance uses its own `--urls http://127.0.0.1:52xx` and `Storage:DataDirectory` |
| One ledger file every track edits → merge conflicts | Tracks **do not** run `ledger_add.py`. They push their branch; the integrator rebases onto `main`, merges with `--no-ff`, then runs `python scripts/ledger_add.py` once per merged feature |
| `tasks.md` edited by everyone | Tracks tick their own task lines only; conflicts there are trivial and resolved by the integrator |
| One Model API key and budget | Fine: an evaluation run is ≈ 90k tokens and 23 images; four tracks at full tilt stay far under the credit. Cap each track's eval runs with `--only` while iterating |
| Python sidecar and `.venv` | Each worktree gets its own `.venv` (`pip install -r scripts/requirements.txt -r tools/sam-parser/requirements.txt`) or sets `FIELDOPS_PYTHON` |
| Android toolchain | Android SDK and JDK 21 are installed on this Mac; the emulator is the only Android-specific serial resource, so Track B owns it |
| Owned LAN endpoint, real equipment, phone camera | Only the owner can supply these. Track D is prepared end to end so the owner's time is 30 minutes, not a day |
| Muse latency (5–20 s per call) | Irrelevant across tracks; inside a track, batch eval runs with `--only` subsets and keep the full run for the integrator's nightly |
| Review and merge throughput | The integrator merges at three fixed syncs per day (09:00, 15:00, 21:00 UTC); every track must be green on `dotnet test` and `scripts/secret_scan.sh` before pushing |

Suggested agent topology: integrator (this plan's owner) + Track A, B, C as long-running sessions; Track D is the integrator plus the owner's hardware. Each track reads the four project skills first.

### Track A — Backend hardening, change evidence, live probe readiness (Oct 28 → Nov 1)

1. **Hardening (T6.1), Oct 28–29.** Provider-down tests per model endpoint; `RequestTimeouts` on model endpoints; minimum image dimensions; blur and luminance signals from the sidecar stored on `PhotoRecorded` (signals, never gates); ASP.NET rate limiting (30 model calls per inspection, 120 per hour per IP, `rate_limited`); per-inspection model-call cap; assert no logger ever receives an image data URL. Gate: 115+ tests green.
2. **Change evidence (VK-T26), Oct 29–31.** `VisualChangeAnalyzer`: 64-bit dHash from the sidecar, mask IoU from two `MaskRecord`s with a C# COCO RLE decoder (port the JS one), thresholds with a version string, `VisualChangeAnalyzed` event, automatic run after the post-claim photo when a pre-claim segmentation exists. `DuplicateImage` locks the checklist for that photo. Tests: re-submitted photo → `DuplicateImage` with zero SAM calls; unchanged render → `NoVisualChange`; moved-cable render → `Changed`. Gate: the same-photo fake fix is caught before the probe runs. Cut at Oct 31 noon if not green.
3. **Live probe readiness, Oct 30.** `Diagnostics__Mode=LiveLocal` path exercised against a local `python3 -m http.server` on this Mac as a stand-in (it is a real HTTP measurement of an owned endpoint on the loopback; label it `LIVE_LOCAL_PROBE (loopback)` in the ledger and never present it as a lab device). Prepare the exact two commands Track D will run on real hardware.
4. **Second opinion (optional, Oct 31–Nov 1).** `ModelDisagreement`: run the grounded proposal through Muse Spark and Muse Spark Open (Baseten) and record disagreement as an uncertainty signal in the receipt. Only if credits were claimed; otherwise skip without comment in the video.

### Track B — Android client (Oct 28 → Nov 3), P0.5: claimed only when it runs

Load `fieldops-android` before writing a line. Package `com.lensqi.fieldops`, Kotlin, Compose, CameraX, Hilt, Retrofit + kotlinx.serialization, Room, WorkManager. No private LensIQ source; reimplement from behaviour.

1. **Oct 28: skeleton and contract.** `apps/android` Gradle project, CI job (`./gradlew :app:testDebugUnitTest :app:assembleDebug`), `FieldOpsApi` Retrofit interface covering fixtures, inspections, observations (multipart), investigate, segment, diagnostics run, work order, repair claims, checklist, acknowledge, verify, receipt, artifacts. Contract tests from recorded JSON captured by curl against the running API (`fixtures/api-recordings/`). Emulator boots and hits `/health` on `10.0.2.2:5199`.
2. **Oct 29: capture and upload.** CameraX full-resolution JPEG, SHA-256 on device, `Idempotency-Key` per capture, Room queue + WorkManager drain, "awaiting upload" state. Screen 1 (fixture/asset) and Screen 2 (capture). Gate: a photo taken on the emulator appears in the API state and the browser UI.
3. **Oct 30: investigate and localize.** Screen 3: assessment badges, SAM overlay drawn from RLE on the original (port the decoder), crop viewer, requested-view instruction. Gate: emulator screenshot with a genuine Muse assessment and SAM overlay saved to `docs/evidence/android/`.
4. **Oct 31: verify and receipt.** Screen 4: work order, claim, checklist, acknowledge, verify; banner text from the decision; `receipt.html` in a WebView; share JSON. Gate: fake fix denied and real fix verified on the emulator; screenshots recorded.
5. **Nov 1: offline and polish.** Airplane-mode capture then sync; `Cached` label on knowledge facts; copy review. Gate: the hero flow runs on the emulator from a cold install.
6. **Nov 2–3: voice (P1, only if the above is green).** Push-to-talk repair claim: record audio, `POST` to a new API endpoint that calls Muse Voice Transcribe, show the transcript for confirmation before it becomes the claim text. This adds a third Meta model to the demo honestly. Wearables Mock Device Kit stays out unless time remains on Nov 3, and is labelled simulated if shown.

Android is **never** on the critical path: if any gate slips, the video uses the browser UI and the Android section is omitted, not faked.

### Track C — Reproducibility, demo, attribution (Oct 29 → Nov 3)

1. **Oct 29: clean-room script.** `scripts/clean_room_check.sh`: fresh clone to a temp dir, venv, `dotnet test`, start API, hero flow via curl with the simulated probe, `verify-receipt`, `Eval --only c01-clear-p2,f01-seated-down`, PASS/FAIL. Fix README until it passes. Run it again after every integrator merge.
2. **Oct 30: storyboard.** `docs/DEMO_STORYBOARD.md` per PRD §5 with exact clicks, on-screen labels and what is simulated versus live; opens with the fake-fix denial. Reserve 20 s for the Android segment and 10 s for the live-probe receipt; both are cut if their tracks do not deliver.
3. **Oct 31–Nov 1: rehearsal captures.** Record the browser flow at 1280×800 as individual clips so the final edit does not depend on one perfect take; record the `verify-receipt` byte-flip in a terminal.
4. **Nov 2: README and evidence index.** Ten-line "What this is", hero screenshot, evaluation headline, reproduce commands, `docs/evidence/README.md` indexing every evidence file with its label (`LIVE_META`, `LIVE_LOCAL_PROBE`, `SIMULATED_PROBE`).
5. **Nov 3: attribution and freeze prep (T7.4, T8.1).** Ledger complete (one row per merged feature), `PRIOR_ART.md` register empty and third-party inputs current, secret scan, screenshot sanitation, media rights note, tag `v0.9-hackathon-freeze`.

### Track D — Owner's hardware (needs the user; prepared by Track A)

Thirty minutes of the owner's time, any day Oct 30–Nov 2:
1. Point `Diagnostics__Targets__lab-node-a` at any owned HTTP endpoint on the LAN (a Pi, a NAS, a second laptop), run the two prepared commands, keep the receipt bundle (`docs/evidence/receipt-live/`), unplug, run again for the `Unavailable` case.
2. Photograph real equipment with a yellow patch cable (own gear, no serials or client networks), three to five photos into `fixtures/telecom-lab/images/real/` with a rights note; run `/investigate` and `/segment` on each and append a `real-*` scenario set to the evaluation. Record whatever happens.
If the owner cannot do this by Nov 2, the entry stays Simulated and says so.

### Daily syncs and the integrator's checklist

At each sync the integrator: pulls every track branch, rebases onto `main`, runs `dotnet test`, `scripts/secret_scan.sh` and `scripts/clean_room_check.sh`, merges `--no-ff`, runs `ledger_add.py` per feature, ticks `tasks.md`, pushes, and posts a one-paragraph status in `docs/STATUS.md` (date, merged features, test count, what is blocked). Nightly: full evaluation run on `main`, report committed.

### Revised calendar

| Date (UTC) | A backend | B Android | C demo/repro | D owner |
|---|---|---|---|---|
| Oct 28 | hardening starts | skeleton, contract, emulator hits API | — | — |
| Oct 29 | hardening green | capture + upload | clean-room script PASS | — |
| Oct 30 | change evidence; loopback live probe | investigate + localize | storyboard | live endpoint + real photos (30 min) |
| Oct 31 | change evidence gate (noon cut) | verify + receipt | rehearsal clips | — |
| Nov 1 | second opinion (optional) | offline + polish; cold-install hero flow | rehearsal clips | — |
| Nov 2 | regression and nightly eval | voice claim (P1) | README, evidence index | last chance for D |
| Nov 3 | freeze support | voice polish or wearables mock (labelled) | attribution, tag | — |
| Nov 4 | — | — | final clean-room run, submit by 9 PM ET | — |

## 4. Cutlines

- Android never blocks submission: any missed gate removes the Android segment from the video and the claim from the README; nothing is faked.
- Change evidence: cut at Oct 31 noon; keep the spec, remove partial code from `main`.
- Live probe and real photos: owner-dependent; absent by Nov 2 evening means the entry stays Simulated with an explicit note.
- Voice and wearables: only after Track B's Nov 1 gate; wearables only with the Mock Device Kit and a "simulated" label.
- Clean-room script failing on `main` at any sync stops all merges until it passes.

## 5. Known traps (each cost real time already)

- Muse Spark spends reasoning tokens inside `max_tokens`; keep `reasoning_effort: low` and a 6000-token budget or the content comes back empty.
- Strict JSON schema: every property required, `additionalProperties: false`, no `allOf`, nullable via `"type": ["string","null"]`.
- SAM uses `/v1/responses`, not chat completions; `include_confidence` is the string `"true"`; a data URL works; close-up renders return honest no-matches.
- Minimal-API JSON binding needs `JsonStringEnumConverter` registered (done in `Program.cs`); do not remove it.
- Idempotency keys only from the `Idempotency-Key` header; never derive a request ID from content.
- `JsonSchema.Net` registers `$id` globally; each validator uses its own `SchemaRegistry` (done). Keep that pattern for any new schema.
- `.gitignore` ignores `data/`, `.env`, `.venv`; `.env.example` is whitelisted. The API reads `.env` only as a developer fallback.

## 6. Definition of done for the submission

- `scripts/clean_room_check.sh` passes from a fresh clone.
- `dotnet test` ≥ 110 passing, evaluation report regenerated after hardening.
- `docs/evidence/` holds Muse, SAM, UI, receipt and (if available) live-probe evidence with no secrets.
- Ledger has one row per commit; PRIOR_ART register empty; video link public; submission recorded.
