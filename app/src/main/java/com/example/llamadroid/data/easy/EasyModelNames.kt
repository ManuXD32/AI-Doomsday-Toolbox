package com.example.llamadroid.data.easy

import androidx.annotation.StringRes
import com.example.llamadroid.data.db.ModelType

/** Friendly Easy-mode model names: the curated bundle title when known, else the file name. */
object EasyModelNames {
    /** Title resource of the Easy chat bundle whose main LLM file is [filename], or null. */
    @StringRes
    fun curatedTitleRes(filename: String?): Int? {
        val name = filename?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return null
        return EasyModelCatalog.entriesFor(EasyModelKind.CHAT).firstNotNullOfOrNull { entry ->
            EasyModelCatalog.llamaBundle(entry)?.takeIf { bundle ->
                bundle.files.any { it.type == ModelType.LLM && it.matchesInstalledFilename(name) }
            }?.titleRes
        }
    }

    /** [curatedTitle] (resolved from [curatedTitleRes]) without a trailing "complete", else the file name minus `.gguf`. */
    fun friendlyName(filename: String?, curatedTitle: String?): String {
        curatedTitle?.replace(Regex("\\s+(complete|completo)$", RegexOption.IGNORE_CASE), "")?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return filename?.substringAfterLast('/')?.removeSuffix(".gguf").orEmpty()
    }
}
