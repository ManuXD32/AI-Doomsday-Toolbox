package com.example.llamadroid.ui.ai.llama.chat.cards

/** Load state of the full-screen map dialog. Driven by the page title hooks and WebView errors; pure for testing. */
internal sealed interface MapDialogState {
    data object Loading : MapDialogState
    data object Ready : MapDialogState
    data class Error(val detail: String) : MapDialogState
}

internal object MapDialogReducer {
    const val TITLE_READY = "adt-map-ready"
    const val TITLE_ERROR_PREFIX = "adt-map-error:"
    const val TIMEOUT_MS = 10_000L
    const val TIMEOUT_DETAIL = "timeout"

    /** Only Loading transitions: once the map is ready or failed, later signals are ignored. */
    fun onTitle(state: MapDialogState, title: String?): MapDialogState {
        if (state !is MapDialogState.Loading || title == null) return state
        return when {
            title == TITLE_READY -> MapDialogState.Ready
            title.startsWith(TITLE_ERROR_PREFIX) -> MapDialogState.Error(title.removePrefix(TITLE_ERROR_PREFIX).trim().take(200))
            else -> state
        }
    }

    /** Main-frame or bundled-asset failure. Tile failures must not be passed here (offline tiles are tolerated). */
    fun onLoadError(state: MapDialogState, path: String, code: Int): MapDialogState =
        if (state is MapDialogState.Loading) MapDialogState.Error("$path ($code)") else state

    fun onTimeout(state: MapDialogState): MapDialogState =
        if (state is MapDialogState.Loading) MapDialogState.Error(TIMEOUT_DETAIL) else state

    /** True for requests whose failure means the map cannot work (page and bundled Leaflet files). */
    fun isCriticalPath(path: String): Boolean = path == "/index.html" || path.startsWith("/leaflet/")
}
