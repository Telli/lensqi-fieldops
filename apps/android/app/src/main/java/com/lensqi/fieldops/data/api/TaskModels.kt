package com.lensqi.fieldops.data.api

import kotlinx.serialization.Serializable

@Serializable enum class TaskMode { Diy, Technician, Freeform }
@Serializable enum class TaskPhase { Planned, InProgress, Completed, Abandoned }
@Serializable enum class RiskLevel { Low, Moderate, High }
@Serializable enum class StepStatus { Todo, Active, Done, Skipped }
@Serializable enum class StepVerdict { LooksDone, NotYet, CantTell }

@Serializable data class TaskCaution(val kind: String, val text: String)
@Serializable data class TaskStepCheckSpec(val required: Boolean, val whatDoneLooksLike: String)
@Serializable data class TaskStepCheck(val stepId: String, val photoId: String, val verdict: StepVerdict, val reason: String, val modelId: String = "", val at: String? = null)

@Serializable
data class TaskStep(
    val id: String, val title: String, val instruction: String, val handsOn: Boolean,
    val lookFor: List<String> = emptyList(), val cautions: List<TaskCaution> = emptyList(), val stopIf: List<String> = emptyList(),
    val check: TaskStepCheckSpec? = null, val status: StepStatus = StepStatus.Todo, val lastCheck: TaskStepCheck? = null,
    /** On a ladder or step-stool step: the part it reaches, so the phone can check whether it is needed. */
    val accessFor: String? = null, val skipReason: SkipReason? = null,
    val sourceFacts: List<KnowledgeFact> = emptyList(),
    val referencePhotoIds: List<String> = emptyList(),
)

/** Why a step was skipped, with the measured numbers when the phone took them. */
@Serializable data class SkipReason(val text: String? = null, val reason: String? = null, val partHeightCm: Int? = null, val reachCm: Int? = null)
@Serializable data class SkipRequest(val reason: String, val partHeightCm: Int? = null, val reachCm: Int? = null)
@Serializable data class TaskPerson(val heightCm: Int? = null, val standingReachCm: Int? = null, val tools: List<String> = emptyList())

@Serializable
data class TaskPlan(
    val title: String, val summary: String, val risk: RiskLevel, val estimatedMinutes: Int, val restricted: String,
    val professionalRequired: Boolean, val professionalReason: String? = null, val tools: List<String> = emptyList(),
    val parts: List<String> = emptyList(), val sourceNote: String, val modelId: String? = null, val needsSafetyAck: Boolean,
    val steps: List<TaskStep>,
)

@Serializable data class TaskSite(val fixtureId: String, val assetId: String)
@Serializable data class TaskAnswer(val stepId: String? = null, val question: String, val answer: String, val photoId: String? = null, val modelId: String = "", val at: String = "")
/** [origin] is the capture device the client declared; an older server leaves it out, which means a phone photo. */
@Serializable data class TaskPhoto(val evidenceId: String, val purpose: String, val stepId: String? = null, val origin: EvidenceOrigin? = null, val sha256: String? = null)

@Serializable
data class TaskView(
    val taskId: String, val mode: TaskMode, val goal: String, val details: String? = null, val site: TaskSite? = null, val phase: TaskPhase,
    val currentStepId: String? = null, val inspectionId: String? = null, val updatedAt: String, val safetyAcknowledged: Boolean = false,
    val plan: TaskPlan? = null, val photos: List<TaskPhoto> = emptyList(), val answers: List<TaskAnswer> = emptyList(),
    val person: TaskPerson? = null, val title: String? = null,
    /** The title was named by the AI from a free-form conversation; shown with a label, never as a finding. */
    val titleByAi: Boolean = false,
) {
    val currentStep: TaskStep? get() = plan?.steps?.firstOrNull { it.id == currentStepId }
    /** The capture device of one of the task's photos, as the server recorded it. */
    fun photoOrigin(photoId: String): EvidenceOrigin? = photos.firstOrNull { it.evidenceId == photoId }?.origin
}

/** Where a check photo came from when it was not the phone. Mock glasses are never shown as real glasses or as a phone. */
fun checkPhotoNote(task: TaskView, check: TaskStepCheck): String = when (task.photoOrigin(check.photoId)) {
    EvidenceOrigin.MockWearables -> " · photo from mock glasses, simulated"
    EvidenceOrigin.Glasses -> " · photo from the glasses"
    else -> ""
}

@Serializable
data class TaskSummary(
    val taskId: String, val mode: TaskMode, val goal: String, val title: String? = null, val phase: TaskPhase, val steps: Int, val finished: Int,
    val currentStepTitle: String? = null, val inspectionId: String? = null, val updatedAt: String, val titleByAi: Boolean = false,
)

@Serializable data class PlanError(val code: String, val message: String)
@Serializable data class CreateTaskResponse(val task: TaskView, val planError: PlanError? = null)
@Serializable data class AcceptRequest(val safetyAcknowledged: Boolean)
@Serializable data class AbandonRequest(val reason: String? = null)
@Serializable data class StepCheckResponse(val check: TaskStepCheck, val task: TaskView)
@Serializable data class AskResponse(val answer: String, val focus: List<String> = emptyList(), val stepId: String? = null, val modelId: String = "")
@Serializable data class LinkInspectionResponse(val inspectionId: String, val task: TaskView)
