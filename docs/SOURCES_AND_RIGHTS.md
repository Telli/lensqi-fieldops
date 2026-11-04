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

- From the November 4, 2026 license-change commit, project-owned software is licensed under the GNU Affero General
  Public License, version 3 only (`AGPL-3.0-only`); see the root `LICENSE` and README license notice. Copyright
  (c) 2026 Telli Koroma. Earlier versions published under MIT retain that license.
- The project license does not replace third-party licenses, vendor documentation rights, model/API service terms
  or asset-specific notices. Preserve those notices when redistributing; see `docs/PRIOR_ART.md`.
- Vendor documentation may be protected by copyright or access terms; the public project must not mirror PDFs, scrape private support portals or ship proprietary diagrams without rights.
- A link to a manufacturer page does not establish that a specific installed unit has the same firmware or hardware revision.
- Before using production customer documentation, obtain tenant-level authorization, license validation, retention/deletion controls and a source-access review.
- Citations store source URL and locator but must not imply official vendor endorsement.
- Runtime-generated sources from arbitrary web content should not be treated as trusted instructions or executable tools.
- When a source changes, release a new knowledge pack revision and keep old evidence-pack digests addressable.

## Prior art / hackathon rules

The original `Telli/LensIQ-Monorepo` remains private. This project follows PRD §4A and `docs/COMPONENT_REUSE.md`: **no pre-event private source is copied into the public repository** unless the indie-track reuse rule is clarified in writing by the organizers and redistribution rights are recorded first. Until then, small utilities are reimplemented from their behavioural specification with fresh tests. The private code is reference and product knowledge only.

Status on October 28, 2026: zero private source copied. Fresh equivalents of the useful infrastructure (Postgres-free test harness, fake chat client, `ApiError`, pack validator, HTTP client wiring) were written in this repository during the event. `docs/LENSIQ_REUSE_ASSESSMENT.md` remains as the record of what would be eligible if the rule is ever clarified, and of what must never be reused. `docs/PRIOR_ART.md` holds the copied-source register, which is empty.

No pre-existing source was copied into this document kit.
