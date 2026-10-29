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
)

@Serializable
data class TaskPlan(
    val title: String, val summary: String, val risk: RiskLevel, val estimatedMinutes: Int, val restricted: String,
    val professionalRequired: Boolean, val professionalReason: String? = null, val tools: List<String> = emptyList(),
    val parts: List<String> = emptyList(), val sourceNote: String, val modelId: String? = null, val needsSafetyAck: Boolean,
    val steps: List<TaskStep>,
)

@Serializable data class TaskSite(val fixtureId: String, val assetId: String)
@Serializable data class TaskAnswer(val stepId: String? = null, val question: String, val answer: String, val photoId: String? = null, val modelId: String = "")
@Serializable data class TaskPhoto(val evidenceId: String, val purpose: String, val stepId: String? = null)

@Serializable
data class TaskView(
    val taskId: String, val mode: TaskMode, val goal: String, val details: String? = null, val site: TaskSite? = null, val phase: TaskPhase,
    val currentStepId: String? = null, val inspectionId: String? = null, val updatedAt: String, val safetyAcknowledged: Boolean = false,
    val plan: TaskPlan? = null, val photos: List<TaskPhoto> = emptyList(), val answers: List<TaskAnswer> = emptyList(),
) {
    val currentStep: TaskStep? get() = plan?.steps?.firstOrNull { it.id == currentStepId }
}

@Serializable
data class TaskSummary(
    val taskId: String, val mode: TaskMode, val goal: String, val title: String? = null, val phase: TaskPhase, val steps: Int, val finished: Int,
    val currentStepTitle: String? = null, val inspectionId: String? = null, val updatedAt: String,
)

@Serializable data class PlanError(val code: String, val message: String)
@Serializable data class CreateTaskResponse(val task: TaskView, val planError: PlanError? = null)
@Serializable data class AcceptRequest(val safetyAcknowledged: Boolean)
@Serializable data class StepCheckResponse(val check: TaskStepCheck, val task: TaskView)
@Serializable data class AskResponse(val answer: String, val focus: List<String> = emptyList(), val stepId: String? = null, val modelId: String = "")
@Serializable data class LinkInspectionResponse(val inspectionId: String, val task: TaskView)
