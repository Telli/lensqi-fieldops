# Review of PR #9 – Keep spoken steps whole, read stop conditions, hold speech while listening (GT‑T18)

**Overall impression**
The PR introduces a solid set of voice‑interaction improvements that address the major pain‑points identified in the earlier audit:

| ✅ Feature | Description |
|---|---|
| **Forced‑line speech** | `SpeechTurns` and the `force` flag guarantee that steps, answers, check results and errors are spoken in full and cannot be cut off by hints. |
| **Hint‑waiting logic** | `VoicePace.guidance` now checks `busy` (held, listening, or forced line) before playing a hint, preventing overlap. |
| **Full step narration** | `stepSpeech` now reads the title, instruction, every caution, and all “Stop if …” conditions at step start and on repeat. |
| **Quiet‑while‑listening** | `SpeechOutput` now has a `listening` flag; the speaker is silenced while the mic is active, eliminating the “LensQi hears itself” issue. |
| **Command‑feedback** | `mute`/`unmute`, `next`/`skip`/`back`/`repeat` now announce why no action was taken (e.g., “nothing to skip”). |
| **Test coverage** | New `VoiceSpeechTest.kt` adds unit tests for forced‑line behavior, hint‑waiting, target‑loss handling and mute/unmute feedback. |
| **Android UI updates** | Minor UI tweaks in `TaskScreens.kt` (mic button, error handling) to expose the new voice state. |

All unit tests (Android & .NET) pass locally, and the Android debug APK builds successfully.

---

## Detailed observations & recommendations

### 1. Permission handling & fallback for speech recognition
**File:** `apps/android/app/src/main/java/com/lensqi/fieldops/voice/Voice.kt` (lines 179‑185)
*Observation* – The UI never surfaces the permission‑error message when the user denies the microphone permission before tapping the mic button.
*Recommendation* – Expose the permission‑error via `voice.error` (already displayed in `TaskScreens.kt` at line 163) and consider a short‑lived snackbar for UX.

### 2. Regex‑based command parsing robustness
**File:** `Voice.kt` (lines 49‑52)
*Observation* – Strict `lookup[t]` requires an exact match; stray punctuation or extra words cause the command to be missed.
*Recommendation* – Tokenize the input and match against known command tokens, with a fallback that treats unknown input as a *question*.

### 3. `SpeechTurns` time‑cap calculation
**File:** `Voice.kt` (lines 77‑86)
*Observation* – `maxMsPerChar = 90 ms` can lead to caps > 5 s for long forced lines, potentially hanging the UI.
*Recommendation* – Add a hard upper bound (e.g., `maxCapMs = 5000`) and expose the cap via a constant.

### 4. UI – Mic button state handling
**File:** `TaskScreens.kt` (lines 105‑108)
*Observation* – The mic button shows a listening state even while a permission request is pending.
*Recommendation* – Guard the visual state with `voice.supported && voice.error == null` and disable the button during permission requests.

### 5. Business‑logic validation – “Stop if” conditions
**File:** Indirect via `stepSpeech`
*Observation* – Spoken “Stop if” strings are concatenated, but downstream safety checks must also enforce them.
*Recommendation* – Add a unit test confirming the AR safety module reads the same `stopIf` list, and document the contract that `TaskStep.stopIf` must be both spoken **and** enforced.

### 6. Test suite – coverage gaps
*Observation* – No integration test that runs the full UI flow (launch task, speak step, issue voice command).
*Recommendation* – Add a lightweight UI test (Robolectric or instrumentation) that starts `TaskHomeScreen`, triggers `voice.start()`, then `voice.stop()` and asserts `Speaker.busy` toggles appropriately.

---

## Action items for the author (optional)
| ✅ | Item |
|---|---|
| **Permission UX** | Surface permission‑denial error promptly; consider a snackbar. |
| **Command parsing** | Make parsing tolerant of extra words; add fallback logging. |
| **Cap limit** | Introduce a hard upper bound for `maxMsPerChar` caps. |
| **Mic button state** | Guard UI state while permission request is pending. |
| **Safety contract** | Verify that spoken “Stop if” conditions are also programmatically enforced. |
| **Integration test** | Add a minimal UI test covering the full voice‑interaction cycle. |

All existing automated tests pass, and the PR is **feature‑complete** from a functional standpoint. The suggestions above are *non‑blocking*—the code can be merged as‑is, but addressing them will improve robustness and user experience.

---

*I have saved this review in `scratch/pr9_review.md` for reference.*