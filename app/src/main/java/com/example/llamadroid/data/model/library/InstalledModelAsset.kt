package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.PortableModelMetadata

/**
 * A typed installed asset exposed to the model-library editor.
 *
 * LiteRT models intentionally stay backed by [LiteRtModelEntity]. They do not
 * get represented as a synthetic [ModelType] row, because LiteRT has its own
 * runtime table and capability metadata. [stableId] is suitable for bundle
 * item keys and remains distinct from the existing filename primary keys.
 */
data class InstalledModelAsset(
    val stableId: String,
    val displayName: String,
    val path: String,
    val filename: String,
    val family: ModelFamily,
    val role: String?,
    val metadataJson: String,
    val model: ModelEntity? = null,
    val liteRt: LiteRtModelEntity? = null
) {
    init {
        require(stableId.isNotBlank()) { "Installed model asset id cannot be blank" }
        require(path.isNotBlank()) { "Installed model asset path cannot be blank" }
        require((model == null) != (liteRt == null)) {
            "An installed model asset must wrap exactly one runtime entity"
        }
    }

    val isLiteRt: Boolean
        get() = liteRt != null

    companion object {
        fun fromModel(
            model: ModelEntity,
            family: ModelFamily,
            role: String?
        ): InstalledModelAsset = InstalledModelAsset(
            stableId = model.filename,
            displayName = model.filename,
            path = model.path,
            filename = model.filename,
            family = family,
            role = role,
            metadataJson = PortableModelMetadata.fromModel(model),
            model = model
        )

        fun fromLiteRt(model: LiteRtModelEntity): InstalledModelAsset = InstalledModelAsset(
            // Room assigns a positive id to persisted rows. The path fallback
            // keeps fixture/import rows distinct before insertion as well.
            stableId = "litert:${model.id.takeIf { it > 0L } ?: model.path.hashCode()}",
            displayName = model.displayName.ifBlank { model.filename },
            path = model.path,
            filename = model.filename,
            family = ModelFamily.LITERT,
            role = "litert",
            metadataJson = PortableModelMetadata.fromLiteRt(model),
            liteRt = model
        )
    }
}
