# Sources, licensing and provenance policy

## Verified public technical references (reviewed October 27, 2026)

1. **MikroTik hEX S RB760iGS hardware specifications** — <https://mikrotik.com/product/hex_s>.
   - Used only to identify product code, five Gigabit Ethernet ports and SFP interface.
   - Rights mode: `LinkOnly`. No copied vendor photo, product brochure or manual is bundled.
2. **MikroTik RouterOS REST API manual** — <https://manual.mikrotik.com/docs/developer-guides/rest-api/>.
   - Used to scope the optional read-only `GET /rest/interface/{name}` and the read/REST API monitoring user concept.
   - IMPORTANT: REST supports configuration modifications and arbitrary commands. This specification deliberately exposes no generic REST access or arbitrary POST.
   - Rights mode: `LinkOnly`. Paraphrased fact cards contain their own concise wording.
3. **Meta Model API / Muse Spark** — <https://dev.meta.ai/docs/overview>.
   - Meta model for bounded visual reasoning and typed output. Do not claim ground truth from a model response.
4. **Meta SAM 3.1 media segmentation** — <https://dev.meta.ai/docs/media-segmentation>.
   - Produces masks/boxes to locate relevant regions. Not a hardware diagnostic or physical 3D coordinate estimator.

## Synthetic assets

`lab-switch-01.synthetic.json` is an original fictional pack. Its port mapping, endpoint name and test outcomes describe only the constructed demo. Do not label synthetic statements as MikroTik/Cisco/other manufacturer facts.

## License and operational rules

- Vendor documentation may be protected by copyright or access terms; the public project must not mirror PDFs, scrape private support portals or ship proprietary diagrams without rights.
- A link to a manufacturer page does not establish that a specific installed unit has the same firmware or hardware revision.
- Before using production customer documentation, obtain tenant-level authorization, license validation, retention/deletion controls and a source-access review.
- Citations store source URL and locator but must not imply official vendor endorsement.
- Runtime-generated sources from arbitrary web content should not be treated as trusted instructions or executable tools.
- When a source changes, release a new knowledge pack revision and keep old evidence-pack digests addressable.

## Prior art / hackathon rules

The original `Telli/LensIQ-Monorepo` remains private and has no license file, so it is all-rights-reserved by its owner, who also owns the public FieldOps repository and may relicense what is imported. The participant guide permits building on an existing codebase when the new work is clearly identified. The rule for this project is therefore:

- **Permitted:** infrastructure scaffolding only, imported in **one** first commit titled `Import pre-existing infrastructure scaffolding from LensIQ (not hackathon work)`, before any feature commit. The allow list is in `docs/LENSIQ_REUSE_ASSESSMENT.md` §2 (test fixtures, fake chat client, `ApiError`/`Result`, image upload validation, audit-row pattern, HTTP resilience wiring, rate limiter, CI workflows, anonymous JWT auth). Each imported file carries a two-line header naming the source repository, the import date and "infrastructure only; adapted". Imported files live under `tests/Fixtures/`, `Shared/`, `.github/` and `Infrastructure/` so the separation is visible in the tree.
- **Not permitted:** any domain code, and specifically the AR stack, Places, billing, admin pages, Gemini clients, the Gemini Live relay, the confidence-scoring engine and client-side model-JSON parsing (deny list in the same document §4).
- **Disclosure:** `docs/PRIOR_ART.md` records the import commit SHA, the file list and the relicensing decision. `docs/HACKATHON_CONTRIBUTIONS.md` lists only event-period work and never references imported files. The demo video shows no imported code.
- Everything judged (knowledge resolver, applicability, Muse proposal validation, predictions, change evidence, probes, state machine, receipts, Android inspection UI) is newly authored during the event, in commits dated after the import.

No pre-existing source was copied into this document kit.
