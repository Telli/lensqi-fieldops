---
name: fieldops-run-and-verify
description: How to build, test, run the FieldOps API and browser UI, drive the hero flow (fake fix then real fix), issue and verify receipts, and run the evaluation. Load when asked to run, demo, reproduce, smoke-test or regenerate the evaluation.
---

# Run and verify FieldOps

## Build and test
```bash
dotnet test --nologo            # 214 tests across Core, Meta, Api (Oct 29)
npm --prefix apps/web test      # 25 web tests
cd apps/android && ./gradlew :app:testDebugUnitTest   # 107 Android unit tests
```
Python helpers (once): `python3 -m venv .venv && .venv/bin/pip install -r scripts/requirements.txt -r tools/sam-parser/requirements.txt`. Fixture validators: `python scripts/validate_expected_twin.py && python scripts/validate_vendor_packs.py`.

## Run the API + UI
```bash
dotnet run --project src/LensQi.FieldOps.Api --no-launch-profile --urls http://127.0.0.1:5199
```
Open `http://127.0.0.1:5199/m` (mode home: Do it yourself, Just ask, Technician; the technician home is `/m/tech`) or `/ops` (operations dashboard, guided tasks at `/ops/tasks`). The UI is `apps/web` (React + Tailwind + TanStack Query), built into `wwwroot` and committed: after any UI change run `cd apps/web && npm run build` and commit `wwwroot` too (the clean-room check diffs it against a fresh build). `npm run dev` serves on 5173 with a proxy to 5199. The launch config `.claude/launch.json` (`fieldops-api`) does the same for the preview pane. The API fails closed at startup on any invalid pack, binding or twin. Restarting keeps inspections and tasks (`data/tasks/*.jsonl`, replayed through `TaskSession.Rehydrate`): every `data/inspections/*.jsonl` log is replayed through `InspectionSession.Rehydrate`; a log with a different fixture digest, a sequence gap or bad JSON is left on disk and named in a startup warning. `/health` reports `diagnosticsMode`; the UI's demo controls exist only in `Simulated` mode.

## Hero flow over HTTP (curl), simulated probe
1. `POST /v1/inspections {fixtureId:"LAB-RACK-01", assetId:"LAB-SW-01"}` → `inspectionId`
2. `POST /v1/simulation/diagnostics/NODE-A-HEALTH {passed:false}`
3. `POST /v1/inspections/{id}/observations` multipart `image=@fixtures/telecom-lab/images/lab-sw-01-initial-synthetic.jpg source=RecordedFixture`
4. `POST .../investigate` (genuine Muse) · `POST .../segment {concept:"yellow ethernet cable"}` (genuine SAM)
5. `POST .../diagnostics/NODE-A-HEALTH:run` → `FaultConfirmed` · `POST .../work-order {summary}` · `POST .../repair-claims {description}`
6. Upload a post-repair photo; `POST .../checklist {requirementId, status:"Satisfied", photoEvidenceId}` for the three mandatory items; `POST .../acknowledge`; `POST .../verify` → **Denied → VerificationFailed** (fake fix)
7. Set simulation `passed:true`, claim again, upload, checklist, acknowledge, verify → **Verified, VerifiedCompleteSimulated**
8. `GET .../receipt.html`, `GET .../receipt/verify`; CLI: `dotnet run --project tools/LensQi.FieldOps.VerifyReceipt -- src/LensQi.FieldOps.Api/data/receipts/{id}` (flip a byte in `artifacts/` to see FAIL)
Use an `Idempotency-Key` header to prove replay on `/verify`. Errors come back as `{code, message}` with HTTP 409 for typed conflicts.

## Guided task over HTTP (curl)
1. `POST /v1/tasks {mode:"Diy", goal:"Change the engine oil in my 2025 Honda Civic", details:"1.5L turbo"}` (genuine Muse plan; multipart adds up to 3 `manual` photos) → `task.taskId`, or `planError` with `invalid_plan` / `unsafe_plan` / `provider_unavailable`; retry with `POST /v1/tasks/{id}/plan`
2. `POST /v1/tasks/{id}/accept {safetyAcknowledged:true}` → first step active
3. `POST .../steps/{stepId}/check` multipart `image` (suggested verdict only) · `POST .../ask` multipart `question` (+ optional `image`) · `POST .../live/detect|guide` multipart `image`, `frameId`
4. `POST .../steps/{stepId}:complete` or `:skip`; the last one completes the task. A step whose check is `required` needs a check first
5. Technician: create with `mode:"Technician", fixtureId:"LAB-RACK-01", assetId:"LAB-SW-01"`, then `POST .../link-inspection` → `inspectionId`, and run the hero flow above on it. `TechnicianJourneyTests` does this end to end with fakes
6. Free-form help: `POST /v1/tasks {mode:"Freeform"}` → ask and live at once; `POST .../finish` ends it
A restricted goal (for example "Replace the breaker panel in my garage") must come back with `restricted` set, `professionalRequired: true` and no `handsOn` step.

## Hardening limits (T6.1, `Hardening` section in `appsettings.json`)
- Photos: pixel size is read from the file header (the form's `width`/`height` are ignored); under 320×240 → 400 `image_too_small`, over 50 MP → `image_too_large`, no readable header → `image_unreadable`. Blur (Laplacian variance) and mean luminance from `tools/sam-parser/image_quality.py` ride on `PhotoRecorded.quality` as signals only; a sidecar failure records `null` and never blocks.
- Model endpoints (`investigate`, `segment`, `knowledge/reason`): 90 s request timeout → 504 `model_timeout`; 30 calls per inspection per 24 h and 120 per hour per client IP → 429 `rate_limited` with `Retry-After`. In-memory, reset on restart. A full evaluation from one machine makes roughly 50–80 model calls; raise `Hardening__ModelCallsPerHourPerIp` if you run it twice within an hour.
- Provider failures → 409 `provider_unavailable`; provider error text has data URLs and bearer tokens redacted before it reaches an exception, a response or a logger.

## Evaluation
```bash
dotnet run --project tools/LensQi.FieldOps.Eval -- --base http://127.0.0.1:5199   # add --only id,id or --no-sam
```
Writes `docs/evaluation/eval-<stamp>.{json,md}`; re-render scenario images with `.venv/bin/python tools/sam-parser/render_fixture.py --scenarios fixtures/telecom-lab/eval/scenarios.json --out fixtures/telecom-lab/eval/images`.

## Change evidence (VK-T26, `ChangeEvidence` section)
Every photo uploaded after the current repair claim is analysed in the upload request: byte digest or dHash within 3 of any earlier photo → `DuplicateImage` (no SAM call); otherwise one SAM call with the concept of the newest matched pre-claim segmentation, compared by mask IoU and centroid shift → `NoVisualChange` or `Changed`; no reference or no mask → `Unscorable`. DuplicateImage/NoVisualChange send the session back to NeedsEvidence, lock the checklist for that photo (`change_evidence_rejected`) and so no probe can run at verify. The response carries `changeAnalysis`; the view carries `changeAnalyses` and `acceptedPostRepairPhotoId`. Eval scenarios use `<id>-post.jpg` renders (`postRender`) for distinct post-repair photos.

## Live probe
Set `Diagnostics:Mode=LiveLocal` and `Diagnostics:Targets:lab-node-a=http://<owned-host>/health` (env `Diagnostics__Mode`, `Diagnostics__Targets__lab-node-a`). Only then can a receipt show `VerifiedCompleteLive`. Redirects, oversize bodies and errors yield `Unavailable`. Rehearse with the `fieldops-api-live-loopback` launch config (port 5202) plus a loopback `python3 -m http.server 8765`, then `scripts/live_probe_flow.py`; see `docs/LIVE_PROBE_RUNBOOK.md`.

## When something breaks
- Empty model content → reasoning budget (see fieldops-meta-models).
- 400 on JSON bodies with enum names → the string enum converter in `Program.cs` is missing.
- "Overwriting registered schemas" → a validator shared the global `SchemaRegistry`.
- Sidecar failures → `FIELDOPS_PYTHON` points at a Python without `meta-sam-parser`/Pillow.
- Stage did not advance after `/investigate` → check the 409 body; the session rejected the assessment (foreign asset, unknown feature, RequestView without text).
