package com.example.llamadroid.service.chat

/** Pure wording of tool results for image files (`chat_file_read`, `read_workspace_file`). */
object ToolImageResults {
    const val MISTAKES_NOTE =
        "It was written by a vision model and may contain spelling, grammar or text-recognition mistakes: fix obvious errors before you use it."
    const val OWN_VISION_NOTE =
        "Text you read in images can contain recognition mistakes: fix obvious spelling errors before you use it."

    /** Tool result when the chat model sees images: the picture follows as a user-role note. */
    fun shownContent(name: String): String =
        "Image $name is shown to you below as a picture. $OWN_VISION_NOTE"

    /** Text of the user-role note that carries the picture. */
    fun noteText(name: String, toolName: String): String = "Image $name (requested with $toolName)"

    /** Tool result when the vision helper described the image. */
    fun describedContent(name: String, text: String): String =
        "Image $name (description by the vision helper; $MISTAKES_NOTE):\n$text"

    fun tooLargeContent(name: String, sizeLabel: String): String =
        "Image $name is too large to describe ($sizeLabel)."

    fun unavailableContent(name: String, reason: String): String =
        "Image $name is attached, but it cannot be described now: $reason. " +
            "If you can see images, look at it directly; otherwise tell the user."

    /** Replay text of a note whose picture was dropped because the current server cannot see images. */
    const val IMAGE_OMITTED = "[image omitted]"
}
