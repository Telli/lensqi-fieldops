# Teaching recorder verification — 2026-11-01

These are synthetic Android emulator checks, not physical demonstrations or evidence of robot-training performance. Device: Medium Phone API 36.1, rear virtual-scene camera. No backend was running during the recorder smoke.

## Executed checks

- Debug APK built and installed. Home → Teaching recordings → enter goal/equipment/alias → confirm recording permission → record → action start/end → typed note → stop → human outcome Uncertain → review → Android document picker → export.
- The exported MP4 is H.264, 1280 × 720, 959 frames, 36.384911 seconds, with no audio stream. `ffprobe -count_frames` agrees with `frames.csv`; `ffmpeg -v error -i video.mp4 -fps_mode passthrough -enc_time_base demux -f null -` decodes the complete video without errors. Seven large presentation gaps were reported and shown to the reviewer; this emulator capture is not a quality benchmark.
- The ZIP validator accepted the genuine export. Four modified copies were rejected: altered video bytes, missing frames.csv, inconsistent training eligibility, and a duplicated/reordered frame index even after updating its hash.
- Eight new JVM tests exercise consent, paired action boundaries, monotonic capture clocks, post-reboot review, review invalidation after corrections, structured label corrections, recovery of unfinished sessions, and export/delete isolation. Temporarily removing training consent from the eligibility predicate made the consent test fail; the mutation was reverted.
- Backgrounding an active recording finalized it as Interrupted. After force-stop and relaunch, both the complete and interrupted sessions remained in app-private storage. The interrupted session retained training permission = false and actual video gap metrics; raw metadata is saved as `interrupted-session.json` and `interrupted-timeline.jsonl`. Its actual ZIP also passed validation with reviewed = false and trainingEligible = false (`interrupted-export-validation.json`).
- Final Android unit test result: 190 passed, 0 failed, 0 skipped. Debug assembly passed, including a final rerun after the attached-plan provenance and interrupted-recording copy changes. Backend test results: 152 Core + 18 Meta + 133 API = 303 passed, 0 failed, 0 skipped.

The sample `dataset.json`, `session.json`, and `timeline.jsonl` preserve the actual synthetic export metadata. The video and ZIP are temporary local smoke artifacts, not committed datasets. `export-validation.json` and `video-probe.json` contain the validator/decoder results. In the validator output, “annotations” counts all timeline events, including capture clock samples.

## Original gate failures (resolved below) and remaining device checks

- Release assembly failed in Android lint: `NonNullableMutableLiveDataDetector` throws `IncompatibleClassChangeError` about `KaCallableMemberCall` while analyzing `FieldOpsApplication.kt`. An independent release build of the unchanged base commit `e99748a1d526f80172cba62bf71df8a61a123646` produced the same detector/class/file error. This change does not disable lint or alter the toolchain. See the [last 40 release log lines](release-lint-tail.txt).
- The quality-pass runner reports ERROR for the .NET gate because its TRX parser rejects duplicate identities for xUnit theory rows in `KnowledgePackValidatorTests.Pack_rejections_match_the_python_validator`. The test process itself exits 0 with 303 passing cases. Formal failure equivalence remains UNKNOWN; the overall gate is not green.
- Physical-device capture, push-to-talk recognition, camera/storage failure injection, the 10-minute/256-MB cap, and attached online task snapshots have not been exercised on a device in this change. No hand-pose, force, depth, robot-control, or learning-quality claim is made.

## Original gate output (verbatim)

```text
== summary
  FAIL  15.468s  ['./gradlew', ':app:testDebugUnitTest', '--rerun', ':app:assembleDebug', ':app:assembleRelease', '--console=plain']
  ERROR  8.004s  ['dotnet', 'test', '--nologo', '--logger', 'trx', '--results-directory', '{report_dir}']
  PASS  0.14s  ['bash', 'scripts/secret_scan.sh']
== vs baseline: UNKNOWN
  android: failure equivalence unknown
  dotnet: failure equivalence unknown
```

## Release tooling repair — 2026-11-01

Updated AGP from 8.7.3 to 8.10.1 using the existing Gradle 8.11.1 wrapper. Release lint and release assembly now pass with checks enabled. No lint suppression or failure baseline was added.

The repository-local `scripts/test_dotnet_report.py` runs dotnet test and converts its original TRX reports to JUnit, using each result's parameterized testName instead of the shared method-level testId. It retains failure details and skipped results, checks summary counts, rejects duplicate identities and stale report directories, and propagates the test process exit code. Original TRX files remain available. A regression check covers distinct theory cases, failures, skips, bad counters, duplicate names and unknown outcomes. `.quality-pass/gates.json` records the reproducible commands.

All current gates pass: 190 Android tests, 303 backend tests, debug/release APK assembly, report-adapter regression check and secret scan. The original failed baseline is preserved; formal baseline comparison remains UNKNOWN because the report command/configuration changed. Physical-device and online-context acceptance remain outstanding as above.

```text
== summary
  PASS  101.033s  ['./gradlew', ':app:testDebugUnitTest', '--rerun', ':app:assembleDebug', ':app:assembleRelease', '--console=plain']
  PASS  8.578s  ['python3', 'scripts/test_dotnet_report.py', '{report_dir}']
  PASS  0.127s  ['bash', 'scripts/secret_scan.sh']
  PASS  0.072s  ['python3', 'scripts/test_report_adapter.py']
== vs baseline: UNKNOWN
  gate configuration changed or baseline format unsupported; failure equivalence unknown
```
