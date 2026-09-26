package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.llamadroid.data.model.AudioCuratedBundleCatalog
import com.example.llamadroid.data.model.CuratedBundleFile
import com.example.llamadroid.data.model.VideoRecognitionBundleCatalog
import com.example.llamadroid.service.WhisperModel
import com.example.llamadroid.util.DebugLog

/**
 * Additive Agent wake persistence and one-time media metadata repair.
 *
 * The repair deliberately uses the pinned catalogs and the persisted source
 * identity (repository, basename and, for strict catalog entries, byte size).
 * It must never infer a capability from a loose filename fragment, because
 * arbitrary model rows are user data and must remain untouched during a schema
 * upgrade.
 */
object AgentSleepWakeMigration {
    private data class CatalogArtifact(
        val file: CuratedBundleFile,
        val installedNames: Set<String>
    )

    private val knownVideoArtifacts: List<CatalogArtifact> by lazy {
        VideoRecognitionBundleCatalog.bundles
            .flatMap { bundle ->
                bundle.files.map { file ->
                    CatalogArtifact(
                        file = file,
                        installedNames = setOf(
                            file.localFilename,
                            file.installedFilename(bundle.defaultPrefix)
                        )
                    )
                }
            }
            .distinctBy { it.file.repoId to it.file.localFilename }
    }

    private val knownSpeechArtifacts: List<CatalogArtifact> by lazy {
        AudioCuratedBundleCatalog.bundles
            .flatMap { bundle ->
                bundle.files.map { file ->
                    CatalogArtifact(
                        file = file,
                        installedNames = setOf(
                            file.localFilename,
                            file.installedFilename(bundle.defaultPrefix)
                        )
                    )
                }
            }
            .distinctBy { it.file.repoId to it.file.localFilename }
    }

    /** Whisper assets that are part of the built-in speech model picker. */
    private val knownWhisperArtifacts: Map<String, Set<String>> = buildMap {
        put(
            "ggerganov/whisper.cpp",
            WhisperModel.values()
                .filterNot { it.modelName.contains("tdrz", ignoreCase = true) }
                .map { it.filename }
                .toSet()
        )
        put(
            "akashmjn/tinydiarize-whisper.cpp",
            WhisperModel.values()
                .filter { it.modelName.contains("tdrz", ignoreCase = true) }
                .map { it.filename }
                .toSet()
        )
    }

    val MIGRATION_117_118 = object : Migration(117, 118) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_sleep_wakes` (
                    `id` TEXT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `rootTurnId` TEXT,
                    `runEpoch` INTEGER NOT NULL,
                    `wakeAtEpochMs` INTEGER NOT NULL,
                    `reason` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `approximate` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `firedAt` INTEGER,
                    `completedAt` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`conversationId`) REFERENCES `agent_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_sleep_wakes_conversationId` ON `agent_sleep_wakes` (`conversationId`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_sleep_wakes_status` ON `agent_sleep_wakes` (`status`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_sleep_wakes_wakeAtEpochMs` ON `agent_sleep_wakes` (`wakeAtEpochMs`)"
            )
            repairKnownMediaMetadata(database)
            DebugLog.log("[DB] Migration 117 -> 118 complete")
        }
    }

    private fun repairKnownMediaMetadata(database: SupportSQLiteDatabase) {
        if (Migrations.tableExists(database, "models")) {
            repairKnownVideoRows(database)
            repairKnownSpeechRows(database)
            repairKnownWhisperRows(database)
        }
        if (Migrations.tableExists(database, "llama_servers")) {
            repairKnownVideoServerRows(database)
        }
    }

    private fun repairKnownVideoRows(database: SupportSQLiteDatabase) {
        if (!Migrations.columnExists(database, "models", "isVision")) return
        val predicates = knownVideoArtifacts.joinToString(" OR ") { artifactPredicate(it) }
        if (predicates.isBlank()) return
        database.execSQL(
            """
            UPDATE `models`
            SET `isVision` = 1
            WHERE `isDownloaded` = 1
              AND ($predicates)
            """.trimIndent()
        )
    }

    private fun repairKnownSpeechRows(database: SupportSQLiteDatabase) {
        val requiredColumns = setOf("type", "isVision", "audioFamily", "audioLanguage", "audioComponentRole", "audioArtifactIdentity")
        if (requiredColumns.any { !Migrations.columnExists(database, "models", it) }) return

        knownSpeechArtifacts.forEach { artifact ->
            val file = artifact.file
            val family = file.audioFamily ?: return@forEach
            val role = file.componentRole ?: return@forEach
            val languageSql = file.audioLanguage?.let(::sqlString) ?: "NULL"
            database.execSQL(
                """
                UPDATE `models`
                SET `type` = ${sqlString(file.type.name)},
                    `isVision` = 0,
                    `audioFamily` = ${sqlString(family)},
                    `audioLanguage` = $languageSql,
                    `audioComponentRole` = ${sqlString(role)},
                    `audioArtifactIdentity` = ${sqlString(file.artifactIdentity)}
                WHERE `isDownloaded` = 1
                  AND `repoId` = ${sqlString(file.repoId)}
                  AND `sizeBytes` = ${file.sizeBytes}
                  AND (${filenamePredicate(artifact.installedNames, file.localFilename)})
                """.trimIndent()
            )
        }
    }

    private fun repairKnownWhisperRows(database: SupportSQLiteDatabase) {
        if (!Migrations.columnExists(database, "models", "type") ||
            !Migrations.columnExists(database, "models", "isVision")
        ) return
        knownWhisperArtifacts.forEach { (repoId, filenames) ->
            if (filenames.isEmpty()) return@forEach
            database.execSQL(
                """
                UPDATE `models`
                SET `type` = 'WHISPER',
                    `isVision` = 0
                WHERE `isDownloaded` = 1
                  AND `repoId` = ${sqlString(repoId)}
                  AND `filename` IN (${filenames.joinToString(",", transform = ::sqlString)})
                """.trimIndent()
            )
        }
    }

    private fun repairKnownVideoServerRows(database: SupportSQLiteDatabase) {
        if (!Migrations.columnExists(database, "llama_servers", "supportsVideo") ||
            !Migrations.columnExists(database, "llama_servers", "supportsVision") ||
            !Migrations.columnExists(database, "llama_servers", "modelName") ||
            !Migrations.columnExists(database, "llama_servers", "engine")
        ) return

        val videoModels = knownVideoArtifacts.filter { it.file.type == ModelType.LLM }
        if (videoModels.isEmpty()) return
        val modelNamePredicates = videoModels.joinToString(" OR ") { artifact ->
            filenamePredicate("modelName", artifact.installedNames, artifact.file.localFilename)
        }
        val installedModelPredicates = videoModels.joinToString(" OR ") { artifact ->
            artifactPredicate(artifact)
        }
        database.execSQL(
            """
            UPDATE `llama_servers`
            SET `supportsVision` = 1,
                `supportsVideo` = 1
            WHERE LOWER(TRIM(`engine`)) IN ('llama-server', 'llama-swap')
              AND ($modelNamePredicates)
              AND EXISTS (
                  SELECT 1
                  FROM `models` AS `known_video_model`
                  WHERE `known_video_model`.`isDownloaded` = 1
                    AND ($installedModelPredicates)
              )
            """.trimIndent()
        )
    }

    private fun artifactPredicate(artifact: CatalogArtifact): String {
        val file = artifact.file
        return "(`repoId` = ${sqlString(file.repoId)} AND `sizeBytes` = ${file.sizeBytes} AND " +
            "(${filenamePredicate(artifact.installedNames, file.localFilename)}))"
    }

    private fun filenamePredicate(
        column: String,
        names: Set<String>,
        localFilename: String
    ): String {
        val resolvedColumn = "`$column`"
        val exactNames = names.joinToString(" OR ") { "$resolvedColumn = ${sqlString(it)}" }
        // Use substr instead of LIKE: curated names contain underscores, and
        // treating them as LIKE wildcards would broaden the repair predicate.
        val suffix = "substr($resolvedColumn, -${localFilename.length + 1}) = ${sqlString("-$localFilename")}"
        return "($exactNames OR $suffix)"
    }

    private fun filenamePredicate(
        names: Set<String>,
        localFilename: String
    ): String = filenamePredicate("filename", names, localFilename)

    private fun sqlString(value: String): String =
        "'${value.replace("'", "''")}'"
}
