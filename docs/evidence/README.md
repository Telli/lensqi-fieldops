# Evidence index

Everything here was produced during the event by running the code in this repository. Each item carries one of these
labels, and nothing is described more strongly than its label allows.

| Label | Meaning |
|---|---|
| `LIVE_META` | A genuine call to the Meta Model API (`muse-spark-1.3` or `sam-3.1`). The output is a model interpretation, never proof of a repair. |
| `LIVE_LOCAL_PROBE (loopback)` | A genuine HTTP measurement by the live probe of a `python3 -m http.server` on this Mac's loopback, standing in for an owned lab endpoint. Not lab hardware. |
| `SIMULATED_PROBE` | The demo's simulated endpoint. Every verification that relies on it is `VerifiedCompleteSimulated`, never live. |
| `SYNTHETIC_IMAGE` | A Pillow render of a fictional switch (`tools/sam-parser/render_fixture.py`). No photographs of real equipment are included yet. |
| `EMULATOR` | The Android client running on the Android emulator (Medium Phone, API 36.1) on this Mac. Not a physical phone. |

## Meta model calls

| File | What it shows | Labels |
|---|---|---|
| `stage-one-gate-20261028T014314Z.json`, `stage-one-gate-20261028T021847Z.json` | Two grounded `muse-spark-1.3` proposals per run with strict JSON schema, prompt digest, fact IDs, server-side validation (`allAccepted: true`). No diagnostic was run. | `LIVE_META` |
| `sam-spike-20261028T022522Z.json`, `sam-spike-20261028T022522Z-overlay.jpg` | First `sam-3.1` calls through the Responses API: raw output digest, parsed boxes and masks, overlay drawn on the original image. | `LIVE_META`, `SYNTHETIC_IMAGE` |

## Web app (current)

The rebuilt technician app and operations dashboard, walked through end to end on 2026-10-28 against the local API
(genuine Muse and SAM calls, simulated endpoint, synthetic renders imported through the photo picker).

| File | What it shows | Labels |
|---|---|---|
| `ui-v2/01-home.jpg` | Technician home: live counts, work needing verification, open inspections. | — |
| `ui-v2/02-scan-ambiguous-ai-outline.jpg` | Camera result: SAM's mask and box on the cable, Muse unsure about the hidden connector, the requested next view. | `LIVE_META`, `SYNTHETIC_IMAGE` |
| `ui-v2/03-investigate-requested-view.jpg` | Investigation stepper with the AI's requested view and guidance photo. | `LIVE_META`, `SYNTHETIC_IMAGE` |
| `ui-v2/04-same-photo-rejected.jpg` | Post-repair photo re-submitted: "Photo not accepted · Same photo". | `SYNTHETIC_IMAGE` |
| `ui-v2/05-fake-fix-verification-failed.jpg` | Visual check ✓, equipment check ✕, **Verification failed**. | `SIMULATED_PROBE` |
| `ui-v2/06-real-fix-verified-simulated.jpg` | Both checks ✓, **Verified complete** with the simulated caveat. | `SIMULATED_PROBE` |
| `ui-v2/07-fieldproof-receipt.jpg` | Receipt: discrepancy, technician actions, before/after with outlines, decisions ✕ ✓, checks with source badges. | `SIMULATED_PROBE`, `SYNTHETIC_IMAGE` |
| `ui-v2/08-ops-overview.jpg`, `ui-v2/09-ops-inspection-workspace.jpg` | Operations dashboard from real inspection data; workspace with photo, AI observations, verification state, timeline, receipt. | `SIMULATED_PROBE` |
| `ui-v2/10-ar-scan-outline-tags.jpg` | Web scan after capture: real SAM outline with a label pinned to its box, coloured by Muse's finding; asset pill from the AI's asset reading. SAM found no "network switch" region in this render, so that finding has no label (nothing placed by guesswork). | `LIVE_META`, `SYNTHETIC_IMAGE` |
| `ui-v2/11-ar-target-region.jpg` | The AI asked for a closer view: the unclear part's outline is the target region, everything else darkened, amber "not clear" label. | `LIVE_META`, `SYNTHETIC_IMAGE` |
| `ui-v2/12-hands-free-view.jpg` | Hands-free view (web): one spoken instruction from live state, latest photo with outlines and its source. | `LIVE_META`, `SYNTHETIC_IMAGE` |
| `ui-v2/13-ops-evidence-strip-request.jpg` | Ops workspace: evidence strip with each photo's origin and SHA-256, the request sent to the technician ("Waiting for a photo"), required-evidence checklist. | `META_WEARABLE_MOCK`, `LIVE_META` |
| `ui-v2/14-ops-asset-expected-state.jpg`, `ui-v2/19-tech-asset-expected-state.jpg` | Asset detail (ops and technician): the site record's expected connection (port P2 → Lab Node A, cable YELLOW-A, connected), labelled as intent not proof; latest outlined photo; inspection history with attempts. | `SYNTHETIC_IMAGE`, `LIVE_META` |
| `ui-v2/15-ops-knowledge-documents.jpg` | Knowledge packs with identity (firmware, hardware applicability) and each source document: publisher, version, access date, fact count, official link. | documented |
| `ui-v2/16-ops-reports-integrity.jpg` | Reports with status filters, attempts per inspection and a live integrity column (each receipt bundle re-hashed on the server: 40/40 intact at capture). | `SIMULATED_PROBE` |
| `ui-v2/17-tech-home-evidence-requested.jpg`, `ui-v2/18-tech-investigation-request-banner.jpg` | The ops request on the technician's home and as a banner on the investigation, with the action to answer it. | — |

## Browser UI (first version, replaced)

| File | What it shows | Labels |
|---|---|---|
| `ui/01-muse-assessment.jpg` | Muse assessment with Observed / Suspected / Unknown badges in the browser. | `LIVE_META`, `SYNTHETIC_IMAGE` |
| `ui/02-fake-fix-rejected.jpg` | The gate denies a claimed repair while the endpoint is still down. | `SIMULATED_PROBE` |
| `ui/03-verified-and-receipt-pass.jpg` | `VERIFIED COMPLETE · SIMULATED` banner and the receipt bundle re-verified. | `SIMULATED_PROBE` |
| `ui/04-sam-overlay-and-crop.jpg`, `ui/04b-roi-crop-original-pixels.jpg` | SAM mask and box on the original photo, and the ROI crop cut from original pixels. | `LIVE_META`, `SYNTHETIC_IMAGE` |
| `ui/05-change-evidence-duplicate.jpg` | A re-submitted post-repair photo: banner **CHANGE EVIDENCE: DuplicateImage**, stage back to NeedsEvidence, tag on the photo. | `SYNTHETIC_IMAGE`, `SIMULATED_PROBE` |
| `ui/06-fake-fix-denied-reason.png` | The denial banner after a new post-repair photo while the endpoint is still down: "Diagnostic failed after the repair claim: NODE-A-HEALTH measured Unreachable (Simulated probe)", with the check still required. | `SIMULATED_PROBE` |

## Android client

Driven on the emulator against the local API. Imported images are synthetic renders labelled `RecordedFixture`;
camera captures are the emulator's virtual scene, labelled `Phone`.

| File | What it shows | Labels |
|---|---|---|
| `android/01-connect-health.png` | The app reaches `/health` at `10.0.2.2:5199`, shows the diagnostics mode badge and the fixture. | `EMULATOR`, `SIMULATED_PROBE` |
| `android/02-capture-uploaded.png`, `android/03-browser-shows-emulator-photo.jpg` | A camera capture uploaded through the offline queue, then visible in the browser UI for the same inspection. | `EMULATOR` |
| `android/04-muse-assessment.png`, `android/05-sam-overlay.png` | Muse assessment and SAM overlay (mask decoded on the phone from COCO RLE). | `EMULATOR`, `LIVE_META`, `SYNTHETIC_IMAGE` |
| `android/06-fake-fix-denied.png` … `09-receipt-html.png` | Fake fix denied, real fix `VerifiedCompleteSimulated`, receipt re-verified from the app, `receipt.html` in a WebView. | `EMULATOR`, `SIMULATED_PROBE` |
| `android/10-offline-capture-awaiting-upload.png` … `12-reconnected-synced.png` | Airplane-mode capture queued with its device SHA-256, cached view and knowledge labelled `CACHED`, automatic upload after reconnecting with a matching server digest. | `EMULATOR` |
| `android/13-cold-install-connect.png` … `15-cold-install-real-fix-verified.png` | The whole hero flow from a fresh install. | `EMULATOR`, `LIVE_META`, `SIMULATED_PROBE` |
| `android/16-change-evidence-duplicate.png` | A re-submitted photo after the claim: change evidence `DuplicateImage`, inspection back to NeedsEvidence. | `EMULATOR`, `SYNTHETIC_IMAGE` |
| `android/17-restyle-camera.png` | Restyled capture screen (design port): glass top bar, the requested view as the prompt, ringed shutter. The preview is the emulator's virtual scene. | `EMULATOR` |
| `android/18-restyle-investigation.png` | Investigation stepper after the AI looked at an imported lab render: plain labels ("AI: visible"), the part outline on the photo. | `EMULATOR`, `LIVE_META`, `SYNTHETIC_IMAGE` |
| `android/19-restyle-same-photo-blocked.png` | Fake fix on the restyled verification screen: the same photo after the claim is flagged "Same photo" and verification stays locked. | `EMULATOR`, `SYNTHETIC_IMAGE` |
| `android/20-restyle-verification-failed.png` | New photo reviewed but the equipment check still fails: two check rows and the plain-language failure card. | `EMULATOR`, `SIMULATED_PROBE` |
| `android/21-restyle-home.png` | Restyled home: connection badge, counts and work requiring verification, from `GET /v1/inspections`. | `EMULATOR` |
| `android/22-restyle-verified.png` | Real fix verified, labelled Simulated, with before/after photos and the receipt integrity check (intact, 10 checks). | `EMULATOR`, `SIMULATED_PROBE` |
| `android/23-restyle-receipt.png` | The designed FieldProof receipt from the API's web app, embedded in the Android app. | `EMULATOR`, `SIMULATED_PROBE` |
| `android/24-glasses-01` … `24-glasses-06-mock-display-hud.png` | Glasses capture through Meta's Wearables Device Access Toolkit 1.0.1: entry point; real registration path stopping at "Meta AI app not installed" (no hardware here); Mock Device Kit streaming a lab clip and the emulator camera; a mock photo queued and labelled "Mock glasses"; the instruction on a mock Meta Ray-Ban Display. See `docs/GLASSES.md`. | `EMULATOR`, `META_WEARABLE_MOCK` |
| `android/25-ar-review-mock-glasses.png` | Post-capture review after a mock-glasses capture: "Photo from Mock glasses · simulated", the asset pill matching the site record, the yellow cable outlined by SAM with its AI label, findings from Muse. Stored on the server as origin `MockWearables`. | `EMULATOR`, `META_WEARABLE_MOCK`, `LIVE_META`, `SYNTHETIC_IMAGE` |
| `android/26-glasses-streaming-mock.png` | Glasses capture screen streaming from mock Ray-Ban Meta glasses, with the spoken instruction panel and the shutter. | `EMULATOR`, `META_WEARABLE_MOCK` |
| `android/27-home-evidence-requested.png`, `android/28-inspection-request-banner.png` | The ops request on the Android home and inspection; answered by the next photo (server shows `answeredByPhotoId`, ops shows "Answered · show photo"). | `EMULATOR` |
| `android/29-asset-expected-state.png` | Android asset screen: expected installation state from the site record and the asset's inspection history. | `EMULATOR` |

## Receipt bundles

Verify any bundle offline with `dotnet run --project tools/LensQi.FieldOps.VerifyReceipt -- <dir>`; a one-byte change in
any artifact or field fails it. A digest proves bundle integrity only, never who captured an image.

| Directory | What it shows | Labels |
|---|---|---|
| `receipt-sample/` | First issued receipt: fake fix denied then real fix `VerifiedCompleteSimulated` (policy 1.1, before change evidence). | `SIMULATED_PROBE`, `SYNTHETIC_IMAGE` |
| `receipt-loopback/` | Live probe end to end: fault measured as a 404 FAIL, repair measured as 200, `VerifiedCompleteLive`, change evidence `Changed`; `RUN.md` also records the `UNAVAILABLE` result with the server stopped. See `docs/LIVE_PROBE_RUNBOOK.md`. | `LIVE_LOCAL_PROBE (loopback)`, `LIVE_META`, `SYNTHETIC_IMAGE` |

## Evaluation reports (`docs/evaluation/`)

| Report | What changed | Headline |
|---|---|---|
| `eval-20261028T032756Z` | First run, 25 scenarios (4 held out). | 25/25 deterministic, 0 gated false completions (single-look baseline 4/5) |
| `eval-20261028T035339Z` | After hardening (T6.1). | Same headline; no request hit the new limits |
| `eval-20261028T061752Z` | After change evidence (VK-T26); scenarios v2 with distinct post-repair renders. | 25/25; change evidence 2 duplicate / 1 no change / 6 changed / 2 unscorable; 0 probes on rejected photos |

All evaluation runs use `SYNTHETIC_IMAGE` inputs, `LIVE_META` model calls and the `SIMULATED_PROBE`.

## API contract recordings (`fixtures/api-recordings/`)

37 responses captured by `scripts/record_api.py` from the running API (investigate and segment are `LIVE_META`;
diagnostics are `SIMULATED_PROBE`). The Android contract tests decode every one of them.

## Not yet recorded

- A live measurement of owned lab hardware (`receipt-live/`), produced by the owner with `docs/LIVE_PROBE_RUNBOOK.md`.
- Photographs of real equipment (`fixtures/telecom-lab/images/real/`).
- A physical-phone run of the Android client.
