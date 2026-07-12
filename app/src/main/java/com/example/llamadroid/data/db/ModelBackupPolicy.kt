package com.example.llamadroid.data.db

object ModelBackupPolicy {
    const val LOCAL_IMPORT_REPO_ID = "local-import"
    const val CUSTOM_IMPORT_REPO_PREFIX = "custom-import/"
    const val ONNX_CUSTOM_IMPORT_ASSET_KIND = "custom_import_bundle"

    const val IMPORTED_MODEL_SQL_PREDICATE = "1 = 0"

    fun shouldKeepInPortableBackup(@Suppress("UNUSED_PARAMETER") model: ModelEntity): Boolean = false
}
