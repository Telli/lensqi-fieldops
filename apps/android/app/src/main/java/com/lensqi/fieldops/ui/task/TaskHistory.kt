package com.lensqi.fieldops.ui.task

import com.lensqi.fieldops.data.api.TaskMode
import com.lensqi.fieldops.data.api.TaskPhase
import com.lensqi.fieldops.data.api.TaskSummary

enum class TaskFilter { All, Active, Completed, Stopped }
fun taskHistory(tasks: List<TaskSummary>, filter: TaskFilter, query: String): List<TaskSummary> = tasks.filter {
    (when (filter) {
        TaskFilter.All -> true
        TaskFilter.Active -> it.phase == TaskPhase.Planned || it.phase == TaskPhase.InProgress
        TaskFilter.Completed -> it.phase == TaskPhase.Completed
        TaskFilter.Stopped -> it.phase == TaskPhase.Abandoned
    }) && (query.isBlank() || "${it.goal} ${it.title.orEmpty()} ${it.taskId}".contains(query.trim(), ignoreCase = true))
}
fun siteForMode(mode: TaskMode, site: Pair<String, String>?): Pair<String, String>? = site.takeIf { mode == TaskMode.Technician }

fun taskRoute(task: TaskSummary): String = when {
    task.phase == TaskPhase.Completed || task.phase == TaskPhase.Abandoned -> "task/${task.taskId}/done"
    task.mode == TaskMode.Freeform -> "task/${task.taskId}/live"
    task.phase == TaskPhase.InProgress -> "task/${task.taskId}/step"
    else -> "task/${task.taskId}"
}
fun taskStatus(task: TaskSummary): String = when (task.phase) {
    TaskPhase.Completed -> "Done"
    TaskPhase.Abandoned -> "Stopped"
    TaskPhase.Planned -> if (task.title != null) "Plan ready" else "No plan yet"
    TaskPhase.InProgress -> if (task.mode == TaskMode.Freeform) "Free-form help · open" else "${task.currentStepTitle ?: "In progress"} · ${task.finished}/${task.steps}"
}
