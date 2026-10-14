package com.example.llamadroid.ui.ai.llama.chat

/** Title generation needs both the switch and a model; either missing means chats keep automatic names. */
internal fun titleHelperUnset(enabled: Boolean, modelPath: String): Boolean = !enabled || modelPath.isBlank()

/** The vision helper needs both the switch and a vision server; either missing means it will not run. */
internal fun visionHelperUnset(enabled: Boolean, serverRowId: Long): Boolean = !enabled || serverRowId <= 0L

/** What the new-chat pop-up has to tell the user. */
internal data class NewChatNotice(
    val diskKvCache: Boolean,
    val titleHelperUnset: Boolean,
    val visionHelperUnset: Boolean
) {
    /** The pop-up is shown for a new chat when at least one part applies. */
    val needed: Boolean get() = diskKvCache || titleHelperUnset || visionHelperUnset
    val anyHelperUnset: Boolean get() = titleHelperUnset || visionHelperUnset
}
