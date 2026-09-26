package com.example.llamadroid.service

import android.content.Intent

/** Legacy on-phone cancel intents omit the guard; watch commands always supply it. */
internal fun Intent.matchesNotificationTask(currentTaskId: Int?): Boolean =
    !hasExtra(UnifiedNotificationManager.EXTRA_EXPECTED_TASK_ID) ||
        currentTaskId == getIntExtra(UnifiedNotificationManager.EXTRA_EXPECTED_TASK_ID, -1)
