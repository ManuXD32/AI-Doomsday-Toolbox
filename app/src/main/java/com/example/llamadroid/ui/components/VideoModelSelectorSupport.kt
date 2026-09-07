package com.example.llamadroid.ui.components

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.SdVideoFamilyProfile
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.sd.pathFor

/**
 * The visual model picker uses filenames as its compact identity while keeping
 * the absolute path as the persisted value. Paths are exposed only when the
 * picker needs to disambiguate duplicate filenames or in the custom editor.
 */
internal data class VideoModelSelectorEntry(
    val model: ModelEntity,
    val hasDuplicateFilename: Boolean
)

internal fun videoModelSelectorEntries(
    models: List<ModelEntity>
): List<VideoModelSelectorEntry> {
    val filenameCounts = models
        .groupingBy { it.filename }
        .eachCount()
    return models.map { model ->
        VideoModelSelectorEntry(
            model = model,
            hasDuplicateFilename = filenameCounts[model.filename].orZero() > 1
        )
    }
}

internal fun selectedVideoModel(
    path: String,
    models: List<ModelEntity>
): ModelEntity? = models.firstOrNull { it.path == path }

internal fun videoModelDisplayName(
    path: String,
    models: List<ModelEntity>
): String = selectedVideoModel(path, models)?.filename
    ?: path.substringAfterLast('/').ifBlank { path }

/**
 * Required component sets are alternatives when they contain more than one
 * role.  Workflow-specific groups are appended to the profile groups so the
 * UI mirrors the same contract used by launch validation.
 */
internal enum class VideoComponentRequirementKind {
    UNKNOWN,
    REQUIRED,
    CHOOSE_ONE,
    OPTIONAL,
    INCOMPATIBLE
}

internal data class VideoComponentRequirementGroup(
    val kind: VideoComponentRequirementKind,
    val roles: List<SdVideoComponentRole>
)

internal fun videoComponentRequirementGroups(
    profile: SdVideoFamilyProfile?,
    workflow: SdVideoWorkflow,
    paths: SdVideoComponentPaths
): List<VideoComponentRequirementGroup> {
    if (profile == null) {
        return listOf(
            VideoComponentRequirementGroup(
                kind = VideoComponentRequirementKind.UNKNOWN,
                roles = orderedVideoComponentRoles(SdVideoComponentRole.entries.toSet())
            )
        )
    }

    val profileGroups = profile.requiredComponentGroups +
        profile.workflowRequiredComponentGroups[workflow].orEmpty()
    val requiredRoles = profileGroups.flatten().toSet()

    val groups = profileGroups.mapNotNull { roles ->
        val orderedRoles = orderedVideoComponentRoles(roles)
        if (orderedRoles.isEmpty()) {
            null
        } else {
            VideoComponentRequirementGroup(
                kind = if (orderedRoles.size == 1) {
                    VideoComponentRequirementKind.REQUIRED
                } else {
                    VideoComponentRequirementKind.CHOOSE_ONE
                },
                roles = orderedRoles
            )
        }
    }.toMutableList()

    val optionalRoles = orderedVideoComponentRoles(profile.optionalComponents - requiredRoles)
    val supportedRoles = requiredRoles + profile.optionalComponents
    if (optionalRoles.isNotEmpty()) {
        groups += VideoComponentRequirementGroup(
            kind = VideoComponentRequirementKind.OPTIONAL,
            roles = optionalRoles
        )
    }

    val incompatibleRoles = orderedVideoComponentRoles(
        SdVideoComponentRole.entries.toSet() - SdVideoComponentRole.LORA
    ).filter { role ->
        !paths.pathFor(role).isNullOrBlank() && role !in supportedRoles
    }
    if (incompatibleRoles.isNotEmpty()) {
        groups += VideoComponentRequirementGroup(
            kind = VideoComponentRequirementKind.INCOMPATIBLE,
            roles = incompatibleRoles
        )
    }
    return groups
}

private fun orderedVideoComponentRoles(
    roles: Set<SdVideoComponentRole>
): List<SdVideoComponentRole> = SdVideoComponentRole.entries.filter {
    it != SdVideoComponentRole.LORA && it in roles
}

private fun Int?.orZero(): Int = this ?: 0

/** File checks belong to the picker IO producer, never the composition pass. */
internal fun videoModelFileAvailable(path: String): Boolean =
    path.isNotBlank() && java.io.File(path).let { it.exists() && it.canRead() }
