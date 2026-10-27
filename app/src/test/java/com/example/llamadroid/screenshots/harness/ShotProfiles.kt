package com.example.llamadroid.screenshots.harness

import com.example.llamadroid.data.PixelThemeVariant

/** Device profile for a screenshot run: Robolectric qualifiers plus the exact output pixel box. */
data class ShotProfile(
    val key: String,
    /** Robolectric qualifiers without the locale prefix. */
    val qualifiers: String,
    val widthPx: Int,
    val heightPx: Int,
    /** Long side in px for the scaled guide copy (phone 1280, tablets 1280). */
    val guideLongSide: Int
) {
    fun qualifiersFor(locale: ShotLocale): String =
        "${locale.tag}-${if (ShotRunOptions.dark) qualifiers.replace("notnight", "night") else qualifiers}"

    companion object {
        /** 411.4 dp at 420 dpi: the qualifier rounds up, so the app is wrapped in an exact px box. */
        val PHONE = ShotProfile("phone", "w412dp-h732dp-port-notnight-420dpi", 1080, 1920, 1280)
        val TABLET7 = ShotProfile("tablet7", "w600dp-h960dp-port-notnight-xhdpi", 1200, 1920, 1280)
        val TABLET10 = ShotProfile("tablet10", "w800dp-h1280dp-port-notnight-xhdpi", 1600, 2560, 1280)

        val ALL = listOf(PHONE, TABLET7, TABLET10)

        fun byKey(key: String): ShotProfile =
            ALL.firstOrNull { it.key == key } ?: error("Unknown shot profile '$key'; use ${ALL.map { it.key }}")
    }
}

enum class ShotLocale(val tag: String) {
    EN("en"),
    ES("es");

    companion object {
        fun byTag(tag: String): ShotLocale =
            entries.firstOrNull { it.tag == tag } ?: error("Unknown shot locale '$tag'; use en or es")
    }
}

/** Opt-in run options read from `adt.shots.*` system properties (set by `-Padt.shots.*`). */
object ShotRunOptions {
    val profileFilter: ShotProfile? get() = System.getProperty("adt.shots.profile")?.takeIf { it.isNotBlank() }
        ?.let { ShotProfile.byKey(it) }
    val localeFilter: ShotLocale? get() = System.getProperty("adt.shots.locale")?.takeIf { it.isNotBlank() }
        ?.let { ShotLocale.byTag(it) }
    val keyFilter: Set<String>? get() = System.getProperty("adt.shots.keys")?.takeIf { it.isNotBlank() }
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    /** `-Padt.shots.theme=light|dark` (default light). Dark also switches the night qualifier. */
    val dark: Boolean get() = when (val value = System.getProperty("adt.shots.theme")?.trim()?.lowercase()) {
        null, "", "light" -> false
        "dark" -> true
        else -> error("Unknown adt.shots.theme '$value'; use light or dark")
    }
    /** `-Padt.shots.style=soft|pixel` (default soft). */
    val pixelStyle: Boolean get() = when (val value = System.getProperty("adt.shots.style")?.trim()?.lowercase()) {
        null, "", "soft" -> false
        "pixel" -> true
        else -> error("Unknown adt.shots.style '$value'; use soft or pixel")
    }
    /** `-Padt.shots.pixelVariant=candy_pop|pocket_lcd|arcade_night|sunset_cartridge` (enum name or storage value). */
    val pixelVariant: PixelThemeVariant get() {
        val value = System.getProperty("adt.shots.pixelVariant")?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return PixelThemeVariant.CANDY_POP
        return PixelThemeVariant.entries.firstOrNull { it.storageValue == value || it.name.lowercase() == value }
            ?: error("Unknown adt.shots.pixelVariant '$value'; use ${PixelThemeVariant.entries.map { it.storageValue }}")
    }
    /** Output sub-folder name for theme + style, for example `dark-pixel-arcade_night` or `light-soft`. */
    val themeStyleLabel: String get() =
        (if (dark) "dark" else "light") + "-" + (if (pixelStyle) "pixel-${pixelVariant.storageValue}" else "soft")
    val outDir: String get() = System.getProperty("adt.shots.out")?.takeIf { it.isNotBlank() } ?: "build/screenshots"
    val repoRoot: String get() = System.getProperty("adt.shots.repoRoot")?.takeIf { it.isNotBlank() } ?: "."

    fun wants(key: String, profile: ShotProfile, locale: ShotLocale): Boolean =
        (profileFilter == null || profileFilter == profile) &&
            (localeFilter == null || localeFilter == locale) &&
            (keyFilter == null || key in keyFilter!!)
}
