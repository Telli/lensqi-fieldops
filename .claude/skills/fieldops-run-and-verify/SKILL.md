---
name: fieldops-run-and-verify
description: How to build, test, run the FieldOps API and browser UI, drive the hero flow (fake fix then real fix), issue and verify receipts, and run the evaluation. Load when asked to run, demo, reproduce, smoke-test or regenerate the evaluation.
---

# Run and verify FieldOps

## Build and test
```bash
dotnet test --nologo            # ~104 tests across Core, Meta, Api
```
Python helpers (once): `python3 -m venv .venv && .venv/bin/pip install -r scripts/requirements.txt -r tools/sam-parser/requirements.txt`. Fixture validators: `python scripts/validate_expected_twin.py && python scripts/validate_vendor_packs.py`.

## Run the API + UI
```bash
dotnet run --project src/LensQi.FieldOps.Api --no-launch-profile --urls http://127.0.0.1:5199
```
Open `http://127.0.0.1:5199/`. The launch config `.claude/launch.json` (`fieldops-api`) does the same for the preview pane. The API fails closed at startup on any invalid pack, binding or twin. `/health` reports `diagnosticsMode`; the UI's demo controls exist only in `Simulated` mode.

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

## Evaluation
```bash
dotnet run --project tools/LensQi.FieldOps.Eval -- --base http://127.0.0.1:5199   # add --only id,id or --no-sam
```
Writes `docs/evaluation/eval-<stamp>.{json,md}`; re-render scenario images with `.venv/bin/python tools/sam-parser/render_fixture.py --scenarios fixtures/telecom-lab/eval/scenarios.json --out fixtures/telecom-lab/eval/images`.

## Live probe
Set `Diagnostics:Mode=LiveLocal` and `Diagnostics:Targets:lab-node-a=http://<owned-host>/health` (env `Diagnostics__Mode`, `Diagnostics__Targets__lab-node-a`). Only then can a receipt show `VerifiedCompleteLive`. Redirects, oversize bodies and errors yield `Unavailable`.

## When something breaks
- Empty model content → reasoning budget (see fieldops-meta-models).
- 400 on JSON bodies with enum names → the string enum converter in `Program.cs` is missing.
- "Overwriting registered schemas" → a validator shared the global `SchemaRegistry`.
- Sidecar failures → `FIELDOPS_PYTHON` points at a Python without `meta-sam-parser`/Pillow.
- Stage did not advance after `/investigate` → check the 409 body; the session rejected the assessment (foreign asset, unknown feature, RequestView without text).
