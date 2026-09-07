package com.example.llamadroid.ui.navigation

import android.content.Intent
import android.net.Uri
import com.example.llamadroid.util.getParcelableExtraCompat
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val EXTRA_OPEN_ROUTE = "extra_open_route"

/**
 * Returns a stable, opaque identity for the launch inputs consumed by MainActivity.
 *
 * Only the action/type/data/categories and the route or shared-stream inputs participate. The
 * complete canonical input exists only while computing the digest; the returned value never
 * contains the raw route or URI and is safe to save in an instance-state Bundle.
 */
internal fun appLaunchIdentity(intent: Intent?): String {
    val canonical = buildString {
        appendSnapshot("intent", if (intent == null) InputSnapshot.Absent else InputSnapshot.Value("present"))
        appendSnapshot("action", intent.readField { it.action })
        appendSnapshot("type", intent.readField { it.type })
        appendSnapshot("data", intent.readField { it.data?.toString() })
        appendSnapshot("categories", intent.readCategories())
        appendSnapshot("open_route", intent.readStringExtra(EXTRA_OPEN_ROUTE))
        appendSnapshot("stream_uri", intent.readStreamUri())
    }
    return sha256(canonical)
}

private sealed interface InputSnapshot {
    data object Absent : InputSnapshot
    data object Malformed : InputSnapshot
    data class Value(val value: String?) : InputSnapshot
}

private inline fun <T> Intent?.readField(reader: (Intent) -> T?): InputSnapshot {
    if (this == null) return InputSnapshot.Absent
    return try {
        InputSnapshot.Value(reader(this)?.toString())
    } catch (_: RuntimeException) {
        InputSnapshot.Malformed
    }
}

private fun Intent?.readStringExtra(key: String): InputSnapshot {
    val source = this ?: return InputSnapshot.Absent
    val present = try {
        source.hasExtra(key)
    } catch (_: RuntimeException) {
        return InputSnapshot.Malformed
    }
    if (!present) return InputSnapshot.Absent
    return try {
        InputSnapshot.Value(source.getStringExtra(key))
    } catch (_: RuntimeException) {
        InputSnapshot.Malformed
    }
}

private fun Intent?.readStreamUri(): InputSnapshot {
    val source = this ?: return InputSnapshot.Absent
    val present = try {
        source.hasExtra(Intent.EXTRA_STREAM)
    } catch (_: RuntimeException) {
        return InputSnapshot.Malformed
    }
    if (!present) return InputSnapshot.Absent
    return try {
        val uri = source.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
        InputSnapshot.Value(uri?.toString())
    } catch (_: RuntimeException) {
        InputSnapshot.Malformed
    }
}

private fun Intent?.readCategories(): InputSnapshot {
    val source = this ?: return InputSnapshot.Absent
    return try {
        val categories = source.categories
        InputSnapshot.Value(
            categories?.toList()?.sorted()?.let { values ->
                buildString {
                    append(values.size).append(':')
                    values.forEach { value ->
                        append(value.length).append(':').append(value)
                    }
                }
            }
        )
    } catch (_: RuntimeException) {
        InputSnapshot.Malformed
    }
}

private fun StringBuilder.appendSnapshot(name: String, snapshot: InputSnapshot) {
    append(name).append('=')
    when (snapshot) {
        InputSnapshot.Absent -> append('A')
        InputSnapshot.Malformed -> append('M')
        is InputSnapshot.Value -> {
            if (snapshot.value == null) {
                append('N')
            } else {
                append('V').append(snapshot.value.length).append(':').append(snapshot.value)
            }
        }
    }
    append(';')
}

private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(hex[unsigned ushr 4])
            append(hex[unsigned and 0x0f])
        }
    }
}
