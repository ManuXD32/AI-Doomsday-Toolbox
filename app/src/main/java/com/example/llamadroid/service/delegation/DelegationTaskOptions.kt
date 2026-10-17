package com.example.llamadroid.service.delegation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Choices made when a task starts or is reopened, stored in `native_chat_delegate_tasks.optionsJson`
 * so a Continue after a process death still knows them.
 *
 * - [projectId]: Android-side harness workspace id of an existing project to open; null = see [continueProject].
 * - [continueProject]: reuse the chat's previous project when [projectId] is null.
 * - [threadId]: harness session id of an existing thread to keep using; null = a new thread.
 * - [pendingAttachmentRefs]: chat file references (`f12`, `img55`) not yet copied into the project's `inputs/`.
 */
@Serializable
data class DelegationTaskOptions(
    val projectId: String? = null,
    val continueProject: Boolean = false,
    val threadId: String? = null,
    val pendingAttachmentRefs: List<String> = emptyList()
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Lenient: null, blank or malformed JSON gives the defaults. */
        fun decode(json: String?): DelegationTaskOptions =
            json?.takeIf { it.isNotBlank() }
                ?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
                ?: DelegationTaskOptions()
    }
}
