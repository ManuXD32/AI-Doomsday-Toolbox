package com.example.llamadroid.service

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Exact native owner metadata used to reconcile children across an app/service restart. */
@Keep
data class LlamaServerSessionOwner(
    @SerializedName(value = "sessionId", alternate = ["a"]) val sessionId: String,
    @SerializedName(value = "pid", alternate = ["b"]) val pid: Int,
    @SerializedName(value = "processStartTimeTicks", alternate = ["c"]) val processStartTimeTicks: Long,
    @SerializedName(value = "port", alternate = ["d"]) val port: Int,
    /** Exact launch snapshot for pause/restore; never contains prompts or generated content. */
    @SerializedName(value = "launchProfileJson", alternate = ["e"]) val launchProfileJson: String? = null
)

class LlamaServerSessionOwnerStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "llama_server_session_owners.json")
    private val lock = Any()
    private val gson = Gson()
    private val type = object : TypeToken<List<LlamaServerSessionOwner>>() {}.type

    fun readAll(): List<LlamaServerSessionOwner> = synchronized(lock) {
        runCatching { gson.fromJson<List<LlamaServerSessionOwner>>(file.readText(), type).orEmpty() }
            .getOrDefault(emptyList())
    }

    fun get(sessionId: String): LlamaServerSessionOwner? = readAll().firstOrNull { it.sessionId == sessionId }

    fun write(owner: LlamaServerSessionOwner) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val next = readAll().filterNot { it.sessionId == owner.sessionId } + owner
        writeAll(next)
    }

    fun delete(sessionId: String) = synchronized(lock) {
        val next = readAll().filterNot { it.sessionId == sessionId }
        if (next.isEmpty()) file.delete() else writeAll(next)
    }

    private fun writeAll(owners: List<LlamaServerSessionOwner>) {
        writeLlamaServerMetadata(file, gson.toJson(owners, type))
    }
}
