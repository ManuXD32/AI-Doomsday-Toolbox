package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import java.util.Locale

/**
 * Parameter-residency choices understood by stable-diffusion.cpp.
 *
 * `AUTO` deliberately emits no `--params-backend` option.  This is important
 * for backwards compatibility: absence of the option lets the native runtime
 * select its normal residency policy.  `DISK` is the only explicit residency
 * currently exposed by the upstream CLI.
 */
enum class SdParamsResidency(val storedValue: String, val cliValue: String?) {
    AUTO("auto", null),
    DISK("disk", "disk");

    companion object {
        fun fromStoredValue(value: String?): SdParamsResidency = when {
            value.equals("disk", ignoreCase = true) -> DISK
            value.equals("auto", ignoreCase = true) || value.equals("default", ignoreCase = true) -> AUTO
            else -> AUTO
        }
    }
}

/** Official stable-diffusion.cpp parameter modules. */
enum class SdParamsModule(val cliName: String) {
    DIFFUSION("diffusion"),
    TE("te"),
    CLIP_VISION("clip_vision"),
    VAE("vae"),
    CONTROLNET("controlnet"),
    PHOTOMAKER("photomaker"),
    UPSCALER("upscaler"),
    DETECTOR("detector");

    companion object {
        private val aliases = mapOf(
            "text_encoder" to TE,
            "text-encoder" to TE,
            "text" to TE,
            "clip_l" to TE,
            "clip-g" to TE,
            "clip_g" to TE,
            "t5" to TE,
            "t5xxl" to TE,
            "clipvision" to CLIP_VISION,
            "clip-vision" to CLIP_VISION,
            "photo_maker" to PHOTOMAKER,
            "photo-maker" to PHOTOMAKER
        )

        fun fromCliName(value: String?): SdParamsModule? {
            val normalized = value?.trim()?.lowercase(Locale.US).orEmpty()
            return entries.firstOrNull { it.cliName == normalized } ?: aliases[normalized]
        }
    }
}

enum class SdParamsBackendPreset(val storedValue: String) {
    NORMAL("normal"),
    TEXT_ENCODERS_ON_DISK("te_disk"),
    EVERYTHING_ON_DISK("disk"),
    MIXED("mixed");

    companion object {
        fun fromProfile(profile: SdParamsBackendProfile): SdParamsBackendPreset = when {
            profile.assignments.isEmpty() -> NORMAL
            profile.cliValue == "disk" -> EVERYTHING_ON_DISK
            profile.assignments.keys == setOf(SdParamsModule.TE) &&
                profile.assignments[SdParamsModule.TE] == SdParamsResidency.DISK -> TEXT_ENCODERS_ON_DISK
            else -> MIXED
        }
    }
}

/** Components that the current local run will actually load. */
data class SdActiveRunComponents(
    val diffusion: Boolean = false,
    val textEncoders: Boolean = false,
    val clipVision: Boolean = false,
    val vae: Boolean = false,
    val controlNet: Boolean = false,
    val photoMaker: Boolean = false,
    val upscaler: Boolean = false,
    val detector: Boolean = false
)

/** Keeps the run editor free of placement rows for inactive optional modules. */
fun SdActiveRunComponents.paramsModules(): Set<SdParamsModule> = buildSet {
    if (diffusion) add(SdParamsModule.DIFFUSION)
    if (textEncoders) add(SdParamsModule.TE)
    if (clipVision) add(SdParamsModule.CLIP_VISION)
    if (vae) add(SdParamsModule.VAE)
    if (controlNet) add(SdParamsModule.CONTROLNET)
    if (photoMaker) add(SdParamsModule.PHOTOMAKER)
    if (upscaler) add(SdParamsModule.UPSCALER)
    if (detector) add(SdParamsModule.DETECTOR)
}

/**
 * A normalized, local parameter-residency profile.
 *
 * The map uses module names rather than model paths.  This is intentional:
 * regular LoRAs are not an upstream parameter-backend module and therefore do
 * not appear here.  The profile can be persisted on a model row and restored
 * for a later run without retaining any prompt or path data.
 */
data class SdParamsBackendProfile(
    val assignments: Map<SdParamsModule, SdParamsResidency> = emptyMap(),
    val warnings: List<String> = emptyList()
) {
    /** Alias for callers that use the wording from the upstream documentation. */
    val moduleAssignments: Map<SdParamsModule, SdParamsResidency>
        get() = assignments

    /** Canonical CLI value, or null when the normal preset is selected. */
    val cliValue: String?
        get() = assignments.toCanonicalCliValue()

    /** Canonical storage value; this is safe to persist in ModelEntity. */
    val storedValue: String
        get() = cliValue ?: SdParamsResidency.AUTO.storedValue

    val preset: SdParamsBackendPreset
        get() = SdParamsBackendPreset.fromProfile(this)

    fun withModule(module: SdParamsModule, residency: SdParamsResidency): SdParamsBackendProfile =
        copy(assignments = assignments + (module to residency))

    companion object {
        val NORMAL = SdParamsBackendProfile()
        val TEXT_ENCODERS_ON_DISK = SdParamsBackendProfile(
            assignments = mapOf(SdParamsModule.TE to SdParamsResidency.DISK)
        )
        val EVERYTHING_ON_DISK = SdParamsBackendProfile(
            assignments = SdParamsModule.entries.associateWith { SdParamsResidency.DISK }
        )

        /**
         * Parse either the compact upstream values (`disk`, `te=disk`) or the
         * persisted legacy value (`auto`). Invalid entries are ignored and
         * described in [warnings], never allowed to become a malformed flag.
         */
        fun parse(
            value: String?,
            legacyMode: String? = null
        ): SdParamsBackendProfile {
            val raw = value?.trim().orEmpty()
            val effective = if (raw.isBlank() || raw.equals("auto", ignoreCase = true)) {
                legacyMode?.trim().orEmpty().takeIf { it.equals("disk", ignoreCase = true) }
                    ?: raw
            } else {
                raw
            }
            if (effective.isBlank() || effective.equals("auto", ignoreCase = true) ||
                effective.equals("normal", ignoreCase = true)
            ) return NORMAL
            if (effective.equals("disk", ignoreCase = true)) return EVERYTHING_ON_DISK

            val assignments = linkedMapOf<SdParamsModule, SdParamsResidency>()
            val warnings = mutableListOf<String>()
            effective.split(',').forEach { token ->
                val pair = token.trim().split('=', limit = 2)
                if (pair.size != 2) {
                    warnings += "Ignored malformed parameter-backend assignment '${token.trim()}'."
                    return@forEach
                }
                val moduleName = pair[0].trim()
                val module = SdParamsModule.fromCliName(moduleName)
                val residencyValue = pair[1].trim()
                val residency = when {
                    residencyValue.equals("disk", ignoreCase = true) -> SdParamsResidency.DISK
                    residencyValue.equals("auto", ignoreCase = true) ||
                        residencyValue.equals("default", ignoreCase = true) -> SdParamsResidency.AUTO
                    else -> null
                }
                if (module == null) {
                    warnings += "Ignored unsupported parameter-backend module '$moduleName'."
                } else if (residency == null) {
                    warnings += "Ignored unsupported parameter residency '$residencyValue' for ${module.cliName}."
                } else {
                    val previous = assignments[module]
                    if (previous != null && previous != residency) {
                        warnings += "Conflicting ${module.cliName} residency preferences resolved to disk."
                        assignments[module] = SdParamsResidency.DISK
                    } else {
                        assignments[module] = residency
                    }
                }
            }

            // CLIP-L, CLIP-G and T5 are one upstream `te` group. The aliases
            // above already collapse these names; this warning is retained for
            // callers that need a visible explanation when mixed preferences
            // were supplied through separate remembered fields.
            return SdParamsBackendProfile(
                assignments = assignments.filterValues { it != SdParamsResidency.AUTO },
                warnings = warnings
            )
        }

        fun forPreset(preset: SdParamsBackendPreset): SdParamsBackendProfile = when (preset) {
            SdParamsBackendPreset.NORMAL -> NORMAL
            SdParamsBackendPreset.TEXT_ENCODERS_ON_DISK -> TEXT_ENCODERS_ON_DISK
            SdParamsBackendPreset.EVERYTHING_ON_DISK -> EVERYTHING_ON_DISK
            SdParamsBackendPreset.MIXED -> NORMAL
        }
    }
}

/** Resolve a persisted profile, falling back to the pre-112 `auto`/`disk` field. */
fun resolveSdParamsBackendProfile(
    spec: String?,
    legacyMode: String? = null
): SdParamsBackendProfile = SdParamsBackendProfile.parse(spec, legacyMode)

/** Top-level alias useful to command builders and tests. */
fun normalizeSdParamsBackendSpec(
    spec: String?,
    legacyMode: String? = null
): String = resolveSdParamsBackendProfile(spec, legacyMode).storedValue

/**
 * Return only the assignments that can affect one persisted artifact.
 *
 * A model row is also the place where the user's residency preference is
 * remembered, so copying the complete run profile to every selected row would
 * make (for example) a VAE appear to own diffusion and text-encoder modules.
 * Keeping the projection here makes persistence deterministic and keeps the
 * same rules available to UI, import, and non-Compose callers.
 */
fun SdParamsBackendProfile.forArtifact(model: ModelEntity): SdParamsBackendProfile {
    val relevantModules = when (model.type) {
        ModelType.SD_VAE -> setOf(SdParamsModule.VAE)
        ModelType.SD_TAE -> setOf(SdParamsModule.VAE)
        ModelType.SD_CLIP_L,
        ModelType.SD_CLIP_G,
        ModelType.SD_T5XXL -> setOf(SdParamsModule.TE)
        ModelType.SD_CONTROLNET -> setOf(SdParamsModule.CONTROLNET)
        ModelType.SD_PHOTOMAKER -> setOf(SdParamsModule.PHOTOMAKER)
        ModelType.SD_UPSCALER -> setOf(SdParamsModule.UPSCALER)
        ModelType.SD_ADETAILER -> setOf(SdParamsModule.DETECTOR)
        ModelType.SD_CLIP_VISION,
        ModelType.SD_IP_ADAPTER -> setOf(SdParamsModule.CLIP_VISION)
        ModelType.SD_DIFFUSION -> setOf(SdParamsModule.DIFFUSION)
        ModelType.SD_CHECKPOINT -> {
            // A checkpoint/full model can contain the diffusion model and
            // bundled conditioners/VAE. The inspected layout is deliberately
            // consulted only for this projection; it never changes the
            // configured model role or pipeline resolution.
            when (SdMainLayout.fromStoredValue(model.sdArtifactLayout)) {
                SdMainLayout.FULL_MODEL -> setOf(
                    SdParamsModule.DIFFUSION,
                    SdParamsModule.TE,
                    SdParamsModule.VAE
                )
                else -> setOf(SdParamsModule.DIFFUSION)
            }
        }
        else -> emptySet()
    }
    return copy(assignments = assignments.filterKeys { it in relevantModules })
}

/**
 * Merge the remembered preferences for the currently selected artifacts.
 * Encoder rows are intentionally one `te` group: upstream cannot place
 * CLIP-L, CLIP-G, and T5 independently. Explicitly conflicting memories are
 * resolved to disk and surfaced as a warning instead of silently choosing one.
 */
fun resolveSdParamsBackendProfileForArtifacts(
    artifacts: Iterable<ModelEntity?>
): SdParamsBackendProfile {
    val selected = artifacts.filterNotNull()
    if (selected.isEmpty()) return SdParamsBackendProfile.NORMAL

    val projected = selected.map { model ->
        resolveSdParamsBackendProfile(model.sdParamsBackendSpec, model.sdParamsBackendMode)
            .forArtifact(model)
    }
    // `forArtifact` intentionally drops AUTO assignments because they should
    // not be emitted to native. Keep an additional raw view so an explicit
    // `te=auto` on one encoder can still be distinguished from an unconfigured
    // legacy row when another encoder remembers `te=disk`.
    val explicitAssignments = selected.map { model ->
        parseExplicitAssignments(model.sdParamsBackendSpec)
    }
    val merged = linkedMapOf<SdParamsModule, SdParamsResidency>()
    val warnings = mutableListOf<String>()
    SdParamsModule.entries.forEach { module ->
        val values = projected.mapNotNull { it.assignments[module] }.distinct()
        when {
            values.any { it == SdParamsResidency.DISK } -> {
                merged[module] = SdParamsResidency.DISK
                if (values.size > 1 || explicitAssignments.any { it[module] == SdParamsResidency.AUTO }) {
                    warnings += "Conflicting ${module.cliName} residency preferences resolved to disk."
                }
            }
            values.any { it == SdParamsResidency.AUTO } -> merged[module] = SdParamsResidency.AUTO
        }
    }
    return SdParamsBackendProfile(
        assignments = merged.filterValues { it != SdParamsResidency.AUTO },
        warnings = warnings + projected.flatMap { it.warnings }
    )
}

private fun parseExplicitAssignments(spec: String?): Map<SdParamsModule, SdParamsResidency> {
    val raw = spec?.trim().orEmpty()
    if (raw.isBlank() || raw.equals("auto", ignoreCase = true) ||
        raw.equals("normal", ignoreCase = true) || raw.equals("disk", ignoreCase = true)
    ) return emptyMap()
    return raw.split(',').mapNotNull { token ->
        val pair = token.trim().split('=', limit = 2)
        if (pair.size != 2) return@mapNotNull null
        val module = SdParamsModule.fromCliName(pair[0]) ?: return@mapNotNull null
        val residency = when (pair[1].trim().lowercase(Locale.US)) {
            "disk" -> SdParamsResidency.DISK
            "auto", "default" -> SdParamsResidency.AUTO
            else -> return@mapNotNull null
        }
        module to residency
    }.toMap()
}

/** Validate and normalize a module assignment map before command construction. */
fun resolveSdParamsBackendProfile(
    assignments: Map<String, SdParamsResidency>,
    legacyMode: String? = null
): SdParamsBackendProfile {
    val encoded = assignments.entries.joinToString(",") { (module, residency) ->
        "${module.trim()}=${residency.storedValue}"
    }
    return resolveSdParamsBackendProfile(encoded, legacyMode)
}

private fun Map<SdParamsModule, SdParamsResidency>.toCanonicalCliValue(): String? {
    val effective = entries
        .filter { it.value != SdParamsResidency.AUTO }
        .sortedBy { entry -> SdParamsModule.entries.indexOf(entry.key) }
    if (effective.isEmpty()) return null
    if (effective.size == SdParamsModule.entries.size && effective.all { it.value == SdParamsResidency.DISK }) {
        return SdParamsResidency.DISK.cliValue
    }
    return effective.joinToString(",") { (module, residency) ->
        "${module.cliName}=${residency.cliValue ?: SdParamsResidency.AUTO.storedValue}"
    }
}
