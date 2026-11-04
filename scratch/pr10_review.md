# Review of PR #10: Add Android teaching recorder and restore release checks

## Summary & Architectural Evaluation

This PR implements the initial Android teaching recorder slice (**TEACH-T01**), enabling technicians to record structured task demonstrations as local observation datasets (silent MP4 video, timed step and action annotations, notes, append-only corrections, human-assessed outcomes, and ZIP export). It also addresses two release-gate blockers: upgrading AGP to 8.10.1 (resolving the release lint crash) and adding a local TRX-to-JUnit adapter (`scripts/test_dotnet_report.py`) that preserves parameterized xUnit theory cases.

### Strengths & Invariant Compliance
- **Privacy & Two-Tier Consent:** Recording permission and training consent are strictly separated. Video recording explicitly disables audio capture (`AudioConfig.AUDIO_DISABLED`), and dictation uses push-to-talk saving text only without retaining raw audio.
- **Context Sanitization:** Task snapshots and server event logs fetched for `context.json` have personal profile identifiers (`person`) scrubbed before persistence.
- **Strict Eligibility Derivation:** Demonstrations are only marked `trainingEligible = true` if training consent was explicitly granted, the session status is `Complete`, and a human reviewer marked it reviewed after all annotations and corrections.
- **Append-Only Corrections:** Corrections do not mutate original events; they append a `Correction` event referencing the original sequence (`refersTo`), and automatically invalidate any prior review so re-review is mandatory.
- **Storage & System Hygiene:** Sessions are isolated with validated UUID directory names (preventing directory traversal), written via atomic temporary files with `fd.sync()`, and strictly bounded by both disk space and storage budget checks (300 MB free required, < 768 MB total teaching storage).
- **Tooling & Release Gates:** Upgrading AGP to 8.10.1 fixes the release lint crash without suppressing checks, allowing `:app:assembleRelease` and `lintVitalRelease` to pass cleanly.

---

## Findings & Recommendations

### 1. [Important] Distinguish normal CameraX limit caps from capture failures
**File:** [`apps/android/app/src/main/java/com/lensqi/fieldops/teaching/TeachingViewModel.kt`](apps/android/app/src/main/java/com/lensqi/fieldops/teaching/TeachingViewModel.kt#L128-L134)

**Observation:**
In `TeachingViewModel.start`, capture duration and file size are bounded:
```kotlin
FileOutputOptions.Builder(store.video(record.id))
    .setFileSizeLimit(256L * 1024 * 1024)
    .setDurationLimitMillis(10 * 60 * 1000L)
    .build()
```
When a recording naturally reaches the 10-minute cap or 256 MB size cap, CameraX finalizes the video with `VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED` (`9`) or `ERROR_FILE_SIZE_LIMIT_REACHED` (`2`).

In `onVideoEvent`:
```kotlin
val failure = if (event.hasError()) "CameraX finalization code ${event.error}" else null
...
val status = when {
    video == null -> RecordingState.Failed
    failure != null || stopReason != "User stopped recording." -> RecordingState.Interrupted
    else -> RecordingState.Complete
}
```
Because `event.hasError()` is true for these codes, a normal completion at the configured duration or size limit is classified as `RecordingState.Interrupted`. Because `TeachingExport.trainingEligible` requires `record.state == RecordingState.Complete`, a fully executed demonstration that reached the 10-minute cap can never become eligible for training, even after review.

**Recommendation:**
Treat `VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED` and `VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED` as expected completions rather than interruptions:
```kotlin
val limitReached = event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                   event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
val failure = if (event.hasError() && !limitReached) "CameraX finalization code ${event.error}" else null
val effectiveStopReason = if (limitReached) "Recording limit reached." else (failure ?: stopReason)
```

---

### 2. [Defensive Coding] Avoid double-bang unwraps in clock offset calculation
**File:** [`apps/android/app/src/main/java/com/lensqi/fieldops/teaching/TeachingRecord.kt`](apps/android/app/src/main/java/com/lensqi/fieldops/teaching/TeachingRecord.kt#L102-L104)

**Observation:**
```kotlin
val clock = previous.lastOrNull { it.recordedDurationNs != null }
val offset = if (postCapture) null else clock?.let { it.recordedDurationNs!! + (nowNs - it.elapsedNs!!) }
```
`it.recordedDurationNs!!` and `it.elapsedNs!!` assume that any event with non-null `recordedDurationNs` also has non-null `elapsedNs`. While current event types uphold this, safe unwrapping avoids potential `NullPointerException` if event serialization or future event kinds decouple them:
```kotlin
val offset = if (postCapture) null else clock?.let { c ->
    val duration = c.recordedDurationNs ?: return@let null
    val elapsed = c.elapsedNs ?: return@let null
    duration + (nowNs - elapsed)
}
```

---

### 3. [Hardware Consideration] Strictly increasing PTS requirement
**File:** [`apps/android/app/src/main/java/com/lensqi/fieldops/teaching/TeachingVideo.kt`](apps/android/app/src/main/java/com/lensqi/fieldops/teaching/TeachingVideo.kt#L21)

**Observation:**
```kotlin
require(ordered.size > 1 && ordered.zipWithNext().all { (a, b) -> b > a }) { "The video has too few frames or duplicate timestamps." }
```
`b > a` enforces strictly monotonic timestamps. While this worked cleanly on the emulator, certain physical hardware encoders (especially under variable frame rate or heavy system load) have been observed to report zero-delta presentation timestamps (`b == a`) for adjacent sample packets. Keep this in mind when executing physical device testing on Samsung S23/S24 devices to verify whether any OEM hardware encoders trigger false rejections here.

---

### 4. [Tooling] TRX TestMethod null-safety in `test_dotnet_report.py`
**File:** [`scripts/test_dotnet_report.py`](scripts/test_dotnet_report.py#L17-L18)

**Observation:**
```python
definitions = {t.get('id'): t.find('t:TestMethod', NS).get('className')
               for t in root.findall('.//t:UnitTest', NS)}
```
If a test definition in a TRX file lacks a `t:TestMethod` element (e.g. custom test harnesses or non-standard test types), this will raise an `AttributeError: 'NoneType' object has no attribute 'get'`.
Providing a fallback (e.g., `(t.find('t:TestMethod', NS) or {}).get('className', 'UnknownClass')`) makes the conversion adapter even more resilient.

---

## Verification Run

| Test / Check | Result | Details |
|---|---|---|
| **Android Unit Tests** | **PASS** | 190 passed, 0 failed (including `TeachingStoreTest`) |
| **Android Assemble Debug** | **PASS** | `:app:assembleDebug` built successfully |
| **Android Assemble Release & Lint** | **PASS** | `:app:assembleRelease` & `lintVitalRelease` pass with AGP 8.10.1 |
| **.NET Backend Tests** | **PASS** | 303 passed (152 Core + 18 Meta + 133 Api) |
| **Report Adapter Test** | **PASS** | `scripts/test_report_adapter.py` passed |
| **Secret Scan** | **PASS** | `scripts/secret_scan.sh` clean |

The PR is in great shape, well-architected, and fully aligned with repository invariants and documentation discipline. Addressing point #1 regarding CameraX duration/size limit codes will ensure that long/capped demonstrations can be approved and exported as training-eligible without unexpected interruption flags.