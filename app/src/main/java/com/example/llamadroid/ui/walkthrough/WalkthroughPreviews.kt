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
    "models_llm" -> R.drawable.tour_lesson_models_llm
    "models_sd_components" -> R.drawable.tour_lesson_models_sd_components
    "models_onnx_bundle" -> R.drawable.tour_lesson_models_onnx_bundle
    "models_litert_backend" -> R.drawable.tour_lesson_models_litert_backend
    "models_whisper_vad" -> R.drawable.tour_lesson_models_whisper_vad
    "models_custom_url" -> R.drawable.tour_lesson_models_custom_url
    "models_saved_links" -> R.drawable.tour_lesson_models_saved_links
    "models_unknown" -> R.drawable.tour_lesson_models_unknown
    "models_hf_folder" -> R.drawable.tour_lesson_models_hf_folder
    "models_bundles" -> R.drawable.tour_lesson_models_bundles
    "conversations_native_management" -> R.drawable.tour_lesson_conversations_native_management
    "conversations_scheduler" -> R.drawable.tour_lesson_conversations_scheduler
    "conversations_server_selection" -> R.drawable.tour_lesson_conversations_server_selection
    "distributed_roles" -> R.drawable.tour_lesson_distributed_roles
    "fastsd_gallery" -> R.drawable.tour_lesson_fastsd_gallery
    else -> null
}
