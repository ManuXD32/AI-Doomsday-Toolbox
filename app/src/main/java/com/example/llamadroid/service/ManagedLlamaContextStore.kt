package com.example.llamadroid.service

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Last probed capacity is valid only for the exact native process, never for a later restart. */
internal class ManagedLlamaContextStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "llama_server_contexts")
    private fun file(owner: LlamaServerSessionOwner) = File(root,
        owner.sessionId.replace(Regex("[^A-Za-z0-9._:-]"), "_") + ".json")

    fun read(owner: LlamaServerSessionOwner): Int? = runCatching {
        val record = JSONObject(file(owner).readText())
        record.optInt("contextTokens").takeIf {
            it > 0 && record.optInt("pid") == owner.pid &&
                record.optLong("startTicks") == owner.processStartTimeTicks && record.optInt("port") == owner.port
        }
    }.getOrNull()

    fun write(owner: LlamaServerSessionOwner, contextTokens: Int) {
        root.mkdirs()
        writeLlamaServerMetadata(file(owner), JSONObject().put("contextTokens", contextTokens).put("pid", owner.pid)
            .put("startTicks", owner.processStartTimeTicks).put("port", owner.port).toString())
    }
}
