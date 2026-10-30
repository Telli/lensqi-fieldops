package com.lensqi.fieldops.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Wire models for the FieldOps inspection API. Shapes are pinned by the contract tests against
// fixtures/api-recordings; enum names are the server's PascalCase strings. The client renders these,
// it never derives a stage or an outcome from them.

@Serializable
enum class InspectionStage {
    Created, InitialScan, NeedsEvidence, FaultSuspected, FaultConfirmed,
    AwaitingRepair, ReadyToVerify, VerificationFailed, VerifiedComplete, Inconclusive,
}

@Serializable
enum class EvidenceOrigin { Phone, Glasses, RecordedFixture, MockWearables, Muse, Sam, LiveLocalProbe, SimulatedProbe, ReplayProbe, Technician }

@Serializable
enum class ObservationStatus { Observed, Suspected, Unknown, Conflicting }

@Serializable
enum class ProposedNextAction { RequestView, SegmentRegion, RunApprovedProbe, RequestHumanReview, PrepareWorkOrder, StopInconclusive }

@Serializable
enum class ChecklistStatus { Satisfied, NotSatisfied, Unknown, Conflicting }

/** Provenance of a measurement. Only LiveLocal can ever produce VerifiedCompleteLive. */
@Serializable
enum class DiagnosticSource { LiveLocal, Simulated, RecordedReplay, VendorReadOnly }

@Serializable
enum class VerificationResult { Verified, Denied, NeedsHumanReview }

@Serializable
enum class VerificationOutcomeKind { VerifiedCompleteLive, VerifiedCompleteSimulated }

@Serializable
enum class VisualChangeVerdict { Changed, NoVisualChange, DuplicateImage, Unscorable }

/** Server-computed change evidence for a post-repair photo (measured, visual). Never a verification by itself. */
@Serializable
data class VisualChangeAnalysis(
    val claimId: String,
    val photoEvidenceId: String,
    val referencePhotoEvidenceId: String? = null,
    val targetPrompt: String? = null,
    val perceptualHashDistance: Int? = null,
    val duplicateOfEvidenceId: String? = null,
    val targetRegionIoU: Double? = null,
    val componentShiftNormalized: Double? = null,
    val verdict: VisualChangeVerdict,
    val reason: String,
    val thresholdsVersion: String,
)

@Serializable
data class ApiError(val code: String, val message: String, val details: List<String>? = null)

@Serializable
data class Health(val status: String, val packs: Int, val fixtures: Int, val diagnosticsMode: DiagnosticSource)

@Serializable
data class Fixture(
    val fixtureId: String,
    val fixtureRevision: Int,
    val sha256: String,
    val fixtureDescription: String,
    val assets: List<FixtureAsset>,
    val diagnostics: List<FixtureDiagnostic>,
    val visualRequirements: List<VisualRequirement>,
    val verificationPolicy: JsonObject,
    val connections: List<FixtureConnection> = emptyList(),
)

/** The site's expected installation state for a link: documentation of intent, never proof of what is plugged in. */
@Serializable
data class FixtureConnection(val connectionId: String, val fromAssetId: String, val fromPortLabel: String, val toAssetId: String, val cableLabel: String, val expectedState: String)

/** A reviewer's request for more evidence; open until a photo is recorded after it. It never changes a decision. */
@Serializable
data class EvidenceRequest(val requestId: String, val note: String, val requestedBy: String, val requestedAt: String, val answeredByPhotoId: String? = null, val answeredAt: String? = null)

@Serializable
data class FixtureAsset(val assetId: String, val kind: String, val displayName: String)

@Serializable
data class FixtureDiagnostic(val diagnosticId: String, val type: String, val targetAlias: String, val claimVerified: String)

@Serializable
data class VisualRequirement(val requirementId: String, val mandatory: Boolean, val segmentationPrompt: String? = null)

@Serializable
data class CreateInspectionRequest(val fixtureId: String, val assetId: String)

@Serializable
data class ImageQuality(val blurLaplacianVariance: Double, val meanLuminance: Double, val analyzerVersion: String)

@Serializable
data class PhotoEvidence(
    val evidenceId: String,
    val origin: EvidenceOrigin,
    val artifactSha256: String,
    val observedAt: String,
    val quality: ImageQuality? = null,
)

@Serializable
data class DiagnosticResult(
    val fixtureId: String,
    val diagnosticId: String,
    val targetAlias: String,
    val passed: Boolean? = null,
    val completedAt: String,
    val source: DiagnosticSource,
    val traceId: String,
    val normalizedValue: String? = null,
    val rawDigest: String? = null,
    /** The status a live server answered with; absent when nothing answered. */
    val httpStatus: Int? = null,
)

@Serializable
data class DiagnosticEntry(val evidenceId: String, val result: DiagnosticResult, val source: DiagnosticSource)

@Serializable
data class ChecklistEntry(val requirementId: String, val status: ChecklistStatus, val reviewedAt: String? = null, val reviewedByEvidenceId: String? = null)

@Serializable
data class VerificationDecision(
    val result: VerificationResult,
    val outcomeKind: VerificationOutcomeKind? = null,
    val reasons: List<String>,
    val verifiedClaims: List<String>,
    val policyVersion: String,
    val fixtureDigest: String,
    val isVerified: Boolean,
)

/** GET /v1/inspections/{id}: the server's view of the session, the only source of stage and decision. */
@Serializable
data class InspectionView(
    val inspectionId: String,
    val fixtureId: String,
    val fixtureRevision: Int,
    val fixtureDigest: String,
    val assetId: String,
    val stage: InspectionStage,
    val lastSequence: Long,
    val viewRequests: Int,
    val currentClaimId: String? = null,
    val repairClaimedAt: String? = null,
    val technicianAcknowledged: Boolean,
    val photos: List<PhotoEvidence>,
    val diagnostics: List<DiagnosticEntry>,
    val checklist: List<ChecklistEntry>,
    val lastDecision: VerificationDecision? = null,
    val outcomeKind: VerificationOutcomeKind? = null,
    val changeAnalyses: List<VisualChangeAnalysis> = emptyList(),
    val segmentations: List<SegmentationRef> = emptyList(),
    /** The post-repair photo the server will count for the current claim; null when none is accepted yet. */
    val acceptedPostRepairPhotoId: String? = null,
    /** Newer post-repair photos still being compared with the earlier view; while any remain, verification waits. */
    val awaitingAnalysis: List<String> = emptyList(),
    val evidenceRequests: List<EvidenceRequest> = emptyList(),
    /** The server's next verify step and everything still blocking it; absent from an older server. */
    val readiness: Readiness? = null,
)

/**
 * What verify needs next, from the same policy the decision uses. next is kept as the server's string (ClaimRepair,
 * CapturePhoto, WaitForAnalysis, ReviewChecklist, Acknowledge, Verify, None) so a step added later cannot break parsing.
 * Ready never means verified: verify measures every fixture check again and decides on that.
 */
@Serializable
data class Readiness(val ready: Boolean, val next: String, val blocking: List<String>, val checks: List<ReadinessCheck> = emptyList(), val measuresOnVerify: Boolean = true)

@Serializable
data class ReadinessCheck(
    val diagnosticId: String,
    val lastRunAt: String? = null,
    val source: DiagnosticSource? = null,
    val passed: Boolean? = null,
    val value: String? = null,
    val afterClaim: Boolean = false,
)

@Serializable
data class ObservationResponse(
    val evidenceId: String,
    val sha256: String,
    val mediaType: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val quality: ImageQuality? = null,
    val perceptualHash: String? = null,
    val changeAnalysis: VisualChangeAnalysis? = null,
    val origin: EvidenceOrigin,
    val events: Int,
    val state: InspectionView,
)

@Serializable
data class Hypothesis(val featureId: String, val status: ObservationStatus, val reason: String, val evidenceIds: List<String>)

@Serializable
data class VisualAssessment(
    val assetId: String,
    val hypotheses: List<Hypothesis>,
    val nextAction: ProposedNextAction,
    val requestedView: String? = null,
    val requestedSegmentationConcept: String? = null,
    val modelId: String,
    val schemaVersion: String,
)

@Serializable
data class TokenUsage(val inputTokenCount: Long? = null, val outputTokenCount: Long? = null, val totalTokenCount: Long? = null)

@Serializable
data class InvestigateResponse(
    val evidenceId: String,
    val modelId: String,
    val promptDigest: String,
    val responseDigest: String,
    val usage: TokenUsage? = null,
    val assessment: VisualAssessment,
    val events: Int,
    val state: InspectionView,
)

@Serializable
data class SegmentRequest(val photoEvidenceId: String? = null, val concept: String? = null)

@Serializable
data class SegmentBox(val objectId: String, val left: Double, val top: Double, val right: Double, val bottom: Double, val confidence: Double? = null)

/** COCO compressed RLE: size is [height, width], counts are column-major. */
@Serializable
data class Rle(val size: List<Int>, val counts: String)

@Serializable
data class SegmentMask(
    val objectId: String,
    val width: Int,
    val height: Int,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val rle: Rle,
    val confidence: Double? = null,
)

@Serializable
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Serializable
data class CropTransform(val sourceEvidenceId: String, val sourceWidth: Int, val sourceHeight: Int, val crop: CropRect, val scale: Double)

@Serializable
data class SegmentCrop(val file: String, val sha256: String, val transform: CropTransform)

@Serializable
data class Segmentation(
    val segmentationId: String,
    val photoEvidenceId: String,
    val photoSha256: String,
    val concept: String,
    val model: String,
    val parser: String,
    val elapsedSeconds: Double,
    val matched: Boolean,
    val boxes: List<SegmentBox>,
    val masks: List<SegmentMask>,
    val maskSha256: String? = null,
    val crop: SegmentCrop? = null,
    val label: String,
    val note: String,
)

@Serializable
data class SegmentResponse(val segmentation: Segmentation, val state: InspectionView)

@Serializable
data class Citation(val documentTitle: String, val publisher: String, val sourceUrl: String? = null, val sourceLocator: String, val accessedOn: String)

@Serializable
data class KnowledgeFact(
    val factId: String,
    val factKey: String,
    val assertedValue: String,
    val statement: String,
    val claimKind: String,
    val provenance: String,
    val safety: String,
    val tags: List<String>,
    val citation: Citation,
)

@Serializable
data class KnowledgeResponse(
    val inspectionId: String,
    val status: String,
    val packId: String? = null,
    val packSha256: String? = null,
    val packRevision: Int? = null,
    val facts: List<KnowledgeFact>,
    val warnings: List<String>,
    val approvedDiagnostics: List<String> = emptyList(),
)

@Serializable
data class DiagnosticRunResponse(val evidenceId: String, val result: DiagnosticResult, val source: DiagnosticSource, val events: Int, val state: InspectionView)

@Serializable
data class WorkOrderRequest(val summary: String)

@Serializable
data class RepairClaimRequest(val description: String)

@Serializable
data class RepairClaimResponse(val claimId: String, val state: InspectionView)

@Serializable
data class ChecklistRequest(val requirementId: String, val status: ChecklistStatus, val photoEvidenceId: String)

@Serializable
data class VerifyResponse(val decision: VerificationDecision, val state: InspectionView, val replayed: Boolean)

@Serializable
data class ReceiptVerification(val bundle: String, val isValid: Boolean, val checks: List<String>, val failures: List<String>)

@Serializable
data class SimulationRequest(val passed: Boolean?)

@Serializable
data class SimulationResponse(val diagnosticId: String, val passed: Boolean? = null, val label: String)

/** GET /v1/inspections: one row per inspection, derived by the server from its event log. */
@Serializable
data class InspectionSummary(
    val inspectionId: String,
    val fixtureId: String,
    val assetId: String,
    val stage: InspectionStage,
    val outcomeKind: VerificationOutcomeKind? = null,
    val createdAt: String,
    val updatedAt: String,
    val photos: Int,
    val diagnostics: Int,
    val lastDecision: VerificationResult? = null,
    val latestChange: VisualChangeVerdict? = null,
    val currentClaimId: String? = null,
    val workOrder: String? = null,
    val claim: String? = null,
    val verificationAttempts: Int = 0,
    val openEvidenceRequest: EvidenceRequest? = null,
)

/** A segmentation recorded on the inspection; its geometry is the artifact "<evidenceId>.json". */
@Serializable
data class SegmentationRef(val evidenceId: String, val sourcePhotoEvidenceId: String, val concept: String, val matched: Boolean, val maskSha256: String? = null)
