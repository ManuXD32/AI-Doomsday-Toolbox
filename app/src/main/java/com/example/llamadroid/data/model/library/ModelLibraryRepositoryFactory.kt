package com.example.llamadroid.data.model.library

import android.content.Context
import com.example.llamadroid.data.api.HuggingFaceService
import com.example.llamadroid.data.db.AppDatabase
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit

/** Builds the shared persistent source repository for UI and startup recovery. */
object ModelLibraryRepositoryFactory {
    private fun createService(): HuggingFaceService {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        return Retrofit.Builder()
            .baseUrl("https://huggingface.co/api/")
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HuggingFaceService::class.java)
    }

    fun createBrowser(): HuggingFaceFolderBrowser = HuggingFaceFolderBrowser(createService())

    fun create(context: Context): ModelSourceRepository {
        return ModelSourceRepository(
            libraryDao = AppDatabase.getDatabase(context.applicationContext).modelLibraryDao(),
            folderBrowser = createBrowser()
        )
    }
}
