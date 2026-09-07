package com.example.llamadroid.util

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat

/** API-26-safe access to the full package version code. */
internal object AppVersionCodeCompat {
    fun read(packageInfo: PackageInfo): Long =
        PackageInfoCompat.getLongVersionCode(packageInfo)
}
