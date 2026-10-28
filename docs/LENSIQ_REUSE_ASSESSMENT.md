# What to reuse from the LensIQ monorepo

**Assessed:** October 27, 2026, from a read-only shallow clone of `Telli/LensIQ-Monorepo` at commit `593f467` (October 16, 2026, the last commit before the hackathon). Nothing was copied into this kit.

## 1. The short answer

LensIQ is an AR landmark guide for tourists: ARCore Geospatial anchors, Gemini narration, Google Places, Play Billing. It shares **no domain** with FieldOps. There is no evidence state machine, no diagnostic probe, no inspection receipt (every `Receipt` hit is a purchase receipt), no segmentation, no EXIF handling, no Meta model client.

That is good news under the hackathon rules. Everything judges will look at in FieldOps is new by construction. What LensIQ offers is roughly **600 lines of infrastructure scaffolding and two CI workflows** that would otherwise cost a solo developer about a day to rewrite. Take them, label them, and never show them in the video.

## 2. Tier 1: copy nearly verbatim, label as pre-existing

| LensIQ file | Lines | Why it fits | Change before use |
|---|---|---|---|
| `tests/.../Fixtures/PostgresFixture.cs` | 75 | Per-run uniquely named database on the local PostgreSQL 18 install, created and dropped by the fixture; no Testcontainers. Matches this machine's testing policy exactly and the spec's clean-room requirement. | Rename env var to `FIELDOPS_TEST_POSTGRES`; default port is 5433 in LensIQ, this Mac's install is 5432. |
| `tests/.../Fixtures/FakeChatClient.cs` | 69 | `IChatClient` fake that records every prompt and every image it was sent and returns a canned JSON payload. This is precisely the harness VK-T06 and VK-T12 need to assert that prompts contain only applicable fact IDs and that a fabricated `usedFactIds` is rejected. | Replace the canned Pike Place payload with a `VendorGroundedProposal` JSON. |
| `tests/.../Fixtures/LensIqApiFactory.cs` | 382 | `WebApplicationFactory` pattern: `UseEnvironment("Testing")`, `RemoveAll<IChatClient>()` then register the fake, swap the DbContext to the fixture database, reset tables per test. | Keep the skeleton (about 80 lines); drop Places, billing, Live and admin fakes. |
| `Shared/ApiError.cs`, `Shared/Result.cs`, `Shared/PromptValidation.cs` | 87 | The spec already says "follow existing `ApiError` conventions". Stable string codes plus a `Result<T>` with implicit conversions is the right shape for `model_mismatch`, `prediction_late` and friends. | Replace the billing-specific factories with the spec's error codes. |
| `Infrastructure/Storage/ImageUploadValidation.cs` | 22 | Size and content-type gate for uploaded images. Evidence photos need it on day one. | Add magic-byte sniffing; the current version trusts the declared content type. |
| `Infrastructure/Admin/AdminAuditService.cs` + `Domain/Admin/AdminAuditLog.cs` | 86 | Append-only audit row with actor, action, target, summary and a `jsonb` metadata column. This is the storage shape for `KnowledgePackResolved`, `KnowledgeProposalRejected`, `PredictionRegistered` and the other events in design §10. | Rename to an inspection event table; add `inspectionId` and a sequence number. |
| `Program.cs` outbound HTTP wiring (lines ~120–220) | ~100 | Typed `HttpClient` per provider with `AddStandardResilienceHandler`, explicit per-provider timeouts, and `DisableForUnsafeHttpMethods()` on billed POSTs. The same discipline the spec demands for Muse, SAM and the fail-closed probes. | Add `AllowAutoRedirect = false` and a response-size cap for the probe client (VK-R06.4). |
| `.github/workflows/backend.yml`, `android.yml` | 65 | Backend job already runs a `postgres:18` service and passes its connection string to the fixture via env; Android job runs unit tests, lint and assembles a debug APK. | Rename paths and the env var. |
| `Infrastructure/RateLimiting/TierRateLimiter.cs` | 68 | Per-identity token bucket. Caps model-call cost per inspection, which VK-R10.5 wants. | Collapse the tier table to one or two tiers. |

## 3. Tier 2: reuse the pattern, write the code new

- **`GeminiChatClient.cs` → a Muse Spark `IChatClient`.** The adapter already maps `TextContent` and `DataContent` (images) into a REST request and returns `UsageDetails`. A Muse client is a sibling with a different request body. Do not port the Gemini code; the hackathon requires Meta models, and the spec needs typed structured output, which this adapter does not do. Keep the shape, write the body.
- **`TripPreparation.kt` offline manifest.** `PreparedManifest` lists every downloaded file with its byte count and SHA-256, writes atomically, and `verifyNow()` recomputes before marking a pack ready. That is the exact mechanism for two items in the review document: the server-side `packs.lock.json` digest manifest, and the Android offline pack cache that must prove its bytes match the server's `packSha256` before showing `Cached / Synthetic`.
- **`AnonymousJwtIssuer` + `AuthInterceptor` + `AuthTokenStore`.** Self-issued HMAC JWT, Bearer header on every request, one retry on 401, token in `EncryptedSharedPreferences`. Enough auth for a demo and a judge's clone; drop the Clerk and BYOK branches.
- **Vertical-slice endpoint layout** (`Features/<Name>/<Name>Endpoint.cs` with a static `Map`) and **Hilt module layout** (`NetworkModule`, `DataModule`, `ServiceModule`). Conventions, not code.
- **`ViewModelStateTransitionTest.kt`** (3,800 lines) as a model for how to table-test a state machine exhaustively. The FieldProof stage machine deserves the same treatment; the test content is all new.

## 4. Tier 3: do not reuse

- **The entire AR stack** (`ar/`, SceneView, ARCore Geospatial, `ArSceneHostView`). Irrelevant, heavy, and carries a manifest-merger gotcha. FieldOps needs CameraX only; `SnapshotManager` is the one AR-adjacent file worth a glance for its MediaStore write path.
- **Places cache, Overpass and Wikipedia sources, billing, entitlements, recovery codes, admin Razor pages, Gemini Live relay.** Product-specific, and every line is a lineage question you do not want to answer in Stage One.
- **`ConfidenceScoringEngine.kt`.** A weighted average of vision, geo and curated scores into one "confidence" float. This is the pattern the FieldOps spec explicitly rejects: provenance labels (`Documented` / `Site configured` / `Inferred` / `Measured`) instead of a blended number. Porting it would undercut the project's thesis.
- **`lenientJson` fence-stripping in `GeminiRemoteDataSources.kt`.** Client-side parsing of model prose. FieldOps validates typed structured output on the server and never lets the client interpret model text.
- **Anything with "Gemini" in the name.** Different provider; Stage One requires Meta models.

## 5. Rules and provenance handling

- **Rights.** The monorepo has no `LICENSE` file, so it is all-rights-reserved by its owner. You own it, so you may relicense the imported scaffolding under the public repository's license. Record that decision in `PRIOR_ART.md`.
- **One import commit.** Land every Tier 1 file in a single first commit titled along the lines of "Import pre-existing infrastructure scaffolding from LensIQ (not hackathon work)", before any feature commit. Judges then have one SHA to exclude, and `git log --since` shows only hackathon work after it.
- **Header on every imported file.** Two lines: origin repository and date, plus "infrastructure only; adapted". Keep imported files in `tests/Fixtures/`, `Shared/`, `.github/` so the separation is visible in the tree, not only in a document.
- **Never in the video.** Test fixtures and CI are not demo material anyway.
- **Policy (reverted October 28, 2026).** The PRD (§4A) and `docs/COMPONENT_REUSE.md` forbid copying any pre-event private source until the indie-track rule is clarified in writing. That stance is adopted; the one-labelled-import-commit allowance proposed here on October 27 is withdrawn. Nothing was imported. This document stays as the record of what would be eligible if the rule is clarified, and of what must never be reused. The useful utilities were reimplemented fresh in this repository.

## 6. Net effect (historical)

Importing Tier 1 would have saved roughly one working day and removes the two classes of early-hackathon failure that waste the most time: a flaky test database and a hand-rolled fake model client. It adds no judging risk if the import is a single labeled commit. Everything that scores, from the knowledge resolver to predictions, change evidence and receipts, is written during the event.
