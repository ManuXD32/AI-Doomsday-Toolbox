package com.example.llamadroid.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Metadata survives installed-model deletion; no model-row foreign keys are introduced. */
internal fun createModelLibraryTables(database: SupportSQLiteDatabase) {
    database.execSQL("CREATE TABLE IF NOT EXISTS `model_sources` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `family` TEXT NOT NULL, `label` TEXT NOT NULL, `url` TEXT NOT NULL, `normalizedKey` TEXT NOT NULL, `repositoryId` TEXT, `revision` TEXT NOT NULL, `filePath` TEXT, `authRequired` INTEGER NOT NULL, `verified` INTEGER NOT NULL, `expectedSha256` TEXT, `expectedSizeBytes` INTEGER, `mediaType` TEXT, `validationStatus` TEXT NOT NULL DEFAULT 'needs_check', `checkedAt` INTEGER, `lastErrorCode` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_model_sources_normalizedKey` ON `model_sources` (`normalizedKey`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_model_sources_family` ON `model_sources` (`family`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_model_sources_updatedAt` ON `model_sources` (`updatedAt`)")
    database.execSQL("CREATE TABLE IF NOT EXISTS `model_provenance` (`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `modelKey` TEXT NOT NULL, `family` TEXT NOT NULL, `role` TEXT, `localPath` TEXT, `artifactSha256` TEXT, `sizeBytes` INTEGER, `importedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_model_provenance_sourceId` ON `model_provenance` (`sourceId`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_model_provenance_modelKey` ON `model_provenance` (`modelKey`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_model_provenance_family` ON `model_provenance` (`family`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_model_provenance_updatedAt` ON `model_provenance` (`updatedAt`)")
    database.execSQL("CREATE TABLE IF NOT EXISTS `model_bundles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `family` TEXT NOT NULL, `description` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_model_bundles_family` ON `model_bundles` (`family`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_model_bundles_updatedAt` ON `model_bundles` (`updatedAt`)")
    database.execSQL("CREATE TABLE IF NOT EXISTS `model_bundle_items` (`id` TEXT NOT NULL, `bundleId` TEXT NOT NULL, `itemKey` TEXT NOT NULL, `family` TEXT NOT NULL, `role` TEXT, `sourceId` TEXT, `required` INTEGER NOT NULL, `partGroup` TEXT, `partIndex` INTEGER, `partCount` INTEGER, `localFilename` TEXT, `relativePath` TEXT, `expectedSha256` TEXT, `expectedSizeBytes` INTEGER, `modelMetadataJson` TEXT NOT NULL DEFAULT '{}', `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_model_bundle_items_bundleId` ON `model_bundle_items` (`bundleId`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_model_bundle_items_sourceId` ON `model_bundle_items` (`sourceId`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_model_bundle_items_family` ON `model_bundle_items` (`family`)")
    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_model_bundle_items_bundleId_itemKey` ON `model_bundle_items` (`bundleId`, `itemKey`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_model_bundle_items_partGroup` ON `model_bundle_items` (`partGroup`)")
    database.execSQL("CREATE TABLE IF NOT EXISTS `pending_model_artifacts` (`id` TEXT NOT NULL, `downloadTaskId` TEXT, `sourceId` TEXT, `bundleId` TEXT, `bundleItemId` TEXT, `filename` TEXT NOT NULL, `stagingPath` TEXT NOT NULL, `destinationPath` TEXT, `requestedFamily` TEXT, `requestedRole` TEXT, `detectedFamily` TEXT, `detectedRole` TEXT, `detectedType` TEXT, `status` TEXT NOT NULL, `validationJson` TEXT, `validationMessage` TEXT, `requiresManualPromotion` INTEGER NOT NULL, `promotedModelKey` TEXT, `promotedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_model_artifacts_downloadTaskId` ON `pending_model_artifacts` (`downloadTaskId`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_model_artifacts_sourceId` ON `pending_model_artifacts` (`sourceId`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_model_artifacts_bundleId` ON `pending_model_artifacts` (`bundleId`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_model_artifacts_bundleItemId` ON `pending_model_artifacts` (`bundleItemId`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_model_artifacts_status` ON `pending_model_artifacts` (`status`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_model_artifacts_updatedAt` ON `pending_model_artifacts` (`updatedAt`)")
}
