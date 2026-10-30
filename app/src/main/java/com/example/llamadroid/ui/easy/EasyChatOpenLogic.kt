package com.example.llamadroid.ui.easy

/** What tapping a chat row in the Easy chat list should do. */
sealed class EasyChatOpenAction {
    /** The chat is bound to a server row: navigate straight to it. */
    data class Open(val serverId: Long) : EasyChatOpenAction()

    /** Orphan chat (server card deleted): bind it to the selected Easy model, then open. */
    data class Rebind(val modelFilename: String) : EasyChatOpenAction()

    /** Orphan chat and no Easy model selected: send the user to the Models overlay. */
    data object PickModel : EasyChatOpenAction()
}

/** Pure decision for [EasyChatOpenAction]; [selectedModelFilename] is the Easy chat model's file name, if any. */
fun easyChatOpenAction(serverId: Long?, selectedModelFilename: String?): EasyChatOpenAction = when {
    serverId != null -> EasyChatOpenAction.Open(serverId)
    !selectedModelFilename.isNullOrBlank() -> EasyChatOpenAction.Rebind(selectedModelFilename)
    else -> EasyChatOpenAction.PickModel
}
