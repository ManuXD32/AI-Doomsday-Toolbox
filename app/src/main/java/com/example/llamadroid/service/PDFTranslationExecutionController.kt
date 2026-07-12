package com.example.llamadroid.service

import java.util.concurrent.atomic.AtomicBoolean

class PDFTranslationExecutionController {
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var activeRemoteClient: RemoteSummaryClient? = null

    fun cancel() {
        cancelled.set(true)
        activeRemoteClient?.cancelActiveCall()
    }

    fun registerRemoteClient(client: RemoteSummaryClient?) {
        activeRemoteClient = client
        if (cancelled.get()) {
            client?.cancelActiveCall()
        }
    }

    fun clearRemoteClient(client: RemoteSummaryClient?) {
        if (activeRemoteClient === client) {
            activeRemoteClient = null
        }
    }

    fun isCancelled(): Boolean = cancelled.get()
}
