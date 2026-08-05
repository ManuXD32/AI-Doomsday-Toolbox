package com.example.llamadroid.service

/**
 * Configuration for WhisperCPP audio transcription
 */
data class WhisperConfig(
    val modelPath: String,
    val audioPath: String,
    val language: String = "auto", // "auto" for auto-detect, or language code
    val translate: Boolean = false, // Translate to English
    val outputFormats: Set<WhisperOutputFormat> = setOf(WhisperOutputFormat.TXT),
    val threads: Int = 4,
    val outputDir: String? = null, // Custom output directory
    val purpose: WhisperInvocationPurpose = WhisperInvocationPurpose.BATCH_TRANSCRIPTION,
    // null means use the current persisted VAD profile at launch time.
    val vad: WhisperVadConfig? = null
)

enum class WhisperOutputFormat(val cliFlag: String, val extension: String) {
    TXT("-otxt", "txt"),
    SRT("-osrt", "srt"),
    VTT("-ovtt", "vtt"),
    JSON("-oj", "json")
}

/**
 * Available Whisper models with their sizes
 */
enum class WhisperModel(
    val modelName: String,
    val displayName: String,
    val sizeBytes: Long,
    val isEnglishOnly: Boolean = false,
    val isQuantized: Boolean = false
) {
    // Tiny models (~75MB)
    TINY("tiny", "Tiny", 75_000_000L),
    TINY_EN("tiny.en", "Tiny (English)", 75_000_000L, isEnglishOnly = true),
    TINY_Q5_1("tiny-q5_1", "Tiny Q5", 45_000_000L, isQuantized = true),
    TINY_EN_Q5_1("tiny.en-q5_1", "Tiny Q5 (English)", 45_000_000L, isEnglishOnly = true, isQuantized = true),
    TINY_Q8_0("tiny-q8_0", "Tiny Q8", 55_000_000L, isQuantized = true),
    
    // Base models (~142MB)
    BASE("base", "Base", 142_000_000L),
    BASE_EN("base.en", "Base (English)", 142_000_000L, isEnglishOnly = true),
    BASE_Q5_1("base-q5_1", "Base Q5", 85_000_000L, isQuantized = true),
    BASE_EN_Q5_1("base.en-q5_1", "Base Q5 (English)", 85_000_000L, isEnglishOnly = true, isQuantized = true),
    BASE_Q8_0("base-q8_0", "Base Q8", 105_000_000L, isQuantized = true),
    
    // Small models (~466MB)
    SMALL("small", "Small", 466_000_000L),
    SMALL_EN("small.en", "Small (English)", 466_000_000L, isEnglishOnly = true),
    SMALL_EN_TDRZ("small.en-tdrz", "Small (English) TinyDiarize", 466_000_000L, isEnglishOnly = true),
    SMALL_Q5_1("small-q5_1", "Small Q5", 280_000_000L, isQuantized = true),
    SMALL_EN_Q5_1("small.en-q5_1", "Small Q5 (English)", 280_000_000L, isEnglishOnly = true, isQuantized = true),
    SMALL_Q8_0("small-q8_0", "Small Q8", 350_000_000L, isQuantized = true),
    
    // Medium models (~1.5GB)
    MEDIUM("medium", "Medium", 1_500_000_000L),
    MEDIUM_EN("medium.en", "Medium (English)", 1_500_000_000L, isEnglishOnly = true),
    MEDIUM_Q5_0("medium-q5_0", "Medium Q5", 900_000_000L, isQuantized = true),
    MEDIUM_EN_Q5_0("medium.en-q5_0", "Medium Q5 (English)", 900_000_000L, isEnglishOnly = true, isQuantized = true),
    MEDIUM_Q8_0("medium-q8_0", "Medium Q8", 1_100_000_000L, isQuantized = true),
    
    // Large models (~3GB)
    LARGE_V1("large-v1", "Large v1", 3_000_000_000L),
    LARGE_V2("large-v2", "Large v2", 3_000_000_000L),
    LARGE_V2_Q5_0("large-v2-q5_0", "Large v2 Q5", 1_800_000_000L, isQuantized = true),
    LARGE_V2_Q8_0("large-v2-q8_0", "Large v2 Q8", 2_200_000_000L, isQuantized = true),
    LARGE_V3("large-v3", "Large v3", 3_000_000_000L),
    LARGE_V3_Q5_0("large-v3-q5_0", "Large v3 Q5", 1_800_000_000L, isQuantized = true),
    LARGE_V3_TURBO("large-v3-turbo", "Large v3 Turbo", 1_600_000_000L),
    LARGE_V3_TURBO_Q5_0("large-v3-turbo-q5_0", "Large v3 Turbo Q5", 950_000_000L, isQuantized = true),
    LARGE_V3_TURBO_Q8_0("large-v3-turbo-q8_0", "Large v3 Turbo Q8", 1_200_000_000L, isQuantized = true);
    
    val downloadUrl: String
        get() = if (modelName.contains("tdrz")) {
            "https://huggingface.co/akashmjn/tinydiarize-whisper.cpp/resolve/main/ggml-$modelName.bin"
        } else {
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$modelName.bin"
        }
    
    val filename: String
        get() = "ggml-$modelName.bin"
    
    val sizeDisplay: String
        get() = when {
            sizeBytes >= 1_000_000_000L -> String.format("%.1f GB", sizeBytes / 1_000_000_000.0)
            else -> String.format("%.0f MB", sizeBytes / 1_000_000.0)
        }
}

/**
 * Supported languages for Whisper
 */
object WhisperLanguages {
    val languages = listOf(
        "auto" to "Auto-detect",
        "en" to "English",
        "zh" to "Chinese",
        "de" to "German",
        "es" to "Spanish",
        "ru" to "Russian",
        "ko" to "Korean",
        "fr" to "French",
        "ja" to "Japanese",
        "pt" to "Portuguese",
        "tr" to "Turkish",
        "pl" to "Polish",
        "ca" to "Catalan",
        "nl" to "Dutch",
        "ar" to "Arabic",
        "sv" to "Swedish",
        "it" to "Italian",
        "id" to "Indonesian",
        "hi" to "Hindi",
        "fi" to "Finnish",
        "vi" to "Vietnamese",
        "he" to "Hebrew",
        "uk" to "Ukrainian",
        "el" to "Greek",
        "ms" to "Malay",
        "cs" to "Czech",
        "ro" to "Romanian",
        "da" to "Danish",
        "hu" to "Hungarian",
        "ta" to "Tamil",
        "no" to "Norwegian",
        "th" to "Thai",
        "ur" to "Urdu",
        "hr" to "Croatian",
        "bg" to "Bulgarian",
        "lt" to "Lithuanian",
        "la" to "Latin",
        "mi" to "Maori",
        "ml" to "Malayalam",
        "cy" to "Welsh",
        "sk" to "Slovak",
        "te" to "Telugu",
        "fa" to "Persian",
        "lv" to "Latvian",
        "bn" to "Bengali",
        "sr" to "Serbian",
        "az" to "Azerbaijani",
        "sl" to "Slovenian",
        "kn" to "Kannada",
        "et" to "Estonian",
        "mk" to "Macedonian",
        "br" to "Breton",
        "eu" to "Basque",
        "is" to "Icelandic",
        "hy" to "Armenian",
        "ne" to "Nepali",
        "mn" to "Mongolian",
        "bs" to "Bosnian",
        "kk" to "Kazakh",
        "sq" to "Albanian",
        "sw" to "Swahili",
        "gl" to "Galician",
        "mr" to "Marathi",
        "pa" to "Punjabi",
        "si" to "Sinhala",
        "km" to "Khmer",
        "sn" to "Shona",
        "yo" to "Yoruba",
        "so" to "Somali",
        "af" to "Afrikaans",
        "oc" to "Occitan",
        "ka" to "Georgian",
        "be" to "Belarusian",
        "tg" to "Tajik",
        "sd" to "Sindhi",
        "gu" to "Gujarati",
        "am" to "Amharic",
        "yi" to "Yiddish",
        "lo" to "Lao",
        "uz" to "Uzbek",
        "fo" to "Faroese",
        "ht" to "Haitian Creole",
        "ps" to "Pashto",
        "tk" to "Turkmen",
        "nn" to "Nynorsk",
        "mt" to "Maltese",
        "sa" to "Sanskrit",
        "lb" to "Luxembourgish",
        "my" to "Myanmar",
        "bo" to "Tibetan",
        "tl" to "Tagalog",
        "mg" to "Malagasy",
        "as" to "Assamese",
        "tt" to "Tatar",
        "haw" to "Hawaiian",
        "ln" to "Lingala",
        "ha" to "Hausa",
        "ba" to "Bashkir",
        "jw" to "Javanese",
        "su" to "Sundanese"
    )
}
