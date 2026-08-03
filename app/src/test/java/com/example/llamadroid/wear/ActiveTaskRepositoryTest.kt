package com.example.llamadroid.wear

import com.example.llamadroid.service.UnifiedNotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTaskRepositoryTest {
    @Test
    fun imageAndVideoTasksMapToCancellableWearTasks() {
        val image = UnifiedNotificationManager.TaskInfo(
            id = 101,
            type = UnifiedNotificationManager.TaskType.IMAGE_GEN,
            title = "Image generation",
            progress = 0.53f,
            progressText = "Sampling"
        )
        val video = UnifiedNotificationManager.TaskInfo(
            id = 102,
            type = UnifiedNotificationManager.TaskType.VIDEO_GEN,
            title = "Video generation",
            progress = -1f,
            progressText = "Rendering"
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
}
