package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.util.DebugLog
import java.io.File

object WhisperModelPathResolver {

    suspend fun resolve(context: Context, preferredPath: String?): String? {
        preferredPath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { path ->
                if (File(path).isReadableFile()) {
                    return path
                }
            }

        val appContext = context.applicationContext
        val dao = AppDatabase.getDatabase(appContext).modelDao()
        val whisperModels = dao.getModelsByTypesSync(listOf(ModelType.WHISPER))
        val preferredFilename = preferredPath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.substringAfterLast("/")

        val prioritizedModels = whisperModels.prioritize(preferredFilename)
        prioritizedModels.firstOrNull { File(it.path).isReadableFile() }?.let { model ->
            logRepair(preferredPath, model.path)
            return model.path
        }

        val filenames = buildList {
            preferredFilename?.takeIf { it.isNotBlank() }?.let(::add)
            prioritizedModels.mapTo(this) { it.filename }
        }.distinct()

        for (filename in filenames) {
            for (root in candidateRoots(appContext)) {
                val candidate = File(root, filename)
                if (candidate.isReadableFile()) {
                    prioritizedModels.firstOrNull { it.filename == filename }?.let { model ->
                        if (model.path != candidate.absolutePath || model.sizeBytes != candidate.length()) {
                            runCatching {
                                dao.insertModel(
                                    model.copy(
                                        path = candidate.absolutePath,
                                        sizeBytes = candidate.length()
                                    )
                                )
                            }
                        }
                    }
                    logRepair(preferredPath, candidate.absolutePath)
                    return candidate.absolutePath
                }
            }
        }

        return null
    }

    private fun List<ModelEntity>.prioritize(preferredFilename: String?): List<ModelEntity> {
        return sortedWith(
            compareByDescending<ModelEntity> { model ->
                preferredFilename != null && model.filename == preferredFilename
            }.thenByDescending { model ->
                preferredFilename != null && model.path.substringAfterLast("/") == preferredFilename
            }.thenBy { it.filename }
        )
    }

    private fun candidateRoots(context: Context): List<File> {
        return buildList {
            context.getExternalFilesDir(null)?.let { external ->
                add(File(external, "models/whisper"))
            }
            add(File(context.filesDir, "whisper_models"))
            add(File(context.filesDir, "models/whisper"))
        }
    }

    private fun logRepair(oldPath: String?, repairedPath: String) {
        if (!oldPath.isNullOrBlank() && oldPath != repairedPath) {
            DebugLog.log("WhisperModelPathResolver: Repaired Whisper model path from $oldPath to $repairedPath")
        }
    }

    private fun File.isReadableFile(): Boolean =
        isFile && runCatching { inputStream().use { true } }.getOrDefault(false)
}
