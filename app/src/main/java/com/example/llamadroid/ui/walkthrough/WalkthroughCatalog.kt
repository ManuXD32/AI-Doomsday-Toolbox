package com.example.llamadroid.ui.walkthrough

import com.example.llamadroid.R
import com.example.llamadroid.ui.ai.ToolCatalog
import com.example.llamadroid.ui.navigation.Screen

/** A localized walkthrough lesson that points at one real destination in the app. */
data class WalkthroughLesson(
    val id: String,
    val titleRes: Int,
    val bodyRes: Int,
    val route: String,
    val toolId: String? = null,
    val previewKey: String
)

/** A bounded chapter in the first-run walkthrough. */
data class WalkthroughChapter(
    val id: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val lessons: List<WalkthroughLesson>
)

/**
 * The walkthrough content contract.
 *
 * Tool lessons deliberately derive their title and route from [ToolCatalog]. This keeps the
 * walkthrough useful when a tool's existing display name or route changes, while the unit test
 * makes sure every catalog tool remains represented exactly once.
 */
object WalkthroughCatalog {
    private const val PREVIEW_HOME = "home"
    private const val PREVIEW_TOOLS = "tools"
    private const val PREVIEW_LIBRARY = "library"
    private const val PREVIEW_CREATE = "create"
    private const val PREVIEW_TAMA = "tama"
    private const val PREVIEW_FARM = "farm"
    private const val PREVIEW_ARCADE = "arcade"
    private const val PREVIEW_ADVENTURES = "adventures"
    private const val PREVIEW_GALLERY = "gallery"

    private val toolDefinitions = ToolCatalog.tools.associateBy { it.id }

    val chapters: List<WalkthroughChapter> = listOf(
        WalkthroughChapter(
            id = "conversations",
            titleRes = R.string.walkthrough_chapter_conversations_title,
            descriptionRes = R.string.walkthrough_chapter_conversations_description,
            lessons = listOf(
                toolLesson("chat", R.string.walkthrough_lesson_chat_body, PREVIEW_TOOLS),
                toolLesson("native_llama", R.string.walkthrough_lesson_native_llama_body, PREVIEW_HOME)
            )
        ),
        WalkthroughChapter(
            id = "agent",
            titleRes = R.string.walkthrough_chapter_agent_title,
            descriptionRes = R.string.walkthrough_chapter_agent_description,
            lessons = listOf(
                toolLesson("agent", R.string.walkthrough_lesson_agent_body, PREVIEW_TOOLS)
            )
        ),
        WalkthroughChapter(
            id = "images_video",
            titleRes = R.string.walkthrough_chapter_images_video_title,
            descriptionRes = R.string.walkthrough_chapter_images_video_description,
            lessons = listOf(
                toolLesson("image_generation", R.string.walkthrough_lesson_image_generation_body, PREVIEW_CREATE),
                toolLesson("video_generation", R.string.walkthrough_lesson_video_generation_body, PREVIEW_CREATE),
                toolLesson("onnx_image_generation", R.string.walkthrough_lesson_onnx_image_generation_body, PREVIEW_CREATE),
                toolLesson("onnx_background_removal", R.string.walkthrough_lesson_onnx_background_removal_body, PREVIEW_CREATE),
                toolLesson("video_upscaler", R.string.walkthrough_lesson_video_upscaler_body, PREVIEW_CREATE),
                toolLesson("video_interpolation", R.string.walkthrough_lesson_video_interpolation_body, PREVIEW_CREATE),
                toolLesson("subtitle_burn", R.string.walkthrough_lesson_subtitle_burn_body, PREVIEW_CREATE)
            )
        ),
        WalkthroughChapter(
            id = "voice",
            titleRes = R.string.walkthrough_chapter_voice_title,
            descriptionRes = R.string.walkthrough_chapter_voice_description,
            lessons = listOf(
                toolLesson("onnx_tts", R.string.walkthrough_lesson_onnx_tts_body, PREVIEW_CREATE),
                toolLesson("live_translator", R.string.walkthrough_lesson_live_translator_body, PREVIEW_TOOLS),
                toolLesson("transcription", R.string.walkthrough_lesson_transcription_body, PREVIEW_CREATE)
            )
        ),
        WalkthroughChapter(
            id = "documents",
            titleRes = R.string.walkthrough_chapter_documents_title,
            descriptionRes = R.string.walkthrough_chapter_documents_description,
            lessons = listOf(
                toolLesson("video_summary", R.string.walkthrough_lesson_video_summary_body, PREVIEW_CREATE),
                toolLesson("pdf_tools", R.string.walkthrough_lesson_pdf_tools_body, PREVIEW_CREATE),
                toolLesson("workflows", R.string.walkthrough_lesson_workflows_body, PREVIEW_CREATE),
                toolLesson("dataset", R.string.walkthrough_lesson_dataset_body, PREVIEW_LIBRARY)
            )
        ),
        WalkthroughChapter(
            id = "organizer",
            titleRes = R.string.walkthrough_chapter_organizer_title,
            descriptionRes = R.string.walkthrough_chapter_organizer_description,
            lessons = listOf(
                toolLesson("organizer", R.string.walkthrough_lesson_organizer_body, PREVIEW_HOME)
            )
        ),
        WalkthroughChapter(
            id = "library",
            titleRes = R.string.walkthrough_chapter_library_title,
            descriptionRes = R.string.walkthrough_chapter_library_description,
            lessons = listOf(
                toolLesson("knowledge", R.string.walkthrough_lesson_knowledge_body, PREVIEW_LIBRARY),
                toolLesson("offline_library", R.string.walkthrough_lesson_offline_library_body, PREVIEW_LIBRARY),
                toolLesson("model_library", R.string.walkthrough_lesson_model_library_body, PREVIEW_LIBRARY)
            )
        ),
        WalkthroughChapter(
            id = "infrastructure",
            titleRes = R.string.walkthrough_chapter_infrastructure_title,
            descriptionRes = R.string.walkthrough_chapter_infrastructure_description,
            lessons = listOf(
                toolLesson("servers_hub", R.string.walkthrough_lesson_servers_hub_body, PREVIEW_HOME),
                toolLesson("distributed_llm", R.string.walkthrough_lesson_distributed_llm_body, PREVIEW_HOME),
                toolLesson("distributed_media", R.string.walkthrough_lesson_distributed_media_body, PREVIEW_HOME),
                toolLesson("benchmark", R.string.walkthrough_lesson_benchmark_body, PREVIEW_HOME),
                toolLesson("quadtrix", R.string.walkthrough_lesson_quadtrix_body, PREVIEW_HOME),
                toolLesson("termux", R.string.walkthrough_lesson_termux_body, PREVIEW_HOME),
                toolLesson("ollama", R.string.walkthrough_lesson_ollama_body, PREVIEW_HOME),
                toolLesson("file_server", R.string.walkthrough_lesson_file_server_body, PREVIEW_HOME)
            )
        ),
        WalkthroughChapter(
            id = "tama",
            titleRes = R.string.walkthrough_chapter_tama_title,
            descriptionRes = R.string.walkthrough_chapter_tama_description,
            lessons = listOf(
                extraLesson(
                    id = "tama_room",
                    titleRes = R.string.walkthrough_lesson_tama_room_title,
                    bodyRes = R.string.walkthrough_lesson_tama_room_body,
                    route = Screen.Tama.route,
                    previewKey = PREVIEW_TAMA
                ),
                extraLesson(
                    id = "tama_care",
                    titleRes = R.string.walkthrough_lesson_tama_care_title,
                    bodyRes = R.string.walkthrough_lesson_tama_care_body,
                    route = Screen.Tama.route,
                    previewKey = PREVIEW_TAMA
                ),
                extraLesson(
                    id = "tama_shop",
                    titleRes = R.string.walkthrough_lesson_tama_shop_title,
                    bodyRes = R.string.walkthrough_lesson_tama_shop_body,
                    route = Screen.Store.route,
                    previewKey = PREVIEW_TAMA
                ),
                extraLesson(
                    id = "tama_farm",
                    titleRes = R.string.walkthrough_lesson_tama_farm_title,
                    bodyRes = R.string.walkthrough_lesson_tama_farm_body,
                    route = Screen.Farm.route,
                    previewKey = PREVIEW_FARM
                ),
                extraLesson(
                    id = "tama_arcade",
                    titleRes = R.string.walkthrough_lesson_tama_arcade_title,
                    bodyRes = R.string.walkthrough_lesson_tama_arcade_body,
                    route = Screen.Arcade.route,
                    previewKey = PREVIEW_ARCADE
                ),
                extraLesson(
                    id = "tama_adventures",
                    titleRes = R.string.walkthrough_lesson_tama_adventures_title,
                    bodyRes = R.string.walkthrough_lesson_tama_adventures_body,
                    route = Screen.AdventureGate.route,
                    previewKey = PREVIEW_ADVENTURES
                ),
                extraLesson(
                    id = "tama_gallery",
                    titleRes = R.string.walkthrough_lesson_tama_gallery_title,
                    bodyRes = R.string.walkthrough_lesson_tama_gallery_body,
                    route = Screen.TamaGallery.route,
                    previewKey = PREVIEW_GALLERY
                )
            )
        ),
        WalkthroughChapter(
            id = "settings_help",
            titleRes = R.string.walkthrough_chapter_settings_help_title,
            descriptionRes = R.string.walkthrough_chapter_settings_help_description,
            lessons = listOf(
                extraLesson(
                    id = "settings",
                    titleRes = R.string.walkthrough_lesson_settings_title,
                    bodyRes = R.string.walkthrough_lesson_settings_body,
                    route = Screen.Settings.route,
                    previewKey = PREVIEW_HOME
                ),
                extraLesson(
                    id = "about",
                    titleRes = R.string.walkthrough_lesson_about_title,
                    bodyRes = R.string.walkthrough_lesson_about_body,
                    route = "about",
                    previewKey = PREVIEW_HOME
                ),
                extraLesson(
                    id = "diagnostics",
                    titleRes = R.string.walkthrough_lesson_diagnostics_title,
                    bodyRes = R.string.walkthrough_lesson_diagnostics_body,
                    route = Screen.Logs.route,
                    previewKey = PREVIEW_HOME
                )
            )
        )
    )

    fun chapter(id: String): WalkthroughChapter? = chapters.firstOrNull { it.id == id }

    private fun toolLesson(toolId: String, bodyRes: Int, previewKey: String): WalkthroughLesson {
        val definition = checkNotNull(toolDefinitions[toolId]) {
            "Walkthrough lesson references unknown tool '$toolId'"
        }
        return WalkthroughLesson(
            id = toolId,
            titleRes = definition.titleRes,
            bodyRes = bodyRes,
            route = definition.route,
            toolId = definition.id,
            previewKey = previewKey
        )
    }

    private fun extraLesson(
        id: String,
        titleRes: Int,
        bodyRes: Int,
        route: String,
        previewKey: String
    ): WalkthroughLesson = WalkthroughLesson(
        id = id,
        titleRes = titleRes,
        bodyRes = bodyRes,
        route = route,
        previewKey = previewKey
    )
}
