package com.example.llamadroid.wear

import android.content.Context
import android.content.Intent
import com.example.llamadroid.R
import com.example.llamadroid.service.OnnxImageGenerationService
import com.example.llamadroid.service.StableDiffusionService
import com.example.llamadroid.service.UnifiedNotificationManager
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
            ?: return unavailable(context, request)

        return when (command) {
            "cancel" -> {
                val cancellation = cancellationIntent(context, task)
                if (!task.canCancelFromWear() || cancellation == null) {
                    unavailable(context, request)
                } else {
                    val delivered = runCatching { context.startService(cancellation) != null }.getOrDefault(false)
                    if (!delivered) unavailable(context, request) else {
                        UnifiedNotificationManager.updateProgress(task.id, task.progress,
                            context.getString(R.string.wear_task_cancelling))
                        CommandAckDto(
                            commandId = request.meta.requestId,
                            accepted = true,
                            status = "ACKNOWLEDGED",
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    }
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
        !isComplete && !isError && when (cancellationOwner) {
            UnifiedNotificationManager.CancellationOwner.STABLE_DIFFUSION,
            UnifiedNotificationManager.CancellationOwner.ONNX_IMAGE ->
                type == UnifiedNotificationManager.TaskType.IMAGE_GEN
            UnifiedNotificationManager.CancellationOwner.VIDEO ->
                type == UnifiedNotificationManager.TaskType.VIDEO_GEN
            null -> false
        }

    /**
     * The Wear Tasks surface is for work with observable progress. A running LLM server and an
     * active AI agent are persistent runtime states, not progressing jobs, so they belong in
     * their dedicated Watch surfaces instead of the Tasks tile.
     */
    private fun UnifiedNotificationManager.TaskInfo.isWearProgressTask(): Boolean = type !in setOf(
        UnifiedNotificationManager.TaskType.LLAMA_SERVER,
        UnifiedNotificationManager.TaskType.AGENT
    )

    internal fun cancellationIntent(context: Context, task: UnifiedNotificationManager.TaskInfo): Intent? {
        if (!task.canCancelFromWear()) return null
        return when (task.cancellationOwner) {
            UnifiedNotificationManager.CancellationOwner.STABLE_DIFFUSION ->
                StableDiffusionService.createCancelAllIntent(context, task.id)
            UnifiedNotificationManager.CancellationOwner.ONNX_IMAGE ->
                OnnxImageGenerationService.createCancelIntent(context, task.id)
            UnifiedNotificationManager.CancellationOwner.VIDEO ->
                VideoGenerationService.createCancelAllIntent(context, task.id)
            null -> null
        }
    }

    private fun unavailable(context: Context, request: TaskCommandRequest): CommandAckDto = CommandAckDto(
        commandId = request.meta.requestId,
        accepted = false,
        status = "FAILED",
        errorCode = "task_unavailable",
        errorMessage = context.getString(R.string.wear_task_unavailable),
        updatedAtEpochMs = System.currentTimeMillis()
    )
}
