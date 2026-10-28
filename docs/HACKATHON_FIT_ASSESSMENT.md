# Hackathon fit assessment — Meta Global AI Developer Hackathon

**Assessed:** October 27, 2026, against the participant guide (schedule, rules, anti-projects, three-stage judging). The guide's rubric table was not included in the copy reviewed; where this document refers to rubric criteria it assumes the usual Cerebral Valley set (technical implementation, innovation, impact, use of Meta models, demo quality) and says so.

## 1. Verdict

FieldOps fits **Push the Frontier** well and is defensible under **Build for Your World**. Its core thesis, "the model proposes, the physical world confirms, code decides", is exactly the verifiable-agent posture Meta is teaching in the October 28 "Agents 102: Best Practices for Verifiable Agents" session. That alignment is the project's strongest asset.

The project is **not** at risk of being a banned category, but it **is** at risk of being *perceived* as two of the listed anti-projects in a three-minute video: a basic RAG application (manual lookup) bolted to a simple image analyzer (photo of a port). Everything about the demo must be built to defeat that perception in the first sixty seconds.

## 2. Pass/fail risks (Stage One)

| Risk | Where it comes from | Mitigation |
|---|---|---|
| **Meta model use was optional in the original plan.** Cutline 2 in `tasks.md` v1.1 said that if the Meta API is not integrated, ship the deterministic pack and "do not claim a Meta-powered demo". Honest, but a project with no genuine Meta Model API call **fails Stage One outright**. | `tasks.md` §Cutlines | **Fixed in v1.2:** cutline 0 now makes a real Muse Spark call and a real SAM 3.1 call the non-negotiable first gate; the knowledge feature is the cutline candidate, Meta integration is not. |
| **New-work delineation.** The parent FieldProof v1.1 kit is referenced as existing. If any of it predates October 27, the demo may only show post-kickoff work and must say so on screen. | Rules §4, "Demo requirements" | Keep `HACKATHON_CONTRIBUTIONS.md` as already planned; add a one-line on-screen caption in the video for each shown feature with its event-period commit date. |
| **Private monorepo lineage.** The private LensIQ repository is named repeatedly. Judges may read that as reuse. | Rules §4, "New work only" | The kit's clean-room stance is correct. Say it once in the README and never again in the video. |
| **Rights.** Vendor manuals, product photos, diagrams. | Rules §4, "Banned projects" | Already handled by `LinkOnly` rights mode and the schema's 480-character paraphrase cap. Keep it. |
| **Credits need a card on file and must be claimed by Nov 4, 9 PM ET.** | Resources §5 | Claim today. Set the spend-limit notification. The bounded model loop keeps cost trivially under $150. |

## 3. Scoring strengths and weaknesses (Stage Two, assumed rubric)

**Strengths**

- Verifiability is designed in, not described: fabricated fact IDs fail validation, fake fixes fail on independent measurement, every rejection is an auditable event. Few hackathon agents can show their own failure cases on purpose.
- Reproducible without hardware or private data. A judge can clone and run the synthetic fixture. Most "physical world" entries cannot be re-run by judges at all.
- Honest provenance vocabulary (`Documented` / `Site configured` / `Inferred` / `Measured`, `Simulated` never becomes `Live`). This reads as maturity.

**Weaknesses**

- **Thin multimodality.** Only Muse Spark vision and SAM 3.1 are used, and SAM is used mostly to draw a highlight box. The guide lists seven Meta models; breadth is not required, but SAM doing real evidentiary work would be.
- **The "physical system" is shallow.** In P0 the measured world is one HTTP health endpoint. The frontier statement asks for understanding or controlling a physical system; a health check is a thin proxy. Section 4 addresses this.
- **Scope versus eight days, solo.** .NET backend, Android client, Muse and SAM integration, state machine, receipts, and then this feature. The Android client is the most expensive and least judged component; the video and a web or CLI surface are what judges see. Treat Android as optional.
- **Demo narrative risk.** If the video opens with "we retrieve the manual section", judges file it under RAG. It must open with the fake fix being rejected.

## 4. Features that would make it frontier

Ranked by how much they change what the project *is*, divided by effort. The first two together form a single story: **the agent must predict, and the world must agree.**

### 4.1 Pre-registered predictions (strongly recommended, ~half a day) — specified in v1.2 as VK-R11, VK-T24–T25

Before any diagnostic runs, Muse must emit a falsifiable prediction tied to the facts it cites: "if the yellow cable is in P2 as LAB-PORT-MAP-01 requires, NODE-A-HEALTH will return reachable". Core records the prediction, runs the approved probe, and scores the match. The receipt gains a `predictions` block: predicted value, measured value, match, cited fact IDs.

Why it is frontier: it converts the model from an explainer into a hypothesis tester and makes hallucination *measurable* as a prediction-accuracy rate, which is precisely the "accelerate a scientific experiment" phrasing in the problem statement. It is the scientific method applied to a maintenance ticket. Nothing in the current architecture has to move; it is one more typed field on `VendorGroundedProposal` and one more comparison in the verification policy.

### 4.2 Segmentation-based change evidence (recommended, ~one day) — specified in v1.2 as VK-R12, VK-T26–T27

Run SAM 3.1 on the before and after photos against the same target (the port region). Compute whether the claimed change region actually changed: mask overlap, position of the cable mask relative to the port mask, and a perceptual hash to catch a re-submitted photo. A "repair" with no detectable visual change in the cited region yields `NoVisualChange` and cannot proceed, regardless of what the model's prose says.

Why it is frontier: it gives SAM an evidentiary role rather than a decorative one, and it adds a second, independent, non-LLM check on the physical world. Combined with 4.1 the demo shows three independent sources agreeing or disagreeing: segmentation geometry, probe measurement, and model prediction.

### 4.3 Model disagreement as an uncertainty signal (optional, ~half a day, uses partner credits)

Run the same bounded proposal through Muse Spark via the Meta Model API and Muse Spark Open via Baseten or Modal. If the two models propose different next actions or cite different facts, record `ModelDisagreement` and route to the technician. Receipts record model ID, version, prompt digest and response digest for both.

Why it matters: ensemble disagreement is a known verifiable-agent technique, it exercises both the hosted and open-weight halves of the Meta stack, and the partner credits make it free. It is also the natural stepping stone to an offline mode for sites without connectivity.

### 4.4 Hands-free claims with Muse Voice Transcribe (optional, ~half a day)

The technician speaks the repair claim and the acknowledgement. The transcript becomes evidence with the audio digest in the receipt. Field technicians have their hands full; this is realism, not novelty, and it adds a third Meta model with almost no architectural change. Already listed as P1 in the requirements.

### 4.5 Judge-runnable receipt verifier (demo hygiene, ~two hours)

A one-command CLI that takes a receipt bundle, recomputes every digest, replays the policy decisions from the recorded events, and prints pass or fail. Then tamper with one byte on camera and run it again. This is not frontier, but it lets Stage Two judges verify the auditability claim themselves instead of trusting the video.

### Not recommended before November 4

- Procedure-to-state-machine compilation (turning a manual procedure into tracked, evidence-gated steps). Real frontier, but it needs a procedure-rich vendor pack and a week on its own.
- Digital-twin drift ledger, fleet metrics, CMMS integration. Product roadmap, invisible in a three-minute video.
- Muse Image for generating fixture photos. Rights-clean, but "AI-generated evidence photos" is a sentence you do not want a judge to write down.

## 5. Recommended demo sequence (three minutes)

1. 0:00–0:30 The fake fix. Plausible after-photo, failing probe, `VerificationFailed`, with the prediction that was falsified on screen.
2. 0:30–1:15 The real loop. Asset confirmed, applicable facts with citations, Muse requests a close-up citing a fact ID, SAM localizes the port, prediction registered, probe runs, prediction confirmed, state advances under human acknowledgement.
3. 1:15–2:00 The boundary. Show a fabricated fact ID, a `POST /rest/system/reboot` proposal and a foreign asset ID being rejected, each appearing as a `KnowledgeProposalRejected` event.
4. 2:00–2:40 The receipt. Run the verifier, flip one byte, run it again.
5. 2:40–3:00 What was built during the event, with commit dates. Nothing else.

## 6. Track note

The project is prepared as an Indie Developer entry. The guide allows startups to build on existing codebases if new work is identified, and the Startups pool is larger. If LensIQ is an operating company this is a legitimate alternative, but it invites exactly the lineage scrutiny the kit has worked to avoid. The indie choice is the lower-risk one; this document does not recommend changing it.
