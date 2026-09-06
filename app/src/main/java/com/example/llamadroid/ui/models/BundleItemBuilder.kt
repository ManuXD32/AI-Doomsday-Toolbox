package com.example.llamadroid.ui.models

import com.example.llamadroid.data.db.ModelBundleItemEntity
import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.ModelSourceEntity
import com.example.llamadroid.data.model.library.InstalledModelAsset
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.data.model.library.isCompatibleSourceFamily
import com.example.llamadroid.data.model.library.resolvedDownloadUrl
import java.io.File

/** A directory member prepared by the editor's background scan. */
internal data class BundleDirectoryMember(
    val relativePath: String,
    val localPath: String? = null
)

/** A split member prepared by the editor's background scan. */
internal data class BundleSplitMember(
    val filePath: String,
    val relativePath: String,
    val partGroup: String,
    val partIndex: Int,
    val partCount: Int
)

internal enum class BundleItemBuildConflictCode {
    DUPLICATE_ITEM_KEY,
    DUPLICATE_RELATIVE_PATH,
    AMBIGUOUS_SOURCE_TARGET,
    SOURCE_PATH_COLLISION,
    INCOMPATIBLE_SOURCE_FAMILY,
    SOURCE_ROLE_REQUIRED,
    INVALID_SOURCE_PATH
}

internal data class BundleItemBuildConflict(
    val code: BundleItemBuildConflictCode,
    val sourceId: String? = null,
    val relativePath: String? = null,
    val candidateCount: Int = 0
)

internal data class BundleItemBuildResult(
    val items: List<ModelBundleItemEntity>,
    val conflicts: List<BundleItemBuildConflict>
) {
    val isValid: Boolean get() = conflicts.isEmpty()
}

/** Inputs are already scanned/prepared by the composable; this builder performs no filesystem I/O. */
internal data class BundleItemBuilderInput(
    val bundleId: String,
    val family: ModelFamily,
    val existingItems: List<ModelBundleItemEntity>,
    val selectedModelKeys: Set<String>,
    val selectedSourceIds: Set<String>,
    val sourceRoles: Map<String, String?> = emptyMap(),
    /** Optional exact bundle-relative destination/member path keyed by source ID. */
    val sourceRelativePaths: Map<String, String?> = emptyMap(),
    val defaultRole: String? = null,
    val installedAssets: List<InstalledModelAsset>,
    val availableSources: List<ModelSourceEntity>,
    val provenance: List<ModelProvenanceEntity>,
    val directoryRoots: Map<String, String> = emptyMap(),
    val directoryMembers: Map<String, List<BundleDirectoryMember>> = emptyMap(),
    val splitBundleMembers: Map<String, List<BundleSplitMember>> = emptyMap()
)

private data class Candidate(
    var item: ModelBundleItemEntity,
    val assetId: String?,
    val localPath: String?,
    val memberPath: String?
)

/**
 * Builds durable bundle items while retaining identity between installed
 * assets, directory members, saved sources, and existing drafts.
 *
 * A source is attached by exact source/provenance identity first. A relative
 * path suffix is only a fallback when it resolves to one candidate; an
 * ambiguous suffix becomes a visible conflict instead of overwriting a prior
 * member. Existing selected items are retained even when their source row or
 * runtime file is currently unavailable.
 */
internal fun buildBundleItems(input: BundleItemBuilderInput): BundleItemBuildResult {
    val existingByKey = input.existingItems.associateBy { it.itemKey }
    val candidates = mutableListOf<Candidate>()
    val conflicts = mutableListOf<BundleItemBuildConflict>()

    fun normalizedPath(raw: String?): String? = raw
        ?.trim()
        ?.replace('\\', '/')
        ?.trim('/')
        ?.split('/')
        ?.filter { it.isNotBlank() && it != "." && it != ".." }
        ?.joinToString("/")
        ?.takeIf { it.isNotBlank() }

    /**
     * Validates a user-supplied bundle destination before it can participate in matching.
     * Internal paths still use [normalizedPath] because they come from scanned/runtime data;
     * explicit editor paths must never silently discard traversal or absolute-path segments.
     */
    fun validatedExplicitRelativePath(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || raw.any { Character.isISOControl(it) }) return null
        val slashPath = value.replace('\\', '/')
        if (slashPath.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(slashPath)) return null
        val segments = slashPath.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return segments.joinToString("/").takeIf { it.isNotBlank() }
    }

    fun normalizedLocalPath(raw: String?): String? = raw
        ?.trim()
        ?.replace('\\', '/')
        ?.trimEnd('/')
        ?.takeIf { it.isNotBlank() }

    fun safeSegment(raw: String): String = raw
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_')
        .ifBlank { "package" }
        .take(96)

    fun liteRtPackagePrefix(asset: InstalledModelAsset): String =
        "package-${safeSegment(asset.stableId)}"

    fun existingLiteRtPrefix(asset: InstalledModelAsset, member: String): String? {
        val suffix = normalizedPath(member) ?: return null
        return input.existingItems.asSequence()
            .filter { item ->
                item.itemKey == "${asset.stableId}:$member" ||
                    item.itemKey.startsWith("${asset.stableId}:")
            }
            .mapNotNull { item -> normalizedPath(item.relativePath) }
            .mapNotNull { path ->
                when {
                    path == suffix -> ""
                    path.endsWith("/$suffix") -> path.removeSuffix("/$suffix").trim('/')
                    else -> null
                }
            }
            .firstOrNull()
    }

    fun sourceForExactLocalPath(sourceId: String, localPath: String?): Boolean {
        val target = normalizedLocalPath(localPath) ?: return false
        return input.provenance.any { edge ->
            edge.sourceId == sourceId && normalizedLocalPath(edge.localPath) == target
        }
    }

    fun linkedSourceForAsset(asset: InstalledModelAsset, isDirectory: Boolean): String? {
        val assetPath = normalizedLocalPath(asset.path)
        return input.provenance.asSequence()
            .filter { it.modelKey == asset.stableId }
            .filter { edge ->
                // A directory has several edges sharing one runtime key. Only
                // an exact root edge can be used as its primary source.
                !isDirectory || normalizedLocalPath(edge.localPath) == assetPath
            }
            .sortedWith(compareByDescending<ModelProvenanceEntity> {
                normalizedLocalPath(it.localPath) == assetPath
            }.thenByDescending { it.updatedAt })
            .map { it.sourceId }
            .firstOrNull()
    }

    fun sourceRole(sourceId: String, item: ModelBundleItemEntity? = null): String? {
        // Presence in the editor map is intentional, including an empty
        // value: clearing a previously inferred role must be able to surface
        // SOURCE_ROLE_REQUIRED instead of silently restoring the old role.
        if (input.sourceRoles.containsKey(sourceId)) {
            return input.sourceRoles[sourceId]?.trim()?.takeIf { it.isNotBlank() }
        }
        item?.role?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        input.existingItems.firstOrNull { it.sourceId == sourceId }
            ?.role?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        input.provenance.filter { it.sourceId == sourceId }
            .mapNotNull { it.role?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .singleOrNull()
            ?.let { return it }
        return input.defaultRole?.trim()?.takeIf { it.isNotBlank() }
    }

    fun addCandidate(
        item: ModelBundleItemEntity,
        assetId: String?,
        localPath: String?,
        memberPath: String?
    ) {
        candidates += Candidate(item, assetId, localPath, memberPath)
    }

    input.installedAssets
        .filter { it.stableId in input.selectedModelKeys }
        .forEach { asset ->
            val members = input.directoryMembers[asset.stableId].orEmpty()
            val linkedSource = linkedSourceForAsset(asset, members.isNotEmpty())
            if (members.isNotEmpty()) {
                val group = "dir:${asset.stableId}".takeIf { members.size > 1 }
                val packagePrefix = if (asset.isLiteRt) null else normalizedPath(asset.filename)
                members.forEachIndexed { index, member ->
                    val memberRelative = normalizedPath(member.relativePath) ?: return@forEachIndexed
                    val legacyPrefix = if (asset.isLiteRt) existingLiteRtPrefix(asset, memberRelative) else null
                    val prefix = if (asset.isLiteRt) {
                        legacyPrefix ?: liteRtPackagePrefix(asset)
                    } else {
                        packagePrefix
                    }
                    val relativePath = listOfNotNull(prefix?.takeIf { it.isNotBlank() }, memberRelative)
                        .joinToString("/")
                    val itemKey = "${asset.stableId}:${member.relativePath}"
                    val previous = existingByKey[itemKey]
                        ?: input.existingItems.firstOrNull { it.partGroup == group && it.partIndex == index }
                    val localPath = member.localPath ?: run {
                        val root = input.directoryRoots[asset.stableId]
                        root?.let { File(it, member.relativePath).absolutePath }
                    }
                    val memberSource = input.provenance.firstOrNull { edge ->
                        sourceForExactLocalPath(edge.sourceId, localPath)
                    }?.sourceId
                    val item = (previous ?: ModelBundleItemEntity(
                        bundleId = input.bundleId,
                        itemKey = itemKey,
                        family = input.family.storedValue,
                        role = asset.role.takeIf { index == 0 },
                        sourceId = memberSource ?: if (index == 0) linkedSource else null,
                        required = true,
                        partGroup = group,
                        partIndex = index.takeIf { group != null },
                        partCount = members.size.takeIf { group != null },
                        localFilename = memberRelative.substringAfterLast('/'),
                        relativePath = relativePath,
                        modelMetadataJson = asset.metadataJson
                    )).copy(
                        bundleId = input.bundleId,
                        family = input.family.storedValue,
                        // Existing role/source/path values are durable user
                        // choices; only fill them when an item is new.
                        sourceId = previous?.sourceId ?: memberSource ?: if (index == 0) linkedSource else null,
                        role = previous?.role ?: asset.role.takeIf { index == 0 },
                        partGroup = group,
                        partIndex = index.takeIf { group != null },
                        partCount = members.size.takeIf { group != null },
                        localFilename = previous?.localFilename ?: memberRelative.substringAfterLast('/'),
                        relativePath = previous?.relativePath ?: relativePath,
                        modelMetadataJson = previous?.modelMetadataJson?.takeIf { it != "{}" }
                            ?: asset.metadataJson
                    )
                    addCandidate(item, asset.stableId, localPath, memberRelative)
                }
            } else {
                val relativePath = normalizedPath(asset.filename) ?: asset.filename
                val previous = existingByKey[asset.stableId]
                val splitParts = input.splitBundleMembers[asset.stableId].orEmpty()
                if (splitParts.isNotEmpty()) {
                    val assetPath = normalizedLocalPath(asset.path)
                    splitParts.forEach { part ->
                        val partPath = normalizedPath(part.relativePath) ?: return@forEach
                        val selectedPrimary = assetPath == normalizedLocalPath(part.filePath)
                        val itemKey = if (selectedPrimary) asset.stableId else "${asset.stableId}:$partPath"
                        val previousPart = existingByKey[itemKey]
                            ?: input.existingItems.firstOrNull {
                                it.partGroup == part.partGroup && it.partIndex == part.partIndex
                            }
                        val memberSource = input.provenance.firstOrNull {
                            it.sourceId.isNotBlank() && normalizedLocalPath(it.localPath) == normalizedLocalPath(part.filePath)
                        }?.sourceId
                        val item = (previousPart ?: ModelBundleItemEntity(
                            bundleId = input.bundleId,
                            itemKey = itemKey,
                            family = input.family.storedValue,
                            role = asset.role.takeIf { part.partIndex == 0 },
                            sourceId = memberSource ?: if (selectedPrimary) linkedSource else null,
                            required = true,
                            partGroup = part.partGroup,
                            partIndex = part.partIndex,
                            partCount = part.partCount,
                            localFilename = partPath.substringAfterLast('/'),
                            relativePath = partPath,
                            modelMetadataJson = asset.metadataJson
                        )).copy(
                            bundleId = input.bundleId,
                            family = input.family.storedValue,
                            sourceId = previousPart?.sourceId ?: memberSource ?: if (selectedPrimary) linkedSource else null,
                            role = previousPart?.role ?: asset.role.takeIf { part.partIndex == 0 },
                            partGroup = part.partGroup,
                            partIndex = part.partIndex,
                            partCount = part.partCount,
                            localFilename = previousPart?.localFilename ?: partPath.substringAfterLast('/'),
                            relativePath = previousPart?.relativePath ?: partPath,
                            modelMetadataJson = previousPart?.modelMetadataJson?.takeIf { it != "{}" }
                                ?: asset.metadataJson
                        )
                        addCandidate(item, asset.stableId, part.filePath, partPath)
                    }
                } else {
                    val multipart = splitBundlePartForBuilder(relativePath)
                    val item = (previous ?: ModelBundleItemEntity(
                        bundleId = input.bundleId,
                        itemKey = asset.stableId,
                        family = input.family.storedValue,
                        role = asset.role,
                        sourceId = linkedSource,
                        required = true,
                        localFilename = asset.filename,
                        relativePath = relativePath,
                        partGroup = multipart?.group,
                        partIndex = multipart?.index,
                        partCount = multipart?.count,
                        modelMetadataJson = asset.metadataJson
                    )).copy(
                        bundleId = input.bundleId,
                        family = input.family.storedValue,
                        sourceId = previous?.sourceId ?: linkedSource,
                        role = previous?.role ?: asset.role,
                        modelMetadataJson = previous?.modelMetadataJson?.takeIf { it != "{}" }
                            ?: asset.metadataJson
                    )
                    addCandidate(item, asset.stableId, asset.path, null)
                }
            }
        }

    fun matchingCandidates(source: ModelSourceEntity, exactPath: String? = null): List<Candidate> {
        val sourcePath = normalizedPath(exactPath ?: source.filePath ?: source.label) ?: return emptyList()
        return candidates.filter { candidate ->
            val candidatePath = normalizedPath(candidate.item.relativePath) ?: return@filter false
            if (exactPath != null) {
                candidatePath == sourcePath
            } else {
                candidatePath == sourcePath || candidatePath.endsWith("/$sourcePath")
            }
        }
    }

    input.availableSources
        .filter { it.id in input.selectedSourceIds && it.resolvedDownloadUrl() != null }
        .forEach { source ->
            val sourceFamily = ModelFamily.fromStoredValue(source.family)
            val rawExplicitRelativePath = input.sourceRelativePaths[source.id]
                ?.takeIf { it.isNotBlank() || it.any { char -> Character.isISOControl(char) } }
            val explicitRelativePath = if (rawExplicitRelativePath == null) {
                null
            } else {
                validatedExplicitRelativePath(rawExplicitRelativePath) ?: run {
                    conflicts += BundleItemBuildConflict(
                        code = BundleItemBuildConflictCode.INVALID_SOURCE_PATH,
                        sourceId = source.id
                    )
                    return@forEach
                }
            }
            val existingAssociation = candidates.filter { it.item.sourceId == source.id }
            if (existingAssociation.isNotEmpty() && explicitRelativePath == null) {
                val explicitRole = if (input.sourceRoles.containsKey(source.id)) {
                    input.sourceRoles[source.id]?.trim()?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                val effectiveRole: (Candidate) -> String? = { candidate ->
                    if (input.sourceRoles.containsKey(source.id)) explicitRole else candidate.item.role
                }
                if (sourceFamily != null && sourceFamily != input.family &&
                    existingAssociation.any { effectiveRole(it) == null }
                ) {
                    conflicts += BundleItemBuildConflict(
                        code = BundleItemBuildConflictCode.SOURCE_ROLE_REQUIRED,
                        sourceId = source.id
                    )
                    return@forEach
                }
                if (sourceFamily != null && sourceFamily != input.family &&
                    existingAssociation.any { !isCompatibleSourceFamily(sourceFamily, input.family, effectiveRole(it)) }
                ) {
                    conflicts += BundleItemBuildConflict(
                        code = BundleItemBuildConflictCode.INCOMPATIBLE_SOURCE_FAMILY,
                        sourceId = source.id
                    )
                    return@forEach
                }
                if (input.sourceRoles.containsKey(source.id)) {
                    candidates.indices
                            .filter { index -> candidates[index].item.sourceId == source.id }
                            .forEach { index ->
                                candidates[index].item = candidates[index].item.copy(role = explicitRole)
                            }
                }
                // One saved direct link may intentionally be reused for more
                // than one generated item. Never collapse those edges.
                return@forEach
            }

            // An explicit path is a user-directed remap. Detach the old inferred association
            // before looking for the requested destination; the old item itself remains selected
            // and can be rebuilt as a source-only draft if the new destination is not installed.
            if (explicitRelativePath != null) {
                existingAssociation
                    .filter { normalizedPath(it.item.relativePath) != explicitRelativePath }
                    .forEach { candidate -> candidate.item = candidate.item.copy(sourceId = null) }
            }

            val sourceEdges = input.provenance.filter { it.sourceId == source.id }
            val edgeCandidates = if (explicitRelativePath == null) {
                candidates.filter { candidate ->
                    sourceEdges.any { edge ->
                        normalizedLocalPath(edge.localPath) != null &&
                            normalizedLocalPath(candidate.localPath) == normalizedLocalPath(edge.localPath)
                    }
                }
            } else {
                emptyList()
            }
            // Provenance is the authoritative member identity. Once it maps
            // this source to one or more exact local files, do not union a
            // basename suffix search that would make a valid nested mapping
            // look ambiguous.
            val pathCandidates = if (explicitRelativePath != null) {
                matchingCandidates(source, exactPath = explicitRelativePath)
            } else if (edgeCandidates.isEmpty()) {
                matchingCandidates(source)
            } else {
                emptyList()
            }
            val candidatesByIdentity = (edgeCandidates + pathCandidates).distinctBy { it.item.itemKey }
            val role = sourceRole(source.id, candidatesByIdentity.singleOrNull()?.item)

            if (sourceFamily != null && sourceFamily != input.family && role == null) {
                conflicts += BundleItemBuildConflict(
                    code = BundleItemBuildConflictCode.SOURCE_ROLE_REQUIRED,
                    sourceId = source.id
                )
                return@forEach
            }
            if (sourceFamily != null && !isCompatibleSourceFamily(sourceFamily, input.family, role)) {
                conflicts += BundleItemBuildConflict(
                    code = BundleItemBuildConflictCode.INCOMPATIBLE_SOURCE_FAMILY,
                    sourceId = source.id
                )
                return@forEach
            }

            when {
                candidatesByIdentity.size > 1 -> {
                    conflicts += BundleItemBuildConflict(
                        code = BundleItemBuildConflictCode.AMBIGUOUS_SOURCE_TARGET,
                        sourceId = source.id,
                        relativePath = explicitRelativePath
                            ?: normalizedPath(source.filePath ?: source.label),
                        candidateCount = candidatesByIdentity.size
                    )
                }
                candidatesByIdentity.size == 1 -> {
                    val candidate = candidatesByIdentity.single()
                    if (candidate.item.sourceId != null && candidate.item.sourceId != source.id) {
                        conflicts += BundleItemBuildConflict(
                            code = BundleItemBuildConflictCode.SOURCE_PATH_COLLISION,
                            sourceId = source.id,
                            relativePath = candidate.item.relativePath
                        )
                    } else {
                        candidate.item = candidate.item.copy(
                            sourceId = source.id,
                            role = role ?: candidate.item.role
                        )
                    }
                }
                else -> {
                    val existing = input.existingItems.firstOrNull { it.sourceId == source.id }
                    val relativePath = explicitRelativePath ?: normalizedPath(
                        existing?.relativePath ?: source.filePath ?: source.label
                    )
                    if (relativePath == null) return@forEach
                    val localFilename = if (explicitRelativePath != null) {
                        relativePath.substringAfterLast('/')
                    } else {
                        existing?.localFilename ?: relativePath.substringAfterLast('/')
                    }
                    val existingPath = candidates.firstOrNull {
                        normalizedPath(it.item.relativePath) == relativePath
                    }
                    if (existingPath != null) {
                        conflicts += BundleItemBuildConflict(
                            code = BundleItemBuildConflictCode.SOURCE_PATH_COLLISION,
                            sourceId = source.id,
                            relativePath = relativePath
                        )
                    } else {
                        val item = (existing ?: ModelBundleItemEntity(
                            bundleId = input.bundleId,
                            itemKey = "source:${source.id}",
                            family = input.family.storedValue,
                            role = role,
                            sourceId = source.id,
                            required = true,
                            localFilename = localFilename,
                            relativePath = relativePath
                        )).copy(
                            bundleId = input.bundleId,
                            family = input.family.storedValue,
                            sourceId = source.id,
                            role = role ?: existing?.role,
                            localFilename = localFilename,
                            // Recompute shard metadata from the selected destination. This fixes
                            // source-only GGUF shards created before they had a split relationship,
                            // while preserving custom metadata when no explicit destination was
                            // edited.
                            partGroup = splitBundlePartForBuilder(relativePath)?.group
                                ?: existing?.partGroup?.takeIf { explicitRelativePath == null },
                            partIndex = splitBundlePartForBuilder(relativePath)?.index
                                ?: existing?.partIndex?.takeIf { explicitRelativePath == null },
                            partCount = splitBundlePartForBuilder(relativePath)?.count
                                ?: existing?.partCount?.takeIf { explicitRelativePath == null },
                            relativePath = if (explicitRelativePath == null) {
                                existing?.relativePath ?: relativePath
                            } else {
                                relativePath
                            }
                        )
                        addCandidate(item, null, null, null)
                    }
                }
            }
        }

    // Keep selected old rows which are not currently represented by an
    // installed asset or an available direct source. This is what preserves a
    // Needs-source draft after a source deletion or repository-only link.
    input.existingItems
        .filter { it.itemKey in input.selectedModelKeys }
        .filter { old -> candidates.none { it.item.itemKey == old.itemKey } }
        .forEach { old ->
            addCandidate(
                old.copy(bundleId = input.bundleId, family = input.family.storedValue),
                assetId = null,
                localPath = null,
                memberPath = null
            )
        }

    val duplicateKeys = candidates.groupBy { it.item.itemKey }.filterValues { it.size > 1 }
    duplicateKeys.keys.forEach { key ->
        conflicts += BundleItemBuildConflict(BundleItemBuildConflictCode.DUPLICATE_ITEM_KEY)
    }
    candidates.groupBy { normalizedPath(it.item.relativePath) }.filterKeys { it != null }
        .filterValues { it.size > 1 }
        .forEach { (path, entries) ->
            conflicts += BundleItemBuildConflict(
                code = BundleItemBuildConflictCode.DUPLICATE_RELATIVE_PATH,
                relativePath = path,
                candidateCount = entries.size
            )
        }

    return BundleItemBuildResult(
        items = candidates.map { it.item },
        conflicts = conflicts.distinct()
    )
}

private data class BuilderBundlePart(val group: String, val index: Int, val count: Int)

private fun splitBundlePartForBuilder(relativePath: String): BuilderBundlePart? {
    val filename = relativePath.substringAfterLast('/')
    val match = Regex("^(.*?)[-_](\\d{1,6})-of-(\\d{1,6})(\\.[^/]*)?$").matchEntire(filename)
        ?: return null
    val index = match.groupValues[2].toIntOrNull()?.minus(1) ?: return null
    val count = match.groupValues[3].toIntOrNull() ?: return null
    if (count <= 0 || index !in 0 until count) return null
    val parent = relativePath.substringBeforeLast('/', "")
    val extension = match.groupValues[4]
    val groupName = match.groupValues[1] + extension
    return BuilderBundlePart(if (parent.isBlank()) groupName else "$parent/$groupName", index, count)
}
