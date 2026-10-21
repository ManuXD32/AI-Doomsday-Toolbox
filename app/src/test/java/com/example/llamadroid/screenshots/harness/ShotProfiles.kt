package com.example.llamadroid.screenshots.harness

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
    fun qualifiersFor(locale: ShotLocale): String = "${locale.tag}-$qualifiers"

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
    val outDir: String get() = System.getProperty("adt.shots.out")?.takeIf { it.isNotBlank() } ?: "build/screenshots"
    val repoRoot: String get() = System.getProperty("adt.shots.repoRoot")?.takeIf { it.isNotBlank() } ?: "."

    fun wants(key: String, profile: ShotProfile, locale: ShotLocale): Boolean =
        (profileFilter == null || profileFilter == profile) &&
            (localeFilter == null || localeFilter == locale) &&
            (keyFilter == null || key in keyFilter!!)
}
