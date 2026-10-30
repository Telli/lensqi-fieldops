# LensQi FieldOps — Private LensIQ Reuse Plan

**Private upstream:** `Telli/LensIQ-Monorepo` at `593f467372c7e88acb7a217242fb70b1b0c49525` (October 16, 2026). Verified private on October 27. Do not publish old history.

**Competition status:** Indie-track permission for copying earlier code **not confirmed**. The event guide explicitly allows startups to build on existing code, but says demo entries must identify only new hackathon work. This kit does not assert permission for indie reuse.

| Component | Reuse choice | Dependency/technical concern | Priority |
|---|---|---|---|
| `SceneFrameSource.kt` | Reimplement clean frame interface | Depends on `LiveCameraService`/Hilt; tiny utility | P0 |
| `LiveCameraService.kt` | Reuse pattern, new full-res evidence logic | 640px/50% preview unsuitable for tiny port lettering | P0 |
| `TtsService.kt` | Conditional source reuse if approved, else fresh Android TTS | Offline voice lifecycle/utterance tracking | P1 |
| `PermissionHandler.kt` | Rewrite | Forces unneeded mic/location for some flows; tourism strings | P0 |
| `BackendJson.kt` | Recreate basic setup | Small and generic | P0 |
| `LensIqApiService.kt` | No direct reuse | Entwined billing, heritage, auth endpoints | P0 new client |
| `AudioCaptureService.kt` and `AdaptiveVad.kt` | Conditional targeted reuse | Complex concurrency and AEC; add only when core works | P1 |
| `LocationProvider.kt`, `HeadingProvider.kt` | Defer | GPS not needed for asset ID or lab fixture | Future |
| `ArCapabilityChecker.kt` and `ar/` | Reimplement clean optional P1 ARCore spatial anchor + depth bridge | Optional 3D world-locked anchors & metric distance on ARCore phones, with CameraX 2D fallback | P1 |
| `SceneCandidateService.kt` | Exclude | Geography-based heritage candidate ranking | Not applicable |
| `GroundingService.kt`, `ConfidenceScoringEngine.kt` | Exclude | Soft scoring unsuitable as verification authority | Not applicable |
| `ExplainService.kt`, `TimeTravelService.kt` | Exclude | Heritage story workflow and Gemini semantics | Not applicable |
| C# `ApiError.cs`, `PromptValidation.cs` | Reimplement or conditional micro reuse | Small API infrastructure, dependency variants | Optional |
| C# `Features/Ai/` and original database/migrations | Exclude | Use fresh Meta+FieldProof modules and SQLite | Not applicable |

## Rule for reuse

1. Obtain organizer's indie-category interpretation in writing and verify redistribution/IP ownership.
2. If copying is allowed, list exact path, commit SHA, copied lines and upstream license in `PRIOR_ART.md` **before** first public commit.
3. Extract only reviewed files; inspect transitive dependencies, secrets, notices and commit history. Never graft prior Git history.
4. Mark copied code as pre-existing in judged demo and project documentation.
5. If permission unclear, write new code with independent tests implementing the behavior from specifications.

**Current status:** No private code has been incorporated in this specification kit.
