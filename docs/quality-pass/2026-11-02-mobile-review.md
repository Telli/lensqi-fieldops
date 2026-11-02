# Android mobile completeness and quality review — 2 November 2026

**Original review verdict (before the fixes below): the Android app was not feature complete or release-ready.** Most core workflows are implemented, and the debug/unit baseline is green. The remaining problems include evidence identity errors, unsafe model-output handling, incomplete recovery/navigation, unmet photo/source requirements, and a failing release/lint gate. A percentage would conceal these differences, so this report separates implemented features from correctness and acceptance.

## Scope and validation

Branch: `main`, reviewed commit `fdb0db569d6048d0904f47aa268999f82570268d`; no diff base. Standard feature scope: `apps/android/**`, expanded into task/inspection API endpoints, core state machines/safety validation, model coaching, tests and product requirements. Six independent reviewer contexts covered behavior, security/data integrity, feature honesty, performance, dead code/navigation and architecture. Findings below were rechecked by the coordinator.

This is a native Android review, not an iOS or entire-web audit. No application source was changed, no fixes were committed, and the existing untracked `scratch/` was left alone. Only the report, local review records and a temporary reproduction harness were added. No Docker, PostgreSQL server, real provider calls, paid API calls or external messages were needed; repository integration tests use isolated temporary filesystem storage and fake providers.

No fresh emulator UI, physical-phone, microphone, ARCore or glasses run was performed. An emulator was available but was not used for this audit. Historical screenshots/runs provide context, not proof that the current revision passes hardware acceptance. No new exploit delivery path, frame-time profile, battery measurement or exhaustive safety assurance is claimed.

| Check | Current result |
|---|---|
| Android unit/contract tests and debug build | **PASS: 182 passed, 0 failed, 0 skipped**; test task forced to rerun |
| Backend tests (`dotnet test --nologo --verbosity minimal`) | **PASS: 303** — Core 152, Meta 18, API 133 |
| Release + debug lint (`./gradlew :app:assembleRelease :app:lintDebug --console=plain`) | **FAIL** — AndroidX lint crashes in release-vital/debug analysis |
| Targeted real-API/fake-model reproduction | **Confirmed two defects:** unsafe restricted check reason accepted; retake checked old pixels |
| Existing baseline comparison | **UNKNOWN**: prior gate configuration/format differs; current checks above are independently green, not a claim of matched-baseline equivalence |
| Physical device acceptance | **Not rerun**; recorded gaps remain |

Existing baseline was preserved. Verbatim current gate summary:

```text
  PASS  9.157s  ['./gradlew', ':app:testDebugUnitTest', '--rerun', ':app:assembleDebug', '--console=plain']
  tests: total=182, passed=182, failed=0, skipped=0
== vs baseline: UNKNOWN
  gate configuration changed or baseline format unsupported; failure equivalence unknown
```

Fresh release failure: `NonNullableMutableLiveDataDetector` throws `IncompatibleClassChangeError` because `KaCallableMemberCall` is a class where an interface was expected. `BUILD FAILED in 8s`; 79 actionable tasks, 11 executed and 68 up-to-date. Packaging produced an unsigned APK before validation failed; that artifact is not a successful release gate.

Local evidence: [Android gate results](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/logs/20261102-102120-6b45399c/results.json), [API reproduction harness](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/mobile-repro/Program.cs), [reproduction output](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/mobile-repro/output.log), [finding state](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/state.json).

## Feature completeness

“Built” means a code path exists; it does not mean production-ready or newly verified on hardware. The PRD and feature-status documents contain older snapshots, so missing-code claims below were checked against current code.

| Area | Assessment | What remains |
|---|---|---|
| DIY/Technician goal, details, manual photos, plan and safety acknowledgement | Built | Fix mode-switch state; improve exact-equipment clarification and source grounding |
| Guided steps, live camera coaching, SAM, ARCore/2D fallback | Built; acceptance partial | Current hardware regression run, real step-part outline, tracking loss/recovery and shutter handoff |
| Voice questions, commands, paced TTS, pause/mute | Built; acceptance partial | Hear output on a phone and demonstrate a real “next step” command after latest speech changes |
| Step photo checks | Built; P0 contract partial | Safety guard, capture-specific idempotency and full-resolution record preservation |
| Offline task-photo queue | Built | Reliable request identity; whole-task cold-start/offline read cache is absent and is a proposed enhancement |
| Inspection capture, diagnostics, fake-fix rejection, verification, receipt viewing | Built, with historical emulator/phone evidence | Fix cache/capture correlation and offline review resume; native receipt bundle share/save missing |
| Technician task → linked FieldProof inspection | Built/tested with fakes | Human-operated end-to-end linked journey with genuine models remains unvalidated |
| Task history and asset browsing | Partial | All-task access and existing asset-screen entry points |
| Vendor knowledge display | Partial P0 (VK-T07) | Source details, revision/digest, cached/synthetic/applicability information |
| Push-to-talk repair claim | Missing P1 | Claim forms are typed; task voice questions do not implement repair-claim dictation |
| Vendor-pack citations inside task plans | Missing P1 | Site context contains equipment information, not per-step cited vendor facts |
| Glasses task/inspection paths | Built P1; hardware unvalidated | Mock Device Kit evidence only; real-glasses acceptance |
| Production access and lifecycle | Outside hackathon completeness | Authentication, organization/record ownership, role checks, retention/deletion and operational deployment |
| iOS | No implementation found | Not required by the Android-focused product scope |

Outstanding acceptance is supported by [S23 run 2](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/docs/evidence/tasks/s23-phone-run-2/run.json) (silent phone, no demonstrated command/shutter check), [Android acceptance notes](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/docs/evidence/android/ux-polish/README.md) and [mock-glasses evidence](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/docs/evidence/live-glasses/README.md). Older documentation incorrectly calls some now-built work missing: offline step-check queue, task glasses integration and speech pacing all exist.

## Verified findings, ranked

14 demonstrated findings below include 13 behavior/security/completeness/build issues and one optional dead-code cleanup. “Code path” means deterministic source inspection, not an observed device run. Two API behaviors and the build failure were also reproduced at runtime.

| ID | Priority | Finding | Primary evidence |
|---|---|---|---|
| QP-a44aed57dc5c | High | Restricted photo checks bypass the safety guard | [TaskEndpoints.cs](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/src/LensQi.FieldOps.Api/Features/Tasks/TaskEndpoints.cs:183) |
| QP-fa3264f16e22 | High | Offline inspection snapshots collide across inspections | [InspectionViewModel.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/ui/inspection/InspectionViewModel.kt:368) |
| QP-024e3ecf9dc5 | High | Changing servers redirects pending photos to the new server | [QueueDb.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/data/queue/QueueDb.kt:27) |
| QP-4f0e419229ef | High | Retaking a step-check photo can assess the previous image | [TaskViewModels.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/ui/task/TaskViewModels.kt:166) |
| QP-05140e98a2a2 | High | Inspection review can mix two different captures | [InspectionViewModel.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/ui/inspection/InspectionViewModel.kt:259) |
| QP-0a1a91dea4a2 | High | Release and lint validation fail in the current toolchain | [libs.versions.toml](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/gradle/libs.versions.toml:2) |
| QP-f77d1b51d61a | Medium | Offline capture review does not resume after upload | [InspectionViewModel.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/ui/inspection/InspectionViewModel.kt:263) |
| QP-599ba70a979b | Medium | Older active and stopped tasks are unreachable | [TaskScreens.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/ui/task/TaskScreens.kt:195) |
| QP-04012496d74c | Medium | Existing asset screens have no navigation entry | [FieldOpsNavHost.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/ui/FieldOpsNavHost.kt:160) |
| QP-758c5ec1ef86 | Medium | Switching Technician to DIY submits a hidden site selection | [TaskScreens.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/ui/task/TaskScreens.kt:274) |
| QP-c129048cfe95 | Medium | Step-check records lose original image resolution | [TaskViewModels.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/ui/task/TaskViewModels.kt:164) |
| QP-c9f05bf0b68c | Medium | Knowledge sources omit revision and provenance details | [InspectionScreen.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/ui/inspection/InspectionScreen.kt:644) |
| QP-2596be81c153 | Medium | Receipt origin check permits unrelated lookalike hosts | [ReceiptScreen.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/ui/receipt/ReceiptScreen.kt:107) |
| QP-7ed4a22d5dc8 | Low | Superseded SegmentationOverlay has no callers | [SegmentationOverlay.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/ui/inspection/SegmentationOverlay.kt:23) |

### QP-a44aed57dc5c — Restricted photo checks bypass the safety guard

**High · security · demonstrated.** The check endpoint validates reason length/links, then records it unchanged; Android speaks it at TaskViewModels.kt:179. Unlike ask/live guidance, this path never calls TaskSafety.Guard.

**Reproduce:** Start an accepted professional-only electrical plan; return a fake check result with reason "Remove the panel cover.". The local API harness uses the real endpoint and repository FakeVisionChat.

**Observed versus expected:** Runtime: HTTP 200 and recorded_reason=Remove the panel cover. Expected the server to replace/reject hands-on directions before saving or speaking them.

**Recommended fix:** Apply the same restriction policy to all model text channels, including check reasons; add a negative endpoint regression test.

Repeatable check: `dotnet run --project .quality-pass/mobile-repro/Repro.csproj --verbosity quiet`; `cat .quality-pass/mobile-repro/output.log`.

### QP-fa3264f16e22 — Offline inspection snapshots collide across inspections

**High · bugs · demonstrated.** viewKey is the literal "inspection-" and knowledgeKey is "knowledge-". All instances save/load the same filenames. The fallback does not verify the loaded inspectionId.

**Reproduce:** View inspection A online, then B; restart offline and open A from the cached list. Follow save at line 155 and fallback at 181–182; knowledge at 358–362.

**Observed versus expected:** Deterministic code path: A reads B’s persisted state/knowledge while commands still target A. Expected a snapshot bound to the selected inspection and server.

**Recommended fix:** Key by server identity plus inspection ID and reject mismatched cached records.

Repeatable check: `nl -ba apps/android/app/src/main/java/com/lensqi/fieldops/ui/inspection/InspectionViewModel.kt`.

### QP-024e3ecf9dc5 — Changing servers redirects pending photos to the new server

**High · security · demonstrated.** PendingCommand has no API origin. The interceptor resolves every queued request against currentBase at send time; Settings allows changing it without partitioning pending work or caches.

**Reproduce:** Queue an inspection/step photo for unavailable A, change Settings to B, then reconnect. Trace CommandQueue.send through NetworkModule.baseUrlInterceptor.

**Observed versus expected:** Deterministic code path: A’s bytes are sent to B. A B-side 404 permanently rejects the command; task observers dismiss rejected checks and delete their queued photo. Expected sending only to the capture’s intended server.

**Recommended fix:** Persist destination identity on each command, scope local data by server, and preserve/recover rejected work explicitly.

Repeatable check: `nl -ba apps/android/app/src/main/java/com/lensqi/fieldops/data/queue/QueueDb.kt`.

### QP-4f0e419229ef — Retaking a step-check photo can assess the previous image

**High · bugs · demonstrated.** The pending action is keyed only by step ID. Retryable provider failures retain that key. A new shutter capture reuses it; TaskEndpoints intentionally reloads the first saved photo under that key.

**Reproduce:** Submit image A, simulate provider failure after its photo is saved, then submit different image B using the key retained by the Android view model.

**Observed versus expected:** Runtime API harness: first HTTP 409, second HTTP 200; model_saw_first_image=True, model_saw_second_image=False. Expected B to have a new request identity; only retries of A should reuse A’s key.

**Recommended fix:** Bind each capture and its bytes to an immutable request key; expose retry of that capture separately from taking a new photo.

Repeatable check: `dotnet run --project .quality-pass/mobile-repro/Repro.csproj --verbosity quiet`; `nl -ba apps/android/app/src/main/java/com/lensqi/fieldops/ui/task/TaskViewModels.kt | sed -n '128,179p'`.

### QP-05140e98a2a2 — Inspection review can mix two different captures

**High · bugs · demonstrated.** The enqueue result is ignored. Review selects the first server photo not in the old view. Investigation uses the latest server photo, while segmentation uses the selected first-new photo ID.

**Reproduce:** Leave A queued offline, capture B before A is in the current view, then reconnect and drain A followed by B.

**Observed versus expected:** Deterministic code path: B’s frozen review can identify A as its photo, investigate B, and segment A. Expected every result in a review to refer to the same capture.

**Recommended fix:** Return/persist upload-result evidence IDs per command and bind investigation, comparison and segmentation to that exact photo.

Repeatable check: `nl -ba apps/android/app/src/main/java/com/lensqi/fieldops/ui/inspection/InspectionViewModel.kt`.

### QP-0a1a91dea4a2 — Release and lint validation fail in the current toolchain

**High · bugs · demonstrated.** assembleRelease and lintDebug fail in lintVitalAnalyzeRelease/lintAnalyzeDebug/lintAnalyzeDebugUnitTest. NonNullableMutableLiveDataDetector throws IncompatibleClassChangeError: KaCallableMemberCall is a class where an interface was expected.

**Reproduce:** Run ./gradlew :app:assembleRelease :app:lintDebug --console=plain in apps/android.

**Observed versus expected:** Executed: BUILD FAILED in 8s, 79 actionable tasks. Debug tests/build pass. An unsigned APK is produced before the failed validation, which does not establish release readiness.

**Recommended fix:** Resolve the AGP/Kotlin/AndroidX lint compatibility issue and require release/lint gates; do not silently suppress all lint checks.

Repeatable check: `cd apps/android && ./gradlew :app:assembleRelease :app:lintDebug --console=plain`.

### QP-f77d1b51d61a — Offline capture review does not resume after upload

**Medium · bugs · demonstrated.** After 45 seconds without a photo, the review sets awaitingUpload and returns. The worker only uploads/caches; observers do not resume investigate/segment or clear that review flag.

**Reproduce:** Capture offline, leave the review open longer than 45 seconds, then reconnect and let WorkManager upload.

**Observed versus expected:** Deterministic code path: the photo uploads but review stays awaiting upload and no initial analysis runs. The UI promises LensQi will look once uploaded.

**Recommended fix:** Persist/observe the exact review job and resume its next stage on upload completion, or show an explicit Analyze action; cover process recreation.

Repeatable check: `nl -ba apps/android/app/src/main/java/com/lensqi/fieldops/ui/inspection/InspectionViewModel.kt`.

### QP-599ba70a979b — Older active and stopped tasks are unreachable

**Medium · bugs · demonstrated.** Home filters out Abandoned and takes six tasks. These are the only task-row entries, and the navigation graph has no full history/search screen.

**Reproduce:** Leave a job unfinished, then create six newer Just ask sessions. Restart and try to reopen the old job. Stop another task and try to find its retained record.

**Observed versus expected:** Deterministic UI path: records remain on the server but have no entry in the app. Expected all active and retained work to remain discoverable.

**Recommended fix:** Add All tasks with active/completed/stopped filters and search; keep active jobs prominent.

Repeatable check: `nl -ba apps/android/app/src/main/java/com/lensqi/fieldops/ui/task/TaskScreens.kt`.

### QP-04012496d74c — Existing asset screens have no navigation entry

**Medium · bugs · demonstrated.** The assets and asset/{assetId} routes are registered, but no tab/button enters assets. All asset-detail entry comes from that stranded list. Home also hides its direct start-inspection entry when a focused inspection exists.

**Reproduce:** Open Technician and attempt to browse assets/expected state. With an active photographed inspection, try to directly start a second inspection.

**Observed versus expected:** Navigation inspection: no Assets entry exists; Start inspection is conditional on having no focused work. A new technician task is a possible indirect path, not an asset-browser entry.

**Recommended fix:** Restore persistent Assets and Start inspection actions on Technician; retain the existing screens.

Repeatable check: `rg -n 'navigate\(|Tab\(|AssetsScreen|AssetDetailScreen|asset/|"assets"' apps/android/app/src/main/java/com/lensqi/fieldops/ui`.

### QP-758c5ec1ef86 — Switching Technician to DIY submits a hidden site selection

**Medium · bugs · demonstrated.** Mode change only changes mode; the remembered asset persists. The form hides the picker in DIY but always submits asset fields. TaskSession rejects site links for non-Technician mode.

**Reproduce:** New task → Technician → select equipment → DIY → Plan it.

**Observed versus expected:** Deterministic client/server path: invalid_input for a now-hidden selection. Expected a valid DIY task without an active site attachment.

**Recommended fix:** Submit site fields only for Technician mode and deliberately reset or deactivate them on mode changes.

Repeatable check: `nl -ba apps/android/app/src/main/java/com/lensqi/fieldops/ui/task/TaskScreens.kt`.

### QP-c129048cfe95 — Step-check records lose original image resolution

**Medium · slop · demonstrated.** reduce(photo, 2400) and JPEG quality 88 feed both upload and durable queue; original shutter file is only in cacheDir. GT-R09.1 calls for full-resolution capture stored by the API.

**Reproduce:** Capture a step-check image longer than 2400 pixels and follow immediate upload and offline enqueue paths.

**Observed versus expected:** Deterministic transform: only a reduced derivative reaches durable queue/server storage. Expected the original task record to be retained; model inputs can be separate derivatives.

**Recommended fix:** Persist/upload original bytes with digest; derive a smaller upright model input independently.

Repeatable check: `nl -ba apps/android/app/src/main/java/com/lensqi/fieldops/ui/task/TaskViewModels.kt`.

### QP-c9f05bf0b68c — Knowledge sources omit revision and provenance details

**Medium · slop · demonstrated.** The panel renders statement, category, document title and locator; sourceUrl, packRevision, packSha256 and warnings are not surfaced, nor explicit synthetic status. The cached label lacks the pack digest.

**Reproduce:** Open a knowledge-backed inspection and attempt to inspect the source/revision, applicability warnings and cached pack identity.

**Observed versus expected:** Static UI/data-contract trace: most metadata is fetched but has no control/rendering path. VK-T07 remains partial.

**Recommended fix:** Add compact source details with source link, section/revision, pack digest, applicability warnings and cached/synthetic labels.

Repeatable check: `nl -ba apps/android/app/src/main/java/com/lensqi/fieldops/ui/inspection/InspectionScreen.kt`.

### QP-2596be81c153 — Receipt origin check permits unrelated lookalike hosts

**Medium · security · demonstrated.** The JavaScript-enabled WebView permits navigation using string startsWith(origin), not parsed-origin equality.

**Reproduce:** Compare https://trusted.example against https://trusted.example.evil/ or https://trusted.example@evil.example/.

**Observed versus expected:** Executed predicate check: both prefix comparisons are true while both parsed hosts differ. An actual malicious redirect/link delivery path was not established; no credential theft is claimed.

**Recommended fix:** Compare parsed scheme, host and effective port; reject userinfo/unexpected schemes.

Repeatable check: `nl -ba apps/android/app/src/main/java/com/lensqi/fieldops/ui/receipt/ReceiptScreen.kt | sed -n '98,112p'`; `Python urllib.parse hostname comparison against the current startsWith predicate`.

### QP-7ed4a22d5dc8 — Superseded SegmentationOverlay has no callers

**Low · dead-code · demonstrated.** The 51-line former overlay has no path/symbol callers; current screens use ArOverlay. It is not framework-registered.

**Reproduce:** Search filename/path, qualified name and symbol across source excluding generated build output; trace current review/inspection overlay calls.

**Observed versus expected:** Only its own declaration references the symbol. No runtime penalty measured; this is optional maintenance cleanup.

**Recommended fix:** Optionally remove after rechecking both reference searches immediately before deletion; prioritize workflow defects first.

Repeatable check: `rg -n SegmentationOverlay apps/android --glob '!**/build/**' --glob '!**/.gradle/**'`.

## Suspected risks — outside the confirmed fix plan

- **QP-aa7126a1baee: Queue mutex is per instance, allowing competing drainers.** @Singleton decorates StepCheckQueue rather than concrete CommandQueue. Generated Hilt commandQueue() returns new CommandQueue; each has a private Mutex. Multiple instances are confirmed in generated DI. A user-visible duplicate-model-work, ordering or data-loss outcome was not reproduced. Next validation: Run simultaneous foreground and WorkManager drains; inspect generated provider and measure duplicate sends/file lifetime. See [CommandQueue.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/data/queue/CommandQueue.kt:48).
- **QP-3101167c9806: Capture persistence runs blocking work on the UI coroutine.** viewModelScope.launch calls enqueuePhoto without dispatcher change; before the first suspension it reads/copies the full file and hashes it. Step-check persistence and snapshot saves have similar synchronous disk work. Dispatch/call path confirmed; no frame-time, ANR, latency or battery measurement was made. User-visible performance impact remains suspected. Next validation: Record a system trace or StrictMode disk violations while capturing a large real-phone JPEG. See [CommandQueue.kt](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/android/app/src/main/java/com/lensqi/fieldops/data/queue/CommandQueue.kt:69).

## Rejected and already-resolved claims

No new reviewer defect was promoted without verification; zero current reviewer claims were rejected. Two saved findings from the preceding review were rechecked and rejected as no longer present at this commit:

- **QP-02b5f9197993: Step announcement during microphone listening is discarded without retry.** Current Speaker holds forced messages while listening and releases them afterward. VoiceSpeechTest covers forced messages arriving/interrupted during microphone use. Prior finding no longer reproduces in the current code; forced messages are deferred. Device audio remains untested in this audit.
- **QP-b14599360572: A later forced message interrupts remaining step safety narration.** Current SpeechTurns.begin returns Play.After for a later forced line, and Speaker uses QUEUE_ADD. Explicit replace still deliberately replaces obsolete steps. Prior finding no longer reproduces; current test suite passes. Audible device acceptance is still outstanding.

Other checked non-findings: AI checks do not auto-complete steps; linked inspection status supplies verification labels; simulated outcomes remain labelled; release cleartext is restricted to loopback/emulator exceptions; application backup is disabled. Missing production authentication is a documented scope gap, not a newly introduced mobile regression.

## Additions that would provide real value

Priority reflects the user jobs and failures found in this repository, not measured customer-demand research. Repair the evidence/safety defects before adding new workflow breadth.

| Priority | Addition | Practical value | Acceptance target / relative size |
|---|---|---|---|
| 1 | Durable offline job package and sync center | Work continues in basements/poor coverage; a restart does not lose the accepted plan, current step or saved evidence | Reopen a downloaded job after force-stop in airplane mode; see pending/refused work and recover it. Medium–large |
| 2 | All tasks, active-work resume, Assets and persistent Start inspection | Prevents jobs/equipment from disappearing as usage grows | Reopen the 20th task, a stopped task and asset history; start another inspection with active work present. Small–medium |
| 3 | Exact-equipment clarification and source-backed steps | Reduces wrong-model, tool, part and specification assumptions | Ask for missing model/variant information; show source page/crop, revision and uncertainty at the relevant step. Medium–large |
| 4 | Share/save a useful work package | Makes the result usable by a supervisor/customer outside the app | Native Android share/save of readable summary and integrity-verifiable evidence bundle, with skipped steps and simulated/live labels. Medium |
| 5 | Carry context from “Just ask” into “Make a plan” | Avoids re-entering equipment details and losing the camera/conversation context | Carry selected photos, equipment identity and relevant answers with explicit user review. Currently only the latest question becomes the goal. Medium |
| 6 | Asset QR/barcode entry plus repeat-work history/templates | Speeds correct asset selection and reduces repeated troubleshooting | Scan an asset ID, see previous faults/fixes, and start a suitable reviewed template. Medium |

For the offline package, Android’s official architecture guidance recommends durable local reads and synchronization through a local data source; that supports extending the current photo queue into a recoverable job view. Pending actions must still be distinguished from server-confirmed verification. [Android offline-first guidance](https://developer.android.com/topic/architecture/data-layer/offline-first).

For deployment beyond the controlled single-user demo, prioritize identity/ownership enforcement, supervisor permissions and retention/export/deletion before exposing the existing API to multiple customers. Multilingual voice and richer assignment/escalation can follow validated field use. No evidence from this review justifies prioritizing additional decorative AR effects over recovery and trustworthy records.

## Performance and architecture

Measured existing APKs: debug **105,307,405 bytes (100.43 MiB)**; unsigned release **82,817,598 bytes (78.98 MiB)**. Release includes four native ABIs (21,745,952 compressed native bytes); its four largest DEX entries total 58,452,652 bytes. Release minification is disabled. These are packaging facts, not a proven regression or a measured startup/download problem. Evaluate R8/per-device delivery after release gates work.

`TaskScreens.kt` has 743 lines and 16 commits in the last six months; `CameraScreen.kt` has 744 lines and 17 commits. When those screens are next changed, small file/presentation extractions are reasonable. Keep state ownership, callbacks, effect keys and camera lifecycle unchanged during mechanical work. No measured merge-conflict or maintenance-time penalty is claimed.

**Do not touch in this pass:** server-owned task/inspection transitions; the existing verification/provenance model; LiveCameraSession ownership/cancellation; TargetMemory’s identity-only persistence; Compose/Hilt/Retrofit/Room/WorkManager as a stack. No framework migration or backend rewrite is warranted.

**Dead-code limit:** only SegmentationOverlay is a confirmed optional candidate. Asset screens are unreachable features to reconnect, not files to delete. Framework-registered services, Hilt providers, workers and live geometry helpers have callers/registrations. Recheck path and symbol references immediately before any deletion.

## Execution plan and release criteria

1. Fix safety checks, snapshot/server isolation, capture-specific idempotency and exact photo correlation. Add regressions that fail against the old behavior, including A/B inspections, A/B servers and A/B captures. Preserve the invariant that offline or inferred results never imply verified completion.
2. Fix offline review recovery, history/asset entry points and hidden mode state. Test process recreation and network return with multiple queued captures.
3. Close full-resolution and knowledge-source requirements; add share/export if mobile record handoff is required for the intended pilot.
4. Resolve lint/toolchain compatibility and pass debug, release and lint checks. The current debug-only CI command cannot establish release readiness.
5. Run a physical-phone acceptance session: audible step cautions, voice command, full-resolution shutter step check, ARCore/no-depth fallback, tracking loss/recovery, offline restart/reconnect, and a technician task through failed then successful FieldProof with provenance-labelled receipt. Test real glasses only if that capability will be claimed.
6. Profile the suspected UI-thread persistence hotspot and reproduce competing queue drains before claiming their user-visible impact. Optional overlay cleanup and mechanical screen splits come last.

Application changes are intentionally outside this review. The requested review is complete; no implementation approval has been recorded. The review-only skill says, “Produce the ranked, evidence-backed report and stop. No code changes.” [Review skill](/Users/telli/.codex/plugins/cache/quality-pass-marketplace/quality-pass/1.1.0/skills/quality-pass-review/SKILL.md).

Approve to continue with `quality-pass-execute`.


## Shipped — approved follow-up, 2 November 2026

The user authorized fixing the confirmed bugs and completeness gaps, adding regression tests, and passing release/lint. Implemented on `codex/mobile-review-fixes`; the sections above preserve the original findings, rather than claiming those faults remain in the fixed code.

- All 13 confirmed behavior, integrity, completeness and build findings addressed: restricted check text guarded before recording/speech; cache keys include server and inspection; immutable queue destinations; capture-specific request IDs and original bytes; exact upload evidence IDs; resumed review across offline waits and screen recreation; searchable task history including stopped work and read-only closed-session records; reachable Assets/Start inspection; DIY submissions exclude hidden site links; complete knowledge provenance; exact receipt origins.
- Queue schema 3 preserves old photos. Legacy rows have no trustworthy destination, so migration pauses them with `server_unbound` until the person explicitly chooses the original server and retries. Refused photos remain until explicit retry/removal. The queue is a singleton and concurrent drains share one mutex.
- Missing functional P1 paths added: repair-claim dictation into an editable draft, native receipt ZIP save/share, and vendor-fact citations in technician plans. Dictation uses the phone speech service with explicit text review; it does not claim a new Muse transcription endpoint. Export contains the issued JSON/HTML and original artifacts and refuses failed integrity checks. Citation IDs must belong to the resolved applicable facts; the server supplies citation details and persists them in the plan.
- AGP updated from 8.7.3 to 8.10.1 to resolve lint compatibility with the existing Kotlin version. No broad lint suppression. CI now requires debug/release assembly plus debug/release lint.

Validation: **191 Android tests**, **307 backend tests** (152 Core, 18 Meta, 137 API), debug and release assembly, debug and release lint, and the secret scan pass. Release output is the unsigned APK; signing/distribution was not part of this request. New backend tests use fake providers and isolated temporary filesystem stores; no Docker or database was needed.

Regression evidence: restricted-check and capture-correlation tests failed before their fixes. Controlled mutations restored the faulty cache key, missing queue destination, shared photo key, reduced image bytes, wrong evidence ID, 45-second timeout, truncated history, hidden site submission and prefix-based origin check; the corresponding Android tests failed. Backend mutations removing citations, falsifying synthetic metadata and excluding original artifacts from export also failed. Fixed code passes. Local logs are under `.quality-pass/mobile-review/`; gate results under `.quality-pass/logs/`.

An emulator startup/navigation smoke confirms Settings reconnect, Assets, persistent Start inspection, all-task history and the oldest stopped record. Screenshots and exact limits: [emulator evidence](../evidence/android/mobile-review-fixes/README.md). No new real-model, microphone, camera, ARCore or glasses acceptance is implied.

## Deliberately not done

Optional unused-overlay removal (QP-7ed4a22d5dc8), unmeasured performance claims, larger roadmap additions (whole-job offline packs, QR entry, context-preserving conversion), production identity/ownership/retention, iOS, signing and distribution remain outside this fix set. Hardware acceptance remains open. The main Android milestone stays partial because actual voice/camera/AR/glasses acceptance and the originally proposed Muse Voice Transcribe endpoint are not demonstrated.

## Policy going forward

Use the committed `.quality-pass/gates.json` and Android CI workflow. Evidence retries retain original bytes, destination and request identity; a retake gets a new identity. Cache ownership includes server and record. Restricted model text is guarded before recording. Documentation stays guidance; diagnostic provenance and server decisions stay authoritative. Never erase legacy queued evidence to migrate it, suppress lint crashes to claim readiness, or call a hardware workflow verified without running it.
