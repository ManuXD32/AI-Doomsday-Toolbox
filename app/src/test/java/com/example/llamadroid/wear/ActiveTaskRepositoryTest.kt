package com.example.llamadroid.wear

import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.service.StableDiffusionService
import com.example.llamadroid.service.OnnxImageGenerationService
import com.example.llamadroid.service.VideoGenerationService
import com.example.llamadroid.service.matchesNotificationTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ActiveTaskRepositoryTest {
    @Test
    fun imageAndVideoTasksMapToCancellableWearTasks() {
        val image = UnifiedNotificationManager.TaskInfo(
            id = 101,
            type = UnifiedNotificationManager.TaskType.IMAGE_GEN,
            title = "Image generation",
            progress = 0.53f,
            progressText = "Sampling",
            cancellationOwner = UnifiedNotificationManager.CancellationOwner.STABLE_DIFFUSION
        )
        val video = UnifiedNotificationManager.TaskInfo(
            id = 102,
            type = UnifiedNotificationManager.TaskType.VIDEO_GEN,
            title = "Video generation",
            progress = -1f,
            progressText = "Rendering",
            cancellationOwner = UnifiedNotificationManager.CancellationOwner.VIDEO
        )

        val imageWear = with(ActiveTaskRepository) { image.toWearTask() }
        val videoWear = with(ActiveTaskRepository) { video.toWearTask() }

        assertEquals("101", imageWear.taskId)
        assertEquals(53, imageWear.progressPercent)
        assertTrue(imageWear.canCancel)
        assertTrue(videoWear.indeterminate)
        assertTrue(videoWear.canCancel)
    }

    @Test
    fun completedAndUnsupportedTasksAreNotCancellableFromWear() {
        val task = UnifiedNotificationManager.TaskInfo(
            id = 201,
            type = UnifiedNotificationManager.TaskType.TRANSCRIPTION,
            title = "Transcription",
            progress = 1f,
            progressText = "Complete",
            isComplete = true
        )

        val wear = with(ActiveTaskRepository) { task.toWearTask() }

        assertEquals("COMPLETED", wear.state)
        assertFalse(wear.canCancel)
    }

    @Test fun cancellationRoutesOnlyToSelectedOwnerAndCarriesTaskIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val owners = listOf(
            UnifiedNotificationManager.CancellationOwner.STABLE_DIFFUSION to StableDiffusionService::class.java,
            UnifiedNotificationManager.CancellationOwner.ONNX_IMAGE to OnnxImageGenerationService::class.java,
            UnifiedNotificationManager.CancellationOwner.VIDEO to VideoGenerationService::class.java
        )
        owners.forEachIndexed { index, (owner, service) ->
            val task = UnifiedNotificationManager.TaskInfo(
                id = 300 + index,
                type = if (owner == UnifiedNotificationManager.CancellationOwner.VIDEO)
                    UnifiedNotificationManager.TaskType.VIDEO_GEN else UnifiedNotificationManager.TaskType.IMAGE_GEN,
                title = "Running",
                cancellationOwner = owner
            )
            val intent = ActiveTaskRepository.cancellationIntent(context, task)!!
            assertEquals(service.name, intent.component?.className)
            assertTrue(intent.matchesNotificationTask(task.id))
            assertFalse(intent.matchesNotificationTask(task.id + 1))
        }
    }

    @Test fun unownedAndCompletedTasksDoNotPublishWatchCancellation() {
        val context = RuntimeEnvironment.getApplication()
        val unowned = UnifiedNotificationManager.TaskInfo(
            id = 410, type = UnifiedNotificationManager.TaskType.IMAGE_GEN, title = "Unknown"
        )
        assertFalse(with(ActiveTaskRepository) { unowned.toWearTask().canCancel })
        assertEquals(null, ActiveTaskRepository.cancellationIntent(context, unowned))
        val completed = unowned.copy(cancellationOwner = UnifiedNotificationManager.CancellationOwner.ONNX_IMAGE,
            isComplete = true)
        assertEquals(null, ActiveTaskRepository.cancellationIntent(context, completed))
    }

    @Test fun simultaneousImageRegistrationsKeepSeparateWatchCancellationOwners() {
        val context = RuntimeEnvironment.getApplication()
        UnifiedNotificationManager.init(context)
        val sdId = UnifiedNotificationManager.startTask(
            UnifiedNotificationManager.TaskType.IMAGE_GEN, "Stable Diffusion",
            cancellationOwner = UnifiedNotificationManager.CancellationOwner.STABLE_DIFFUSION)
        val onnxId = UnifiedNotificationManager.startTask(
            UnifiedNotificationManager.TaskType.IMAGE_GEN, "ONNX",
            cancellationOwner = UnifiedNotificationManager.CancellationOwner.ONNX_IMAGE)
        try {
            val tasks = UnifiedNotificationManager.activeTasks.value.associateBy { it.id }
            val sdCommand = ActiveTaskRepository.cancellationIntent(context, tasks.getValue(sdId))!!
            val onnxCommand = ActiveTaskRepository.cancellationIntent(context, tasks.getValue(onnxId))!!
            assertEquals(StableDiffusionService::class.java.name, sdCommand.component?.className)
            assertEquals(OnnxImageGenerationService::class.java.name, onnxCommand.component?.className)
            assertTrue(sdCommand.matchesNotificationTask(sdId))
            assertFalse(sdCommand.matchesNotificationTask(onnxId))
            assertTrue(onnxCommand.matchesNotificationTask(onnxId))
            assertFalse(onnxCommand.matchesNotificationTask(sdId))
        } finally {
            UnifiedNotificationManager.dismissTask(sdId)
            UnifiedNotificationManager.dismissTask(onnxId)
        }
    }
}
