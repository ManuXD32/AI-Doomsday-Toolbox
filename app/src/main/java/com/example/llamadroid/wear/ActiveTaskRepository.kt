package com.example.llamadroid.wear

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.service.OnnxImageGenerationService
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.StableDiffusionService
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.VideoGenerationService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

object ActiveTaskRepository {
    fun observeSnapshot(context: Context): Flow<ActiveTaskSnapshot> =
        UnifiedNotificationManager.activeTasks.map { tasks ->
            ActiveTaskSnapshot(
                revisioned = Revisioned(
                    revision = System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                    sourceDeviceId = context.packageName
                ),
                tasks = tasks.filter { it.isWearProgressTask() }.map { it.toWearTask() }
            )
        }

    fun currentSnapshot(context: Context): ActiveTaskSnapshot =
        ActiveTaskSnapshot(
            revisioned = Revisioned(
                revision = System.currentTimeMillis(),
                updatedAtEpochMs = System.currentTimeMillis(),
                sourceDeviceId = context.packageName
            ),
            tasks = UnifiedNotificationManager.activeTasks.value.filter { it.isWearProgressTask() }.map { it.toWearTask() }
        )

    fun handleCommand(context: Context, request: TaskCommandRequest, command: String): CommandAckDto {
        val task = UnifiedNotificationManager.activeTasks.value.firstOrNull {
            it.id.toString() == request.taskId && it.isWearProgressTask()
        }
            ?: return CommandAckDto(
                commandId = request.meta.requestId,
                accepted = false,
                status = "FAILED",
                errorCode = "task_not_found",
                errorMessage = context.getString(R.string.wear_task_not_found),
                updatedAtEpochMs = System.currentTimeMillis()
            )

        return when (command) {
            "cancel" -> {
                if (!task.canCancelFromWear()) {
                    CommandAckDto(
                        commandId = request.meta.requestId,
                        accepted = false,
                        status = "FAILED",
                        errorCode = "task_not_cancellable",
                        errorMessage = context.getString(R.string.wear_task_not_cancellable),
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                } else {
                    cancelTask(context, task.type)
                    UnifiedNotificationManager.updateProgress(task.id, task.progress, context.getString(R.string.wear_task_cancelling))
                    CommandAckDto(
                        commandId = request.meta.requestId,
                        accepted = true,
                        status = "ACKNOWLEDGED",
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                }
            }
            "pause", "resume" -> CommandAckDto(
                commandId = request.meta.requestId,
                accepted = false,
                status = "FAILED",
                errorCode = "unsupported",
                errorMessage = context.getString(R.string.wear_task_pause_resume_unavailable),
                updatedAtEpochMs = System.currentTimeMillis()
            )
            else -> CommandAckDto(
                commandId = request.meta.requestId,
                accepted = false,
                status = "FAILED",
                errorCode = "unknown_command",
                errorMessage = context.getString(R.string.wear_bridge_unknown_path),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }
    }

    internal fun UnifiedNotificationManager.TaskInfo.toWearTask(): ActiveTaskSummary {
        val percent = progress.takeIf { it >= 0f }?.let { (it.coerceIn(0f, 1f) * 100f).roundToInt() }
        val state = when {
            isError -> "FAILED"
            isComplete -> "COMPLETED"
            progressText.contains("cancel", ignoreCase = true) -> "CANCELLING"
            progress < 0f -> "RUNNING"
            else -> "RUNNING"
        }
        return ActiveTaskSummary(
            taskId = id.toString(),
            taskType = type.name,
            title = title.ifBlank { type.label },
            subtitle = progressText,
            state = state,
            stage = progressDetails.lastOrNull().orEmpty(),
            progressCurrent = percent?.toLong(),
            progressMaximum = percent?.let { 100L },
            progressPercent = percent,
            indeterminate = progress < 0f,
            updatedAtEpochMs = System.currentTimeMillis(),
            canCancel = canCancelFromWear(),
            errorMessage = errorMessage
        )
    }

    private fun UnifiedNotificationManager.TaskInfo.canCancelFromWear(): Boolean =
        !isComplete && !isError && type in setOf(
            UnifiedNotificationManager.TaskType.IMAGE_GEN,
            UnifiedNotificationManager.TaskType.VIDEO_GEN
        )

    /**
     * The Wear Tasks surface is for work with observable progress. A running LLM server and an
     * active AI agent are persistent runtime states, not progressing jobs, so they belong in
     * their dedicated Watch surfaces instead of the Tasks tile.
     */
    private fun UnifiedNotificationManager.TaskInfo.isWearProgressTask(): Boolean = type !in setOf(
        UnifiedNotificationManager.TaskType.LLAMA_SERVER,
        UnifiedNotificationManager.TaskType.AGENT
    )

    private fun cancelTask(context: Context, type: UnifiedNotificationManager.TaskType) {
        when (type) {
            UnifiedNotificationManager.TaskType.IMAGE_GEN -> {
                context.startService(StableDiffusionService.createCancelAllIntent(context))
                context.startService(OnnxImageGenerationService.createCancelIntent(context))
            }
            UnifiedNotificationManager.TaskType.VIDEO_GEN -> {
                VideoGenerationMode.values().forEach { mode ->
                    context.startService(VideoGenerationService.createCancelIntent(context, mode))
                }
            }
            else -> Unit
        }
    }
}
