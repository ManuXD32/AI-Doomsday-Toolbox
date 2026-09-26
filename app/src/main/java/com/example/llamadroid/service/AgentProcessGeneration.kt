package com.example.llamadroid.service

import java.util.UUID

/** One identity shared by every Agent-owned process in this Android app process. */
internal object AgentProcessGeneration {
    val id: String = UUID.randomUUID().toString()
}
