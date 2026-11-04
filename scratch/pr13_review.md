# Review of PR #13: Android field improvements: offline commands, label scan, verify preflight, attempt history, clock skew, glasses resume, measured notes (T6.2, GT-T19, VK-T07)

## Summary & Architectural Evaluation

This PR implements an extensive suite of field improvements stacked on top of PR #12 (targeting `fix/android-review-bugs`):

- **Offline Command Queueing (`CommandQueue.kt`, `QueueDb.kt`):**
  - Database migration from v3 to v4 adds `payload` and `measured` columns.
  - Checklist reviews and work orders are safely queued when the server is unreachable or responds with 5xx/408/429.
  - Maintains strict FIFO execution via `enqueueBehindPending`, ensuring dependencies execute in capture sequence.
  - **Preservation of System Invariants:** Repair claims, technician acknowledgements, and verification commands are strictly kept online (never queued), ensuring that timestamps and measurements reflect actual equipment state rather than stale client queues.
  - **Pre-check Invariant:** Acknowledgement and verification refuse to proceed while un-synced checklist answers or work orders remain pending in the local queue, preventing decisions based on incomplete reviews.
- **Asset Label Scanning (`ScanAssetScreen.kt`, `AssetScan.kt`):**
  - Uses bundled ML Kit barcode analysis (QR, Data Matrix, Code 128) without external Play Services dependencies.
  - Filters scanned values strictly against known site fixtures via `assetFromScan`.
- **Preflight Verification & Attempt History (`VerifyFlow.kt`, `InspectionScreen.kt`):**
  - Provides a comprehensive "Before you verify" preflight comparison against accepted repair evidence, checklist confirmations, and diagnostic expectations.
  - Chronological attempt history provides full visibility into past verification decisions.
- **Clock-Skew Warning (`ClockSkew.kt`):**
  - Computes delta between the device clock and the server `Date` header on each response, warning the technician if skew exceeds the 120 s tolerance window prior to capture.
- **Measured Notes Provenance (`CaptureAids.kt`, `InspectionEndpoints.cs`):**
  - ARCore spatial measurements are snapshotted at shutter trigger time and transmitted in the `measured` form field, validated server-side by `MeasuredParts.Parse`.
- **Knowledge Transparency & Permission Settings:**
  - Fact source sheets (VK-T07) render publisher, revision, digest, and verification provenance.
  - Permanent permission denials route directly to app settings with explicit explanations.

---

## Code Quality & Implementation Observations

1. **Resolution of Prior Review Feedback:**
   - Addressed Devin Review's initial observations regarding offline claim timestamps and confirmation ordering in commits `9ae5848`, `951f35b`, and `5b03070`.
   - Resolved the experimental API warning (`UnsafeOptInUsageError` on CameraX `ImageProxy.image`) in `ScanAssetScreen.kt`.
2. **Room Database Migration (v3 -> v4):**
   - Migration in `QueueDb.kt` and `QueueMigrationTest.kt` verifies that existing queued items preserve their state while adding the new nullable columns.
3. **Branch Retargeting Note:**
   - PR #13 is stacked on PR #12 (`base: fix/android-review-bugs`). Once PR #12 is merged into `main`, PR #13 should be retargeted to `main` before merging.

---

## Verification

- **Android Unit Tests:** All 236 tests pass (`:app:testDebugUnitTest`).
- **Android Builds:** `:app:assembleDebug` and `:app:assembleRelease` pass cleanly.
- **.NET Tests:** 310 tests pass (including `InspectionApiTests`).
- **Secret Scan:** `scripts/secret_scan.sh` clean.

**Recommendation:** Approve. PR #13 provides a major step forward for offline reliability and field usability while respecting all core architecture invariants.
