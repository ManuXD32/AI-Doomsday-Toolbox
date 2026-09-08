package com.example.llamadroid.data.model

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.audio.music.StableAudio3ComponentManifest
import com.example.llamadroid.audio.music.StableAudio3Kind
import com.example.llamadroid.audio.music.StableAudio3ManifestLoader
import com.example.llamadroid.data.db.ModelType

/** The normal LiteRT catalog and Audio shortcuts consume the same pinned manifest. */
object StableAudioCuratedBundleCatalog {
    fun bundles(context: Context): List<CuratedModelBundle> = fromManifest(StableAudio3ManifestLoader.load(context))

    /**
     * Resolves a pinned component only when both the repository and the exact
     * source URL match. A custom download that reuses a catalog basename must
     * remain a custom artifact and must never inherit the curated digest.
     */
    fun fileForDownload(
        context: Context,
        localFilename: String,
        repoId: String?,
        sourceUrl: String?
    ): CuratedBundleFile? {
        val matches = bundles(context).flatMap { it.files }.filter { file ->
            file.matchesInstalledFilename(localFilename) &&
                (repoId == null || file.repoId == repoId) &&
                (sourceUrl == null || file.downloadUrl == sourceUrl)
        }
        return matches.singleOrNull() ?: matches
            .distinctBy { it.artifactIdentity to it.sha256 }
            .singleOrNull()
    }

    /** Verifies a pinned Stable Audio component after source identity matching. */
    fun verifyDownload(
        context: Context,
        localFilename: String,
        downloadedFile: java.io.File,
        repoId: String?,
        sourceUrl: String?
    ): Boolean {
        val expected = fileForDownload(context, localFilename, repoId, sourceUrl) ?: return false
        verifyCuratedBundleFile(expected, localFilename, downloadedFile)
        return true
    }

    internal fun fromManifest(manifest: StableAudio3ComponentManifest): List<CuratedModelBundle> {
        return manifest.verifiedEntries().map { entry ->
            val music = entry.kind == StableAudio3Kind.MUSIC
            CuratedModelBundle(
                id = entry.id,
                titleRes = if (music) R.string.audio_music_bundle_music else R.string.audio_music_bundle_sfx,
                descriptionRes = R.string.audio_music_bundle_description,
                defaultPrefix = if (music) "Stable-Audio-3-Small-Music" else "Stable-Audio-3-Small-SFX",
                capabilityRes = listOf(R.string.audio_bundle_capability_cpu),
                files = entry.components.map { component ->
                    val main = component.role == "dit"
                    CuratedBundleFile(
                        id = "${entry.id}-${component.role}",
                        repoId = manifest.repository,
                        revision = component.sourceRevision,
                        remotePath = component.remotePath,
                        localFilename = component.localFileName,
                        type = if (main) ModelType.LITERT_AUDIO_DIT else ModelType.LITERT_AUDIO_COMPONENT,
                        sizeBytes = requireNotNull(component.sizeBytes),
                        sha256 = requireNotNull(component.sha256),
                        license = component.license,
                        strictSize = true,
                        componentRole = component.role,
                        audioFamily = if (main) {
                            if (music) StableAudioModelSupport.FAMILY_MUSIC
                            else StableAudioModelSupport.FAMILY_SFX
                        } else StableAudioModelSupport.FAMILY_SHARED,
                        sharedArtifactKey = if (main) null else "sha256:${component.sha256}"
                    )
                }
            )
        }
    }
}
