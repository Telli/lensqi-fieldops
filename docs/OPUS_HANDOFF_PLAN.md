# Handoff plan for the next agent (Opus) — finish the hackathon entry by November 4

**Written:** October 28, 2026 (UTC) at commit `cc2f72d`. **Deadline:** November 4, 2026, 9:00 PM Eastern.
**Repository:** <https://github.com/Telli/lensqi-fieldops>, branch `main`, 29 event-period commits, 104 passing tests.
**Read first, in this order:** `README.md`, `PRD.md` §3–§8 and §17, `.kiro/specs/lensqi-fieldops/tasks.md`, `docs/HACKATHON_CONTRIBUTIONS.md`, then the project skills under `.claude/skills/` (they encode the conventions and the traps already hit).

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

Not done: hardening pass (T6.1), live owned endpoint smoke (T3.3 live half), demo video (T7.3), final attribution pass (T7.4), freeze and submit (T8). Android is **P1** by recorded decision (PRD §19); no Android build is claimed. Child-spec items VK-T07/T08/T10/T12/T13/T14 and the prediction/change-evidence tasks VK-T24–T27 are partly implemented inside the parent work (predictions are registered and scored; change analysis VK-T26 is **not** built).

## 2. Hard rules that must not be broken

1. Only `InspectionSession` changes `InspectionStage`. No endpoint, model output, probe or UI may set a stage.
2. The model never chooses a URL, host, command, path, credential or verification outcome. Diagnostics run only from a fixture-approved ID through a server-configured alias.
3. Simulated and replayed results are labelled everywhere and can never produce `VerifiedCompleteLive`.
4. Every evidence item keeps its origin, server timestamp and SHA-256. Receipts hash canonical JSON; verification recomputes.
5. No private LensIQ source is copied (PRD §4A). Every commit is event-period work and goes into the ledger.
6. Never claim a live measurement, a hardware test, a photograph or an Android build that did not happen. The video shows only what exists.

## 3. Day plan (remaining)

Times are UTC. The order is chosen so that at any cutoff the repository is submittable.

### Oct 28–29 — Hardening (T6.1) and truthfulness sweep

- **Bookkeeping check (10 min).** The stale boxes (T0.1–T0.3, Day 1–6 gates) were ticked on Oct 28; confirm `tasks.md` matches reality after each day and keep ticking with a one-line note. Every feature commit gets a ledger row via `python scripts/ledger_add.py`.
- **Provider-down behaviour.** `POST /investigate`, `/segment`, `/knowledge/reason` already return `provider_unavailable` on HTTP or timeout errors; add a test per endpoint with a throwing fake, and make the UI banner say "model unavailable; inspection stays where it is" (it does today via the generic error path; make it explicit).
- **Cancellation and timeouts.** Muse 60 s and SAM 60 s are configured; add `RequestTimeouts` middleware (90 s) on the three model endpoints and pass the request `CancellationToken` through (already done). Verify with a test that a cancelled request leaves no half-written event (the store writes events only after the command succeeds; assert the JSONL line count).
- **Image checks.** `EvidenceFileStore.MaxImageBytes` is 12 MB; add a minimum dimension check (reject below 320 px on either side when width/height are supplied) and a cheap quality signal: compute the Laplacian-variance blur score and mean luminance in the Python sidecar (`sam_crop.py --stats`) and store them in `PhotoRecorded` (two nullable doubles). Do not gate on them; FR-02 says quality checks are signals, not evidence. Show them in the UI photo caption.
- **Rate limiting and budgets.** Add ASP.NET rate limiting: 30 model calls per inspection and 120 per hour per client IP, 429 with `ApiError("rate_limited")`. Add a per-inspection model-call counter to `InspectionState` (events `AssessmentRecorded` + segmentations) and refuse `/investigate` beyond 6 calls with `invalid_state` (the two-view budget already bounds the loop; this bounds cost).
- **Secrets and logs.** Grep the repo and `docs/evidence/` for the key prefix before every push (`scripts/secret_scan.sh`, write it). Make sure request logging never prints the data URL (it does not today; add a test that the Muse client's exception message truncates to 600 chars, which exists, and that no logger receives the body).
- **Gate:** `dotnet test` green, 110+ tests, `docs/evidence/` clean, ledger row added.

### Oct 30 — Live probe and real photographs

- **Live owned endpoint.** Run any owned HTTP service on the LAN (a Raspberry Pi, or `python3 -m http.server` on a second machine) and set `Diagnostics:Mode=LiveLocal` with `Targets.lab-node-a` pointing at it. Drive the hero flow; confirm `VerifiedCompleteLive` appears exactly once in a receipt and `LIVE` on the banner. Unplug the endpoint, re-run, confirm `Unavailable` (Passed = null) and a denial. Save the receipt bundle to `docs/evidence/receipt-live/` and add `LIVE_LOCAL_PROBE` to the ledger. If no second device exists, state that plainly in the ledger and keep Simulated; do not fake it.
- **Real photos.** Photograph any real switch or router with a yellow patch cable (your own equipment, no serials or client networks in frame). Add two or three to `fixtures/telecom-lab/images/real/` with a `README` stating rights. Run `/investigate` and `/segment` on them and append the results to a new evaluation run with `--only` plus a small `real-*` scenario set. Expect SAM to behave differently than on renders; record whatever happens.
- **Gate:** at least one receipt with a live outcome or an explicit note that none exists; real-photo results recorded.

### Oct 31 — Change evidence (VK-T26) if time allows, else skip

This is the one frontier item not built. Budget one day, drop it at noon if it is not green.

- Core: `VisualChangeAnalyzer` with a 64-bit dHash computed by the sidecar (`sam_crop.py --dhash`) and mask IoU from two `MaskRecord`s (decode RLE in C#: port the COCO decoder from `app.js`). Thresholds in `appsettings` with a `ThresholdsVersion`. Event `VisualChangeAnalyzed`.
- Api: run automatically after the post-claim photo upload when a segmentation exists for the pre-claim photo with the same prompt. `DuplicateImage` (hash distance ≤ 2) leaves the checklist locked for that photo in the UI.
- Tests: re-submitted photo → `DuplicateImage` with zero SAM calls; unchanged render → `NoVisualChange`; moved cable render (`port` 5 → 2) → `Changed`.
- **Gate:** the fake-fix scenario with the *same* photo re-uploaded is caught before the probe runs.

### Nov 1 — Clean-room reproducibility (T6 gate, VK-T13)

- Fresh clone into a temp directory on this Mac: `git clone`, `python -m venv .venv && .venv/bin/pip install -r scripts/requirements.txt -r tools/sam-parser/requirements.txt`, `dotnet test`, `dotnet run --project src/LensQi.FieldOps.Api`, open the UI, run the hero flow with the simulated probe, run `verify-receipt` on the issued bundle, run the eval with `--only c01-clear-p2,f01-seated-down`. Fix anything that needed a step not in the README. Add a `make`-free `scripts/clean_room_check.sh` that does exactly this and prints PASS.
- Write `docs/DEMO_AND_EVALUATION.md` "How to reproduce" from the checked steps.
- **Gate:** the script passes from a fresh clone with only `MODEL_API_KEY` supplied.

### Nov 2 — Demo video (T7.3) and README polish

- Storyboard in `docs/DEMO_STORYBOARD.md` following PRD §5 timings, then record with the browser UI at 1280×800:
  1. 0:00–0:30 Fake fix first: show the denied verification and the red banner before anything else.
  2. 0:30–1:15 The loop: fixture, photo, Muse assessment with Unknown badges, Localize with the SAM mask, grounded proposal citing a fact ID, prediction registered, probe, work order, claim.
  3. 1:15–2:00 The boundary: paste a proposal with a fabricated fact ID and a `POST /rest/system/reboot` diagnostic into the grounded-proposal box (type the observation that provokes it, or call the endpoint from a terminal) and show the `KnowledgeProposalRejected` codes.
  4. 2:00–2:40 Receipt: open `receipt.html`, run `verify-receipt`, flip a byte in a terminal, run again.
  5. 2:40–3:00 Ledger and commit dates on screen; state what is simulated and what is live.
- Keep every on-screen label truthful: "SIMULATED" stays visible whenever the simulated probe is used.
- README: a 10-line "What this is" at the top, the hero screenshot, the evaluation headline table, and the reproduce commands. Do not add claims the evidence folder cannot back.

### Nov 3 — Attribution (T7.4) and freeze prep (T8.1)

- `docs/HACKATHON_CONTRIBUTIONS.md`: ensure every commit has a row (the helper appends them), and add a "What judges should look at first" list with five file links.
- `docs/PRIOR_ART.md`: confirm the copied-source register is empty and third-party inputs are current (add `meta-sam-parser`, `Pillow`, `Microsoft.AspNetCore.Mvc.Testing`).
- Secret scan, screenshot sanitation (`docs/evidence/ui/`), license check of `fixtures/` media (all synthetic or own photographs).
- Tag `v0.9-hackathon-freeze`.

### Nov 4 — Submit (T8.2, T8.3)

- Final `scripts/clean_room_check.sh` run, push, verify the public URL and the video link open in a private browser window, submit on the Cerebral Valley form before 9:00 PM ET. Record the submission time in the ledger.

## 4. Cutlines

- If the live endpoint is not available by Oct 30 evening: stay Simulated, say so, move on.
- If VK-T26 is not green by Oct 31 noon: remove its partial code from the branch, keep the spec, note it as not built.
- If the clean-room check fails on Nov 1: nothing else is worked on until it passes.
- Never start Android, wearables or voice before Nov 4. They are P1 and the PRD says so.

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
