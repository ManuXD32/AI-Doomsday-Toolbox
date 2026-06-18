package com.example.llamadroid.tama.data

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

private const val TAMA_DIALOG_ASSET = "tama/dialogs/pet_dialogs.json"

@Serializable
private data class TamaDialogCatalogFile(
    val version: Int = 1,
    val entries: List<TamaDialogCatalogEntry> = emptyList()
)

@Serializable
private data class TamaDialogCatalogEntry(
    val key: String,
    val category: String = "",
    val worldOrRoomId: String = "",
    val phase: Int? = null,
    val npcId: String = "",
    val lineIndex: Int? = null,
    val en: String = "",
    val es: String = ""
)

object TamaDialogTextCatalog {
    @Volatile
    private var cachedEntries: Map<String, TamaDialogCatalogEntry>? = null

    fun localizedResource(context: Context, @StringRes resId: Int): String {
        val fallback = context.getString(resId)
        val key = runCatching { context.resources.getResourceEntryName(resId) }.getOrNull()
            ?: return fallback
        return localizedText(context, key, fallback)
    }

    fun localizedText(context: Context, key: String, fallback: String = ""): String {
        val entry = entries(context)[key] ?: return fallback
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val candidate = if (locale.language.lowercase(Locale.ROOT).startsWith("es")) entry.es else entry.en
        return candidate.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun entries(context: Context): Map<String, TamaDialogCatalogEntry> {
        cachedEntries?.let { return it }
        val loaded = runCatching {
            context.assets.open(TAMA_DIALOG_ASSET).bufferedReader().use { reader ->
                Json.decodeFromString<TamaDialogCatalogFile>(reader.readText())
            }.entries.associateBy { it.key }
        }.getOrDefault(emptyMap())
        cachedEntries = loaded
        return loaded
    }
}
