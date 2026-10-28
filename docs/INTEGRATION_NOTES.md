# Implementation integration notes

These notes complement the three Kiro files. They should be read by Codex before modifying the FieldProof workflow.

## Source separation

| Source | Authoritative for | Not authoritative for |
|---|---|---|
| Vendor documentation | Model features, official interface behavior, version-scoped vendor facts | Site cabling, customer-specific topology, observed current state |
| Site expected twin | Approved asset connections, site procedures, diagnostic IDs | Whether equipment actually works right now |
| Muse / SAM | Hypotheses, segmentation geometry, suggested further evidence | Final diagnosis, permission to execute, verification result |
| Read-only probe | Specifically measured service/interface status at a timestamp | Unmeasured aspects of a repair or broad compliance |
| Human acknowledgement | Technician intent, visual checklist review and repair claim | Automatically guaranteed success |

## Sample inspection flow

1. Technician confirms `LAB-SW-01` in `LAB-RACK-01`.
2. Server loads `lab-switch-01.synthetic`, validates exact identity, and records its byte digest.
3. Retriever returns `LAB-PORT-MAP-01` (site fixture requirement, not vendor fact) and `LAB-HEALTH-CHECK-01`.
4. Muse sees applicable facts and photo; requests a close-up if the cable port is occluded, citing the relevant fact ID.
5. SAM provides a target mask/box where possible; absence of a mask is recorded as a no-match, not a detection.
6. Technician submits new photo and authorizes the staged physical action.
7. The fake fix appears visually plausible, but the approved `NODE-A-HEALTH` simulated/real lab probe still fails. Core returns `VerificationFailed`.
8. After a genuine correction, fresh photograph and qualifying diagnostic allow Core's **existing** scoped verification policy to advance, with a simulated result clearly identified as simulation.
9. JSON/HTML receipt records images, diagnostic source, knowledge pack and factual source references.

## Example bounded response from Muse (illustrative only)

```json
{
  "assetId": "LAB-SW-01",
  "usedFactIds": ["LAB-PORT-MAP-01"],
  "hypothesis": "The cable position cannot yet be confirmed.",
  "requestedVisualEvidence": "Capture a close-up showing the yellow cable and visible P1–P3 port labels.",
  "proposedDiagnosticId": null,
  "nextAction": "RequestView"
}
```

`nextAction` is one of the five `VendorGroundedNextAction` values defined in `design.md` §5; Core maps it onto the parent action allowlist and the model never emits parent action names.

If Muse requests `MK-REST-GET-INTERFACE` for synthetic `LAB-SW-01`, that fact ID is unavailable in the active pack and MUST be rejected.

## Integration with primary code

- `ExpectedTwin` + inspection events: add a nullable `vendorKnowledge` binding; avoid schema-breaking changes if stored fixtures already exist.
- Keep the existing `RunReadOnlyDiagnostic` tool but require the server to validate **both** fixture allowlist and enabled adapter registry.
- Keep existing verification gates; documentation only adds applicability/citation checks for **explicitly required facts**.
- The existing `EvidenceReceipt` should have a `knowledge` section, not a second certificate type.
- The Android page should show a short source list and a link, not ingest or render vendor PDFs in the P0 build.

## Privacy and device security

- Public repository ships no credentials, actual customer equipment addresses, serial numbers, network layouts or personal field-worker photos.
- P1 live RouterOS is only an isolated owned test device over trusted HTTPS and with a minimal read-only user; REST credentials remain server-side.
- Reject model-provided or user-submitted arbitrary URLs and hostnames for diagnostics. Enforce no redirects, allowlisted targets and bounded responses.

## Longer-term production evolution

1. Signed or attested vendor pack publication with pack version pinning (distinct from plain SHA-256 integrity).
2. Permissions, licensing, document provenance, and version-aware ingestion of manuals, diagrams, service bulletins and maintenance history.
3. Hybrid deterministic-filter + full-text/vector retrieval for large corpora, with exact model/firmware filtering **before** semantic search.
4. Vendor diagnostic plugins validated in isolated labs, each declaring measurable claims and never performing arbitrary actions.
5. Technician review, CMMS synchronization only with permissions, and outcome metrics across repeat visits.
