# Open product scope built after the 3 November review (`codex/open-product-scope`)

The 3 November mobile review listed six capabilities the server or the web client had and the mobile clients did not. The first response set them aside as product scope rather than defects. That decision was reversed the same day, after the fixes merged, and all six were built on this branch. Every one of them keeps the invariants: the client never decides a stage or an outcome, model text is shown as written and labelled as a suggestion, a prediction is calibration and never evidence, and documentation never proves a repair worked.

## What shipped

| Capability | Where | What a technician gets |
|---|---|---|
| Grounded advisor on Android | `ui/inspection/InspectionScreen.kt` "Grounded advisor"; `InspectionViewModel.askAdvisor`; `FieldOpsApi.reason` | "What are you seeing?" → `POST /v1/inspections/{id}/knowledge/reason`. The answer shows the hypothesis verbatim, labelled "Inferred · AI suggestion, not evidence", the facts it rests on with their documents, the view it asks for (camera one tap away), the proposed approved check with a run button when the site lists it, the prediction it registered, and the next action in plain words. A refused proposal shows the server's codes and that it ran nothing. Excluded facts appear under "Not applied to this unit" with a plain reason (`Labels.excludedReason`). |
| Falsifiable predictions on Android | `InspectionScreen.kt` prediction panel; `FieldOpsApi.predictions` | "Prediction before the check": LensQi predicted (inferred) beside the check measured (measured) with Confirmed / Falsified / Unscorable / Pending, and "Ask LensQi to predict the check" before the probe runs. Footer: the measurement decides verification, never the prediction. Snapshot shown with CACHED when unreachable. |
| Grounded advisor and knowledge parity on mobile web | `apps/web/src/pages/m/knowledge.tsx`, `lib/advisor.ts`, `lib/labels.ts` | The same advisor panel on `/m/i/:id/knowledge`; excluded facts with reasons, pack warnings, the SYNTHETIC origin badge, pack digest and approved checks. |
| Continuous hands-free voice on Android | `voice/Voice.kt` `rememberHandsFree`, `HandsFreePolicy`; `TaskScreens.kt` StepScreen and FreeformScreen | An opt-in "Hands-free" chip beside Mute: the microphone opens again after each answer, while LensQi is silent and no request is pending. A badge says where audio goes ("On-device speech recognition · no audio leaves the phone", or that the phone's speech service may send audio to its provider; only text reaches LensQi). While paused, hands-free keeps listening but only "resume", "mute" and "unmute" are heard, matching the web. A microphone that cannot start ends hands-free with one spoken line; silence does not. |
| Tap-to-ask on live outlines on Android | `ui/camera/CameraScreen.kt` `tappedPart`, `PartChips`, `onAskPart`; `domain/live/LiveTracker.staleAlpha` | Tapping any drawn box (2D or ARCore) offers "Ask about the <part>" (asked through `/ask` with the current frame, answered aloud), "Hold as target" where an anchor exists, and Cancel. Boxes no longer seen fade with their age and say "last seen N s ago". |
| Site pack pre-positioning | `src/LensQi.FieldOps.Api/Features/SitePacks/SitePackEndpoints.cs`; Android `data/SitePackStore.kt`, `HomeViewModel.downloadSitePack`, Assets and asset pages | `GET /v1/fixtures/{fixtureId}/pack` returns the site record, each asset's resolved vendor knowledge (facts, excluded, warnings, approved checks), the inspection summaries and the full view of every open inspection. "Download site pack" stores them on the phone under the keys the inspection and home screens already read offline, so an inspection and its documentation open out of signal. Photos are not included, and the button says so. Read-only; no events. |
| Live OCR asset-tag scanning on Android | `ui/home/AssetScanner.kt`, `AssetScan.kt`, `ScanAssetScreen.kt`; `NewTaskScreen` "Scan the label" | ML Kit text recognition (bundled Latin model, on the phone) reads printed ids when no code is in view; a line naming a site-record asset starts the inspection by the same rule as a code, and a near miss is shown as read. A typed id is offered too, as on the web. The technician's new-task screen opens the same scanner as a sheet instead of a list. |
| Decline the requested view (`Inconclusive` exit) | Web `pages/m/investigate.tsx` `DeclineViewAction`; Android `InspectionScreen.kt` "I can't safely get this view"; `POST /v1/inspections/{id}/decline-view` | Only while the stage is NeedsEvidence. A confirm step says what happens: the inspection closes as inconclusive, no fault or repair record is added (earlier work stays on record), the record keeps that the view was declined. Both clients then show the closed card with "You declined the requested view." Refused with `invalid_state` in any other stage; a replayed key returns the same answer. |

## Second pass

An independent read of the combined change found the items recorded in the section below; each was fixed before commit.

- The Android closed card for an inspection whose view budget ran out fell to the generic "a required check is unresolved" line; it now says LensQi asked for as many views as it may and that closing adds no fault or repair record, like the web.
- The asset scanner scheduled ML Kit continuations on its single-thread executor and shut that executor down on dispose while a frame was still in flight, which would have thrown on the completion thread when leaving the scan screen; the executor is now shut down by whoever finishes last.
- Any drawn live box took a tap, so on the inspection camera (no question path) or an unanchored AR box the tap showed a chip row with only Cancel and no longer cleared the chrome; a box is a hit only when a question or a hold is possible.
- The advisor's observation field was prefilled with Muse's reading of the photo and sent unchanged as the technician's observation; it now starts blank, so model text is never recorded as the technician's words and the advisor is never grounded on its own earlier reasoning.
- A failed site pack download wrote the shared home error, and that field also carries unrelated errors; the pack has its own.

## Not claimed

Nothing here ran on a physical phone, a real microphone, a text-to-speech engine or a camera. The hands-free loop, the "LensQi never hears itself" property, OCR on a printed tag, the tap hit-test on a live feed and the site pack on an offline phone are exercised through unit tests of their pure parts and compilation only. `docs/FEATURE_STATUS.md` keeps them at **Built**, not Done.

## Gates on the branch

336 .NET tests (Core 159, Meta 18, Api 159); web typecheck, 77 tests, production build reproducing `wwwroot`, worker smoke in headless Chrome; Android 292 unit tests with debug assembly and debug and release lint (no errors); secret scan PASS. No physical Android device, live model or production deployment was used.

## PR 19 review fixes

- Offline inspections restore the matching fixture and its mandatory checklist after restart. Updated site documentation supersedes older inspection copies. Pack refreshes invalidate obsolete open details and preserve newer event sequences on disk and in memory.
- Advisor actions require the accepted probe action and its registered prediction. Scored results carry their diagnostic source, and predictions become dated cached copies when connectivity drops.
- Hands-free stops when the screen leaves the started state or recognition switches providers. Aborted sessions reject late callbacks and pending permission results. The provider disclosure remains visible before reactivation.
- Scanner teardown waits for admitted frames and closes both recognition clients. Unknown barcodes allow OCR, all decoded codes are considered, and the displayed fallback favors an identifier token. Selected camera parts remain tied to their anchor or an unambiguous visible detection.
- Declining a view requires an outstanding server-recorded request. Photo capture, a replacement assessment and terminal stages clear it, including when events are replayed. Site-pack rows are captured under each inspection’s command lock.
- Failed saves show a retryable storage error, temporary files are unique across stores, and lost command responses describe an unconfirmed outcome. Test fixtures wait for coroutine teardown before releasing their dispatchers.

Regression checks cover these paths through the production models, view models, stores, API and web rendering helpers. Controlled reversions reproduced failures for the offline fixture and knowledge, advisor registration, speech boundaries, scanner selection and lifetime, stale detail handling, the decline guard and concurrent pack reads.

The current gate runs 308 Android tests, 339 .NET tests and 78 web tests, plus debug and release assembly, debug and release lint, web typecheck and production build, and secret scanning. Physical camera, microphone and OCR behavior still require device validation.
