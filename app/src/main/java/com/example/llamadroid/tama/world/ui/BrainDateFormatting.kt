package com.example.llamadroid.tama.world.ui

import android.content.Context
import android.text.format.DateUtils

/** Formats persisted brain timestamps with the locale configured on the owning app context. */
internal fun formatBrainTimestamp(context: Context, epochMillis: Long): String =
    DateUtils.formatDateTime(
        context,
        epochMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR
    )
