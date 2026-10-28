# LensQi FieldOps — beyond the demo: near-production design and plan

**Written:** October 28, 2026. **Scope:** what it takes to turn the hackathon system into something a first paying site can run, including the mobile app. Nothing here is built; the hackathon repository is the starting point and its invariants carry over unchanged.

## 1. What stays, what changes

| Layer | Hackathon (today) | Near production |
|---|---|---|
| Core state machine, policy evaluator, receipts | Keep as is; they are already pure and tested | Add policy versioning per tenant and fixture; add `VisualChangeAnalyzed` and documentation gates (VK-T10, VK-T26) |
| Persistence | In-memory sessions + JSONL per inspection | PostgreSQL with an append-only `inspection_events` table (sequence unique per inspection, server time, fixture digest), projections rebuilt on read or cached; artifacts in S3-compatible object storage with content addressing |
| Fixtures and packs | JSON files loaded at startup | Same files, versioned in a tenant-scoped registry table with digests; a signed manifest per release; admin UI for publishing a new revision, never editing in place |
| Model calls | Direct from the API | A `ModelGateway` service: budgets per tenant and inspection, retries only on idempotent calls, circuit breaker, prompt and schema versions pinned per policy version, full request/response digests retained |
| SAM parsing | Python sidecar process per call | Same parser as a small internal HTTP service (one container, horizontal), or a native C# decoder once the grammar reference is implemented and oracle-tested |
| Diagnostics | Simulated / replay / one guarded HTTP probe | Probe plugins behind `IDiagnosticProbe` (HTTP health, ICMP, SNMP read-only, RouterOS REST GET), each with a declared `ObservationKind`, closed value set and allowlist; probes run from a site-local runner, not the cloud API |
| Auth | None | OIDC (Entra ID or Auth0/Clerk) with roles: technician, reviewer, site admin, tenant admin; device-bound tokens for the mobile app; per-tenant data isolation enforced in queries |
| UI | Browser page served by the API | Browser reviewer console (kept, rebuilt as a small SPA only if needed) + Android app (section 5) |
| Receipts | SHA-256 canonical manifest | Same manifest, signed with a tenant key in a KMS/HSM; signature covers the manifest digest; verifier checks signature then digests |

## 2. Service architecture

```
Android app ──┐                               ┌─ PostgreSQL (events, projections, registry, audit)
Browser console┼─ HTTPS ─> FieldOps API (ASP.NET Core) ─┼─ Object storage (photos, crops, receipt bundles)
Reviewer tools ┘               │                       └─ KMS (receipt signing keys)
                               ├─> ModelGateway ──> Meta Model API (Muse Spark, SAM 3.1)
                               ├─> SAM parser service (meta-sam-parser)
                               └─> Site runner (probes, LAN only, outbound-only connection to the API)
```

- **Site runner.** A small service installed on a laptop or Pi at the site. It holds the probe allowlist and credentials, connects *out* to the API with a device identity, receives `RunDiagnostic(inspectionId, diagnosticId)` jobs, executes locally and posts `DiagnosticResult`. The cloud never reaches into customer networks; SSRF is impossible by construction.
- **ModelGateway.** The only component with the Model API key. Enforces per-tenant monthly budgets and per-inspection call caps, logs digests not payloads, and supports model routing: Muse Spark for assessment and grounding, Muse Wonder as an escalation for hard comparisons, Muse Spark Open (Baseten or Modal) as a second opinion or for an offline-capable tier later.
- **Event store.** `inspection_events(tenant_id, inspection_id, sequence, kind, occurred_at, fixture_digest, payload jsonb, payload_sha256)` with a unique index on `(inspection_id, sequence)`. Commands execute under a per-inspection advisory lock. Idempotency table `(tenant_id, request_id) → outcome` with a TTL.
- **Artifacts.** Content-addressed keys `tenant/inspection/sha256.ext`, immutable, server-side encryption, lifecycle rules per retention policy. Thumbnails and ROI crops derived on write with transforms recorded.

## 3. Security and trust

- Transport: TLS everywhere; mTLS or device certificates for site runners; HSTS on the console.
- AuthZ: every query scoped by `tenant_id` from the token; reviewers can read, technicians can command their own inspections, admins publish fixtures and packs.
- Secrets: KMS-managed; no model key on devices; site runner credentials rotated and revocable.
- Model boundary: unchanged from the hackathon (validators reject, never repair); add prompt-injection canaries to the evaluation set and run them in CI against recorded responses.
- Probe boundary: unchanged; the allowlist moves to the site runner and is signed by the tenant admin.
- Receipts: signed manifest; public verifier (CLI and web page) that needs only the tenant's public key; a published key-rotation history.
- Privacy: photos may contain people or client assets; add an on-device blur-before-upload option, a retention policy per tenant (default 90 days, legal hold), full-export and delete endpoints per inspection, and an audit log of every read of an artifact.
- Supply chain: SBOM, pinned NuGet and pip versions, Dependabot, signed container images.

## 4. Operations

- Deploy: one container for the API, one for the SAM parser service, managed Postgres and object storage; IaC (Terraform or Bicep); blue/green with migrations applied before rollout; the site runner ships as a signed installer.
- Observability: OpenTelemetry traces across API → gateway → model; metrics for model latency, cost per inspection, probe outcomes, false-completion count (must stay zero), inconclusive rate, time-to-first-guidance; structured logs with digests only.
- SLOs: API p95 < 500 ms for non-model endpoints; model endpoints p95 < 20 s; receipt issuance < 2 s; availability 99.5% for the API, with the mobile app fully usable offline for capture.
- CI: `dotnet test`, fixture validators, secret scan, the evaluation runner against recorded model responses (no paid calls in CI) plus a nightly live run on a small scenario subset with budget caps; Android unit tests and an emulator smoke.
- Cost model: per inspection ≈ 2–4 Muse calls (≈ 3k input tokens each) + 1–3 SAM images; budgets enforced in the gateway; tenants see their spend.

## 5. Mobile app (Android first, iOS after pilot)

### Why native
Field technicians need the camera at full resolution, reliable capture without connectivity, and a device that survives a ladder. The browser surface stays for reviewers and demos.

### Architecture
Kotlin, Jetpack Compose, CameraX, Hilt, Room, WorkManager, Retrofit with kotlinx.serialization. Clean layering: `data/` (API client, Room queue, artifact cache), `domain/` (inspection view model mapping, no business rules), `ui/` (four screens plus hands-free mode). The app contains **no verification logic**; it is a capture and display client for the API.

### Capture and evidence
- Full-resolution JPEG retained in app-private storage with EXIF; SHA-256 computed on device at capture and sent with the upload so the server can confirm the bytes it stored match what the device hashed.
- Quality signals on device (blur, exposure) shown before upload as hints, never as gates.
- Each capture gets a UUID idempotency key; uploads resume and never duplicate.
- Optional on-device face/badge blur before upload (ML Kit face detection) controlled by tenant policy.

### Offline-first
- Room queue of commands and captures in order; WorkManager drains it when connectivity returns, with exponential backoff and the server's 409 codes surfaced verbatim.
- Projections cached per inspection; while offline the app shows "awaiting upload" and never a provisional outcome.
- Knowledge packs cached per fixture with digest and a `Cached` label; the server's resolution wins when online.

### Screens
1. Sign-in (OIDC) and site selection.
2. Fixture and asset: QR scan or pick; fixture digest shown.
3. Capture: shutter, source badge, thumbnails, quality hints.
4. Investigate: assessment badges, SAM overlay drawn from RLE on the original, ROI crop, requested-view instruction, grounded proposal with citations.
5. Verify: work order, claim, checklist, acknowledge, verify; banner from the decision; receipt viewer and share.
6. Hands-free mode (P1): large controls, spoken instructions via Android TTS, push-to-talk via Muse Voice Transcribe for the repair claim text.
7. Wearables (P2): `IFrameSource` with the Meta Wearables Device Access Toolkit; Mock Device Kit labelled as simulated; no in-lens overlay claims.

### Testing and release
- Unit tests for queue ordering, idempotency, state mapping; contract tests from recorded API JSON; emulator smoke for capture → upload → investigate; a device lab run on two real phones before each release.
- Play Console: data-safety form reflecting photo handling and retention, camera-only permission by default, internal test track for the pilot. Reuse the Play submission checklist knowledge from the earlier product; write it fresh.

### iOS
After the Android pilot: SwiftUI + AVFoundation with the same API client contract, same offline queue semantics (Core Data or SwiftData), same labelling rules. Share nothing but the API and the contract tests.

## 6. Roadmap after the hackathon

| Phase | Weeks | Outcome |
|---|---|---|
| 0. Harden and pilot-ready API | 1–3 | Postgres event store, object storage, OIDC, tenant scoping, signed receipts, ModelGateway with budgets, site runner with HTTP + ICMP probes, CI with recorded-response evaluation |
| 1. Android pilot client | 3–6 | Screens 1–5 offline-first, internal test track, device-lab smoke, `docs/evidence/android/` |
| 2. First site pilot | 6–10 | One managed-switch site: SNMP read-only probe (port-level claims become possible), RouterOS REST GET adapter (VK-T15–T18), two vendor packs curated with the pack schema, reviewer console, weekly false-completion and inconclusive reporting |
| 3. Knowledge depth | 10–16 | Permission-aware manual ingestion (VK-T19), hybrid retrieval with exact model filtering before semantic search (VK-T20), firmware advisories (VK-T22), change evidence in production (VK-T26) |
| 4. Scale and integrate | 16–24 | CMMS work-order sync behind approvals (VK-T23), multi-tenant admin, iOS client, wearables P2, SOC 2 readiness |

## 7. Risks

- **Real-photo recognition.** All current numbers are on renders. Phase 0 must include a 50-photo set from the pilot site, labelled by a technician, before any accuracy claim.
- **Model drift and cost.** Pin model ids per policy version; re-run the evaluation on every model change; budgets prevent surprise bills.
- **Port-level truth on unmanaged switches is impossible.** Say so in the product; sell managed-switch telemetry as the upgrade path.
- **Receipt trust.** Signing proves issuer, not reality. Keep the disclaimers; add capture attestation (device-signed photo hashes) in Phase 2.
- **Vendor documentation rights.** Link-only until a licensing path exists per vendor; the pack schema already enforces it.
