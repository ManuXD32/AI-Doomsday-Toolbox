package com.example.llamadroid.tama.game

import android.content.Context
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.OnnxCatalogProvider
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.onnx.isTamaDefaultPicGenModel
import com.example.llamadroid.onnx.resolveOnnxCatalogEntry
import com.example.llamadroid.service.TamaArtworkGenerationService
import com.example.llamadroid.tama.data.TamaArtworkKind
import com.example.llamadroid.tama.data.TamaArtworkStatus
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.game.DailyDreamMoment
import com.example.llamadroid.tama.data.TamaPicGenDefaults
import com.example.llamadroid.tama.db.TamaArtworkEntity
import com.example.llamadroid.tama.db.TamaDatabase
import java.io.File
import java.util.UUID

data class ResolvedTamaPicGenModel(
    val model: ModelEntity,
    val label: String
)

object TamaArtworkManager {
    fun galleryRoot(context: Context): File = File(context.filesDir, "tama_gallery")

    fun artworkFile(context: Context, petId: String, artworkId: String): File {
        val petDir = File(galleryRoot(context), petId)
        petDir.mkdirs()
        return File(petDir, "$artworkId.png")
    }

    suspend fun queueDream(
        context: Context,
        pet: TamaPet,
        settingsRepository: SettingsRepository
    ): Result<TamaArtworkEntity> {
        return queueArtwork(
            context = context,
            pet = pet,
            settingsRepository = settingsRepository,
            kind = TamaArtworkKind.DREAM,
            sourceActivity = "sleeping",
            title = context.getString(R.string.tama_gallery_kind_dream),
            prompt = TamaPicGenDefaults.randomDreamPositivePrompt(),
            negativePrompt = TamaPicGenDefaults.DREAM_NEGATIVE_PROMPT
        )
    }

    suspend fun queuePainting(
        context: Context,
        pet: TamaPet,
        settingsRepository: SettingsRepository
    ): Result<TamaArtworkEntity> {
        return queueArtwork(
            context = context,
            pet = pet,
            settingsRepository = settingsRepository,
            kind = TamaArtworkKind.PAINTING,
            sourceActivity = "studying",
            title = context.getString(R.string.tama_gallery_kind_painting),
            prompt = TamaPicGenDefaults.randomPaintingPositivePrompt(),
            negativePrompt = TamaPicGenDefaults.PAINTING_NEGATIVE_PROMPT
        )
    }

    suspend fun resolvePicGenModel(
        context: Context,
        settingsRepository: SettingsRepository
    ): Result<ResolvedTamaPicGenModel> {
        val appDatabase = AppDatabase.getDatabase(context.applicationContext)
        val installed = appDatabase.modelDao()
            .getModelsByTypesSync(listOf(ModelType.ONNX_IMAGE_GEN))
            .filter { it.isOnnxTxt2ImgBundle() }

        val preferredFilename = settingsRepository.tamaPicGenModelFilename.value
        val preferred = preferredFilename?.let { filename ->
            installed.firstOrNull { it.filename == filename }
        }

        val defaultModel = installed.firstOrNull { it.isTamaDefaultPicGenModel() }

        val resolved = preferred ?: defaultModel
            ?: return Result.failure(
                IllegalStateException(context.getString(R.string.tama_pic_gen_install_default_model))
            )

        val label = resolveOnnxCatalogEntry(resolved)?.let { entry ->
            "${entry.title} · ${providerLabel(context, entry.provider)}"
        } ?: resolved.filename

        return Result.success(ResolvedTamaPicGenModel(resolved, label))
    }

    suspend fun queueDailyDreamAlbum(
        context: Context,
        pet: TamaPet,
        settingsRepository: SettingsRepository,
        albumId: String,
        dreamDate: String,
        story: String,
        closing: String,
        moments: List<DailyDreamMoment>,
        sourceActivity: String = "sleeping"
    ): Result<List<TamaArtworkEntity>> {
        val resolvedModel = resolvePicGenModel(context, settingsRepository).getOrElse { error ->
            return Result.failure(error)
        }
        val resolution = settingsRepository.tamaPicGenResolution.value
        val queued = moments.mapIndexed { index, moment ->
            val artworkId = UUID.randomUUID().toString()
            TamaArtworkEntity(
                id = artworkId,
                petId = pet.id,
                kind = TamaArtworkKind.DAILY_DREAM.name,
                status = TamaArtworkStatus.QUEUED.name,
                title = moment.title,
                prompt = moment.prompt,
                negativePrompt = "",
                modelFilename = resolvedModel.model.filename,
                modelLabel = resolvedModel.label,
                width = resolution,
                height = resolution,
                steps = 20,
                cfgScale = 6.5f,
                seed = null,
                sourceActivity = sourceActivity,
                albumId = albumId,
                albumIndex = index,
                albumDate = dreamDate,
                albumSummary = TamaDailyDreamManager.encodeAlbumSummary(
                    story = story,
                    closing = closing,
                    language = settingsRepository.tamaDeepDreamDesiredLanguage.value,
                    momentTexts = moments.map { it.title }
                ),
                filePath = artworkFile(context, pet.id, artworkId).absolutePath,
                errorMessage = null,
                createdAt = System.currentTimeMillis() + index,
                startedAt = null,
                completedAt = null
            )
        }
        TamaDatabase.getInstance(context.applicationContext).tamaDao().saveArtworks(queued)
        ContextCompat.startForegroundService(
            context.applicationContext,
            TamaArtworkGenerationService.createProcessQueueIntent(context.applicationContext)
        )
        return Result.success(queued)
    }

    fun deleteArtworkFile(artwork: TamaArtworkEntity): Boolean {
        val file = artwork.filePath?.let(::File) ?: return true
        return !file.exists() || file.delete()
    }

    private suspend fun queueArtwork(
        context: Context,
        pet: TamaPet,
        settingsRepository: SettingsRepository,
        kind: TamaArtworkKind,
        sourceActivity: String,
        title: String,
        prompt: String,
        negativePrompt: String
    ): Result<TamaArtworkEntity> {
        val resolvedModel = resolvePicGenModel(context, settingsRepository).getOrElse { error ->
            return Result.failure(error)
        }
        val resolution = settingsRepository.tamaPicGenResolution.value
        val artworkId = UUID.randomUUID().toString()
        val artwork = TamaArtworkEntity(
            id = artworkId,
            petId = pet.id,
            kind = kind.name,
            status = TamaArtworkStatus.QUEUED.name,
            title = title,
            prompt = prompt,
            negativePrompt = negativePrompt,
            modelFilename = resolvedModel.model.filename,
            modelLabel = resolvedModel.label,
            width = resolution,
            height = resolution,
            steps = 20,
            cfgScale = 6.5f,
            seed = null,
            sourceActivity = sourceActivity,
            albumId = null,
            albumIndex = 0,
            albumDate = null,
            albumSummary = null,
            filePath = artworkFile(context, pet.id, artworkId).absolutePath,
            errorMessage = null,
            createdAt = System.currentTimeMillis(),
            startedAt = null,
            completedAt = null
        )

        TamaDatabase.getInstance(context.applicationContext).tamaDao().saveArtwork(artwork)
        ContextCompat.startForegroundService(
            context.applicationContext,
            TamaArtworkGenerationService.createProcessQueueIntent(context.applicationContext)
        )
        return Result.success(artwork)
    }

    private fun providerLabel(context: Context, provider: OnnxCatalogProvider): String {
        return when (provider) {
            OnnxCatalogProvider.SDAI -> context.getString(R.string.onnx_models_provider_sdai)
            OnnxCatalogProvider.MANUXD32 -> context.getString(R.string.onnx_models_provider_manuxd32)
            OnnxCatalogProvider.SUPERTONIC -> context.getString(R.string.onnx_models_provider_supertonic)
        }
    }
}
