package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LiteRtModelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiteRtEmbeddingClient(private val context: Context) {
    suspend fun embed(
        model: LiteRtModelEntity,
        text: String,
        threadCount: Int
    ): List<Float> = withContext(Dispatchers.IO) {
        @Suppress("UNUSED_VARIABLE")
        val ignored = Triple(model, text, threadCount)
        throw IllegalStateException(context.getString(R.string.kb_litert_embedding_runtime_missing))
    }
}
