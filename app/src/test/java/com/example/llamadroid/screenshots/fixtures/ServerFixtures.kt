package com.example.llamadroid.screenshots.fixtures

import android.app.Application
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.repository.LocalLlamaServerRepository
import com.example.llamadroid.screenshots.harness.RunningServerFake
import com.example.llamadroid.service.LlamaServerLaunchProfile
import kotlinx.coroutines.runBlocking

/** Managed llama-server cards that look running (stub HTTP + session files). */
object ServerFixtures {
    data class Running(val cardId: Long, val rowId: Long, val port: Int, val modelFile: String)

    /**
     * Creates a managed local server card (preset + card + linked row) for [model] and marks it
     * RUNNING on the shared stub port of [fake].
     */
    fun seedRunningServer(
        app: Application,
        fake: RunningServerFake,
        name: String,
        model: ModelFixtures.DemoModel,
        threads: Int = 6,
        contextSize: Int = 8192
    ): Running = runBlocking {
        ModelFixtures.insert(app, listOf(model))
        val port = fake.start()
        val modelPath = ModelFixtures.sparseFile(app, model.filename, model.bytes).absolutePath
        val profile = LlamaServerLaunchProfile(
            modelPath = modelPath, serverPort = port, threads = threads, contextSize = contextSize,
            temperature = 0.7f, flashAttention = true
        )
        val ids = LocalLlamaServerRepository(app, AppDatabase.getDatabase(app))
            .createLocalServer(name = name, profile = profile, port = port)
        fake.markRunning(app, "card:${ids.cardId}", port, profile.copy(serverPort = port))
        Running(ids.cardId, ids.serverRowId, port, model.filename)
    }
}
