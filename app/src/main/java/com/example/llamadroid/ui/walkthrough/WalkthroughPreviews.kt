package com.example.llamadroid.ui.walkthrough

import com.example.llamadroid.R

/** Actual demonstration screenshots, with Android selecting the English or Spanish asset. */
internal fun lessonPreviewResource(id: String): Int? = when (id) {
    "chat" -> R.drawable.tour_lesson_chat
    "agent" -> R.drawable.tour_lesson_agent
    "native_llama" -> R.drawable.tour_lesson_native_llama
    "image_generation" -> R.drawable.tour_lesson_image_generation
    "video_generation" -> R.drawable.tour_lesson_video_generation
    "onnx_image_generation" -> R.drawable.tour_lesson_onnx_image_generation
    "onnx_background_removal" -> R.drawable.tour_lesson_onnx_background_removal
    "video_upscaler" -> R.drawable.tour_lesson_video_upscaler
    "video_interpolation" -> R.drawable.tour_lesson_video_interpolation
    "subtitle_burn" -> R.drawable.tour_lesson_subtitle_burn
    "onnx_tts" -> R.drawable.tour_lesson_onnx_tts
    "live_translator" -> R.drawable.tour_lesson_live_translator
    "transcription" -> R.drawable.tour_lesson_transcription
    "video_summary" -> R.drawable.tour_lesson_video_summary
    "pdf_tools" -> R.drawable.tour_lesson_pdf_tools
    "workflows" -> R.drawable.tour_lesson_workflows
    "dataset" -> R.drawable.tour_lesson_dataset
    "organizer" -> R.drawable.tour_lesson_organizer
    "knowledge" -> R.drawable.tour_lesson_knowledge
    "offline_library" -> R.drawable.tour_lesson_offline_library
    "servers_hub" -> R.drawable.tour_lesson_servers_hub
    "distributed_llm" -> R.drawable.tour_lesson_distributed_llm
    "distributed_media" -> R.drawable.tour_lesson_distributed_media
    "benchmark" -> R.drawable.tour_lesson_benchmark
    "quadtrix" -> R.drawable.tour_lesson_quadtrix
    "termux" -> R.drawable.tour_lesson_termux
    "ollama" -> R.drawable.tour_lesson_ollama
    "file_server" -> R.drawable.tour_lesson_file_server
    "model_library" -> R.drawable.tour_lesson_model_library
    "tama_room" -> R.drawable.tour_lesson_tama_room
    "tama_care" -> R.drawable.tour_lesson_tama_care
    "tama_shop" -> R.drawable.tour_lesson_tama_shop
    "tama_farm" -> R.drawable.tour_lesson_tama_farm
    "tama_arcade" -> R.drawable.tour_lesson_tama_arcade
    "tama_adventures" -> R.drawable.tour_lesson_tama_adventures
    "tama_gallery" -> R.drawable.tour_lesson_tama_gallery
    "settings" -> R.drawable.tour_lesson_settings
    "about" -> R.drawable.tour_lesson_about
    "diagnostics" -> R.drawable.tour_lesson_diagnostics
    else -> null
}
