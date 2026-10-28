# Three-Minute Demo, Evaluation and Proof Scope

## What is new

All showcased UI/API/state/verification functionality should be newly written during the competition build period. Do not demonstrate older LensIQ heritage features as competition contributions.

## Three-minute story

| Time | Scene | Evidence shown |
|---|---|---|
| 0:00–0:20 | Problem: visually plausible connector ≠ restored service | Controlled lab fixture shown |
| 0:20–0:50 | Wide view: Muse describes uncertainty, SAM segments switch/cable | Real call traces/ROI overlay |
| 0:50–1:20 | Agent requests a closeup instead of guessing | Follow-up view, before/after model state |
| 1:20–1:45 | Diagnostic reveals endpoint fault | Explicit LIVE or SIMULATED badge |
| 1:45–2:15 | Technician attempts false fix; visual looks good; telemetry still fails | `VerificationFailed` reasons |
| 2:15–2:45 | Correct fix, fresh photo, successful probe and human acknowledgement | `VerifiedCompleteLive` if real; otherwise `Simulated` |
| 2:45–3:00 | Hash receipt and source proof | Source code, schema/state gate, receipt validation |

## Core benchmark (20–30 original/rights-cleared cases)

- ~5 unambiguous correct fixture images
- ~5 occluded/blurred/ambiguous views
- ~5 visually plausible connected / diagnostic fail cases
- ~5 fresh repaired / probe passes cases
- ~5 unknown asset or stale/wrong-fixture/test input cases

Report actual metrics: invalid live completions (target 0 across negative tests), correct/unknown decision counts, evidence requests, successful resolution after one additional view, response latency and Model API cost. Do not invent accuracy statistics or present replay as live.

## Deterministic negative tests

- Fake repair with failed probe cannot complete.
- Stale photo/probe predating latest repair claim cannot complete.
- Wrong fixture revision or unknown asset cannot complete.
- Simulated diagnostic cannot produce `VerifiedCompleteLive`.
- Missing SAM output does not create a fabricated mask.
- Failed/timeout Meta model call leaves inspection in safe/inconclusive state.
- Passing health check does not certify the physical port on an unmanaged switch.
- Hash alteration detected on exported receipt.

## Demo assets and honesty

Mark photos as `real_fixture`, `reproduced_lab`, `synthetic`, `recorded`, or `mock`. State whether any glasses footage used Meta Mock Device Kit. Save sanitized provider trace IDs but not API keys or customer frames. Build/run instructions must work from the public repository alone.
