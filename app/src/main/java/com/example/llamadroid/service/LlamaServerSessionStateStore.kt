package com.example.llamadroid.service

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Cross-process projection for dashboard/AI Hub state. It contains metadata, never chat text. */
class LlamaServerSessionStateStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "llama_server_session_states.json")
    private val lock = Any()
    private val gson = Gson()
    private val type = object : TypeToken<List<LlamaServerSessionSnapshot>>() {}.type

    fun readAll(): List<LlamaServerSessionSnapshot> = synchronized(lock) {
        runCatching {
            gson.fromJson<List<LlamaServerSessionSnapshot>>(file.takeIf(File::isFile)?.readText(), type)
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    fun write(snapshot: LlamaServerSessionSnapshot) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val next = readAll().filterNot { it.sessionId == snapshot.sessionId } + snapshot
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(gson.toJson(next, type), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(gson.toJson(next, type), Charsets.UTF_8)
            temp.delete()
        }
    }

    fun delete(sessionId: String) = synchronized(lock) {
        val next = readAll().filterNot { it.sessionId == sessionId }
        if (next.isEmpty()) file.delete()
        else file.writeText(gson.toJson(next, type), Charsets.UTF_8)
    }
}

