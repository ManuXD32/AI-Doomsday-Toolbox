package com.example.llamadroid.service

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Exact native owner metadata used to reconcile children across an app/service restart. */
data class LlamaServerSessionOwner(
    val sessionId: String,
    val pid: Int,
    val processStartTimeTicks: Long,
    val port: Int,
    /** Exact launch snapshot for pause/restore; never contains prompts or generated content. */
    val launchProfileJson: String? = null
)

class LlamaServerSessionOwnerStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "llama_server_session_owners.json")
    private val lock = Any()
    private val gson = Gson()
    private val type = object : TypeToken<List<LlamaServerSessionOwner>>() {}.type

    fun readAll(): List<LlamaServerSessionOwner> = synchronized(lock) {
        runCatching { gson.fromJson<List<LlamaServerSessionOwner>>(file.takeIf(File::isFile)?.readText(), type).orEmpty() }
            .getOrDefault(emptyList())
    }

    fun get(sessionId: String): LlamaServerSessionOwner? = readAll().firstOrNull { it.sessionId == sessionId }

    fun write(owner: LlamaServerSessionOwner) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val next = readAll().filterNot { it.sessionId == owner.sessionId } + owner
        file.writeText(gson.toJson(next, type), Charsets.UTF_8)
    }

    fun delete(sessionId: String) = synchronized(lock) {
        val next = readAll().filterNot { it.sessionId == sessionId }
        if (next.isEmpty()) file.delete() else file.writeText(gson.toJson(next, type), Charsets.UTF_8)
    }
}
