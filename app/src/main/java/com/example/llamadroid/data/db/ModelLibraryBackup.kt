package com.example.llamadroid.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.data.model.library.ModelSourceDraft
import com.example.llamadroid.data.model.library.ModelSourceUrlValidator
import com.example.llamadroid.data.model.PortableModelMetadata

/** Only called on the export copy or restored database, never on the running source DB. */
internal fun sanitizePortableModelLibrary(db: SQLiteDatabase) {
    fun exists(table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)
    ).use { it.moveToFirst() }

    db.transaction {
        if (exists("model_sources")) {
            val invalid = mutableListOf<String>()
            db.rawQuery("SELECT id, family, url FROM model_sources", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val family = ModelFamily.fromStoredValue(cursor.getString(1))
                    if (family == null || !ModelSourceUrlValidator.validate(ModelSourceDraft(family, cursor.getString(2))).isValid) {
                        invalid += cursor.getString(0)
                    }
                }
            }
            invalid.forEach { id ->
                db.execSQL("UPDATE model_sources SET url='', normalizedKey=?, verified=0, authRequired=1 WHERE id=?",
                    arrayOf("restored:$id", id))
            }
            // Reachability must be checked on the receiving device; a backup is not proof
            // that a private repository or expiring source is still available there.
            db.execSQL("UPDATE model_sources SET verified=0, validationStatus='needs_check', checkedAt=NULL, lastErrorCode=NULL")
        }
        if (exists("model_provenance")) {
            db.execSQL("UPDATE model_provenance SET localPath=NULL, modelKey='restored:' || id")
        }
        if (exists("model_bundle_items")) {
            val portableMetadata = mutableListOf<Pair<String, String>>()
            db.rawQuery("SELECT id, modelMetadataJson FROM model_bundle_items", null).use { cursor ->
                while (cursor.moveToNext()) portableMetadata += cursor.getString(0) to
                    PortableModelMetadata.sanitize(cursor.getString(1))
            }
            portableMetadata.forEach { (id, metadata) ->
                db.execSQL("UPDATE model_bundle_items SET modelMetadataJson=? WHERE id=?", arrayOf(metadata, id))
            }
        }
        // These payloads and partial files are not exported. Retaining their installation
        // paths would falsely advertise installed/runnable models on another device.
        if (exists("pending_model_artifacts")) db.execSQL("DELETE FROM pending_model_artifacts")
        if (exists("download_tasks")) db.execSQL("DELETE FROM download_tasks")
        if (exists("litert_models")) db.execSQL("DELETE FROM litert_models")
    }
}
