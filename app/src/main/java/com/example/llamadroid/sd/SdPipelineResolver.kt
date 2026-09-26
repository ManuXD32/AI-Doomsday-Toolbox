package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.service.SDConfig

/** Stable identifiers for preflight failures.  UI layers can localize these
 * codes without parsing a native error string. */
enum class SdPipelineIssueCode {
    UNKNOWN_FAMILY,
    UNKNOWN_LAYOUT,
    UNSUPPORTED_LAYOUT,
    DETECTED_FAMILY_CONFLICT,
    DETECTED_LAYOUT_CONFLICT,
    INVALID_ARTIFACT,
    MISSING_COMPONENT,
    MISSING_VAE,
    COMPONENT_INCOMPATIBLE
}

data class SdPipelineIssue(
    val code: SdPipelineIssueCode,
    val message: String,
    val role: SdComponentRole? = null,
    val blocking: Boolean = true,
    val evidence: String? = null
) {
    val isBlocking: Boolean
        get() = blocking
}

/**
 * The output of SD pipeline resolution.  Family describes architecture;
 * mainLayout describes packaging.  No caller should infer one from the
 * other when constructing a native command.
 */
data class SdResolvedPipeline(
    val family: SdModelFamily?,
    val variant: String?,
    val mainModelPath: String,
    val mainLayout: SdMainLayout,
    val requiredExternalRoles: Set<SdComponentRole>,
    val optionalExternalRoles: Set<SdComponentRole>,
    val resolvedComponents: Map<SdComponentRole, String>,
    val vaeFormatOverride: String? = null,
    val warnings: List<SdPipelineIssue> = emptyList(),
    val blockingIssues: List<SdPipelineIssue> = emptyList(),
    val spec: SdModelFamilySpec? = null,
    val inspection: SdArtifactInspection? = null
) {
    /** Alias used by command builders and diagnostics. */
    val mainPath: String
        get() = mainModelPath

    /** Concise layout spelling used in diagnostics and preflight gates. */
    val layout: SdMainLayout
        get() = mainLayout

    /** Alias that reads naturally at call sites dealing with external files. */
    val externalComponents: Map<SdComponentRole, String>
        get() = resolvedComponents

    /** Short aliases used by UI/preflight adapters. */
    val components: Map<SdComponentRole, String>
        get() = resolvedComponents

    val requiredRoles: Set<SdComponentRole>
        get() = requiredExternalRoles

    val optionalRoles: Set<SdComponentRole>
        get() = optionalExternalRoles

    /** All issues, preserving warnings before blocking failures. */
    val issues: List<SdPipelineIssue>
        get() = warnings + blockingIssues

    /** Alias retained for callers that call blocking issues errors. */
    val errors: List<SdPipelineIssue>
        get() = blockingIssues

    val isValid: Boolean
        get() = family != null && mainLayout != SdMainLayout.UNKNOWN && blockingIssues.isEmpty()

    fun pathForRole(role: SdComponentRole): String? = resolvedComponents[role]

    fun requireValid(): SdResolvedPipeline {
        if (!isValid) throw SdPipelineValidationException(this)
        return this
    }
}

/** Name requested by runtime integrations that prefer a resolution result. */
typealias SdPipelineResolution = SdResolvedPipeline

class SdPipelineValidationException(
    val pipeline: SdResolvedPipeline
) : IllegalStateException(
    pipeline.blockingIssues.joinToString("; ") { it.message }
        .ifBlank { "Stable Diffusion pipeline could not be resolved" }
)

/**
 * Resolve a configuration into a deterministic pipeline.  `inspection` is
 * structural evidence obtained from a bounded artifact-header inspection;
 * when it is absent, explicit configuration remains usable for legacy saved
 * commands but unknown architectures/layouts are never guessed at runtime.
 */
fun resolveSdPipeline(
    config: SDConfig,
    inspection: SdArtifactInspection? = null
): SdPipelineResolution = SdPipelineResolver.resolve(config, inspection)

fun resolveValidatedSdPipeline(
    config: SDConfig,
    inspection: SdArtifactInspection? = null
): SdResolvedPipeline = resolveSdPipeline(config, inspection).requireValid()

object SdPipelineResolver {
    fun resolve(
        config: SDConfig,
        inspection: SdArtifactInspection? = null
    ): SdPipelineResolution {
        val warnings = mutableListOf<SdPipelineIssue>()
        val errors = mutableListOf<SdPipelineIssue>()

        if (inspection != null && !inspection.isStructurallyUsable) {
            errors += SdPipelineIssue(
                code = SdPipelineIssueCode.INVALID_ARTIFACT,
                message = "The selected model artifact contains no usable tensors.",
                evidence = "tensorCount=${inspection.tensorCount}"
            )
        }

        val configuredFamily = SdModelFamily.fromStoredValue(config.modelFamily)
        val inferredType = config.inferredMainModelType()
        val filenameHint = inferSdFamily(inferredType, "", config.modelPath)
        val detectedFamily = inspection?.detectedFamily

        // A configured family is an explicit user choice and is authoritative
        // for runtime resolution. Structural evidence remains visible as a
        // conflict warning, but detection must never silently replace the
        // selected pipeline family.
        val family = when {
            configuredFamily != null -> configuredFamily
            detectedFamily != null -> detectedFamily
            else -> filenameHint.first
        }
        val variant = config.modelVariant?.trim()?.ifBlank { null }?.lowercase()
            ?: filenameHint.second

        if (configuredFamily != null && detectedFamily != null && configuredFamily != detectedFamily) {
            val issue = SdPipelineIssue(
                code = SdPipelineIssueCode.DETECTED_FAMILY_CONFLICT,
                message = "Configured family ${configuredFamily.storedValue} contradicts detected family ${detectedFamily.storedValue}.",
                blocking = false,
                evidence = "configured=${configuredFamily.storedValue},detected=${detectedFamily.storedValue}"
            )
            warnings += issue
        }

        val configuredLayout = config.modelLayout
        val detectedLayout = inspection?.artifactLayout
            ?.takeUnless { it == SdMainLayout.UNKNOWN || it == SdMainLayout.COMPONENT }
            ?: when (inspection?.detectedRole) {
                SdArtifactRole.FULL_MODEL,
                SdArtifactRole.MAIN_MODEL -> SdMainLayout.FULL_MODEL
                SdArtifactRole.STANDALONE_DIFFUSION -> SdMainLayout.STANDALONE_DIFFUSION
                else -> null
            }
        val layout = configuredLayout
            ?.takeUnless { it == SdMainLayout.UNKNOWN || it == SdMainLayout.COMPONENT }
            ?: detectedLayout
            ?: inferLegacyLayout(config, family, inferredType)

        if (configuredLayout != null && configuredLayout != SdMainLayout.UNKNOWN &&
            configuredLayout != SdMainLayout.COMPONENT && detectedLayout != null &&
            configuredLayout != detectedLayout
        ) {
            val issue = SdPipelineIssue(
                code = SdPipelineIssueCode.DETECTED_LAYOUT_CONFLICT,
                message = "Configured model layout ${configuredLayout.storedValue} contradicts detected layout ${detectedLayout.storedValue}.",
                blocking = false,
                evidence = "configured=${configuredLayout.storedValue},detected=${detectedLayout.storedValue}"
            )
            warnings += issue
        }

        if (family == null) {
            errors += SdPipelineIssue(
                code = SdPipelineIssueCode.UNKNOWN_FAMILY,
                message = "The architecture of this Stable Diffusion model could not be determined."
            )
        }
        if (layout == SdMainLayout.UNKNOWN || layout == SdMainLayout.COMPONENT) {
            errors += SdPipelineIssue(
                code = if (layout == SdMainLayout.COMPONENT) {
                    SdPipelineIssueCode.UNSUPPORTED_LAYOUT
                } else {
                    SdPipelineIssueCode.UNKNOWN_LAYOUT
                },
                message = "The selected model's full-versus-standalone layout could not be validated."
            )
        }

        val detectedRole = inspection?.detectedRole
        if (detectedRole != null && detectedRole !in setOf(
                SdArtifactRole.FULL_MODEL,
                SdArtifactRole.MAIN_MODEL,
                SdArtifactRole.STANDALONE_DIFFUSION
            ) && inspection.confidence == SdInspectionConfidence.HIGH
        ) {
            warnings += SdPipelineIssue(
                code = SdPipelineIssueCode.UNSUPPORTED_LAYOUT,
                message = "The selected artifact is a ${detectedRole.storedValue} component, not a runnable main model.",
                blocking = false,
                evidence = "detectedRole=${detectedRole.storedValue}"
            )
        }

        val spec = family?.let { resolveSdFamilySpec(it, variant) }
        val required = requiredExternalRoles(
            family = family,
            layout = layout,
            spec = spec,
            inspection = inspection,
            mode = config.mode
        )
        val optional = optionalExternalRoles(
            family = family,
            layout = layout,
            spec = spec
        )
        val components = config.componentPaths()

        required.forEach { role ->
            if (components[role].isNullOrBlank()) {
                errors += SdPipelineIssue(
                    code = if (role == SdComponentRole.VAE) {
                        SdPipelineIssueCode.MISSING_VAE
                    } else {
                        SdPipelineIssueCode.MISSING_COMPONENT
                    },
                    message = "Missing required Stable Diffusion component: ${role.name}.",
                    role = role
                )
            }
        }

        // A high-confidence full SD3 inspection which proves that the VAE is
        // absent is not a complete checkpoint unless an external override is
        // supplied.  The override is intentionally allowed because sdcpp can
        // load a replacement VAE with --vae.
        if (family == SdModelFamily.SD3 && layout == SdMainLayout.FULL_MODEL &&
            inspection?.confidence == SdInspectionConfidence.HIGH &&
            inspection.containsVae.not() && config.vaePath.isNullOrBlank()
        ) {
            errors += SdPipelineIssue(
                code = SdPipelineIssueCode.MISSING_VAE,
                message = "This SD3 full-model artifact does not contain a VAE; select a compatible external SD3 VAE or a complete checkpoint.",
                role = SdComponentRole.VAE
            )
        }

        val vaeFormat = if (family == SdModelFamily.SD3 && layout == SdMainLayout.STANDALONE_DIFFUSION) {
            "sd3"
        } else {
            null
        }

        return SdResolvedPipeline(
            family = family,
            variant = variant,
            mainModelPath = config.modelPath,
            mainLayout = layout,
            requiredExternalRoles = required,
            optionalExternalRoles = optional,
            resolvedComponents = components,
            vaeFormatOverride = vaeFormat,
            warnings = warnings,
            blockingIssues = errors,
            spec = spec,
            inspection = inspection
        )
    }

    private fun requiredExternalRoles(
        family: SdModelFamily?,
        layout: SdMainLayout,
        spec: SdModelFamilySpec?,
        inspection: SdArtifactInspection?,
        mode: com.example.llamadroid.service.SDMode
    ): Set<SdComponentRole> {
        if (family == null || layout == SdMainLayout.UNKNOWN || layout == SdMainLayout.COMPONENT) {
            return emptySet()
        }
        if (family == SdModelFamily.SD3) {
            if (layout == SdMainLayout.STANDALONE_DIFFUSION) {
                return linkedSetOf(
                    SdComponentRole.VAE,
                    SdComponentRole.CLIP_L,
                    SdComponentRole.CLIP_G,
                    SdComponentRole.T5XXL
                )
            }
            // Full SD3 checkpoints can carry any/all text encoders internally.
            // An absent or unknown inspection conservatively requires the
            // external encoders; a proven internal encoder is not duplicated.
            val evidence = inspection
            return linkedSetOf<SdComponentRole>().apply {
                if (evidence?.containsClipL != true) add(SdComponentRole.CLIP_L)
                if (evidence?.containsClipG != true) add(SdComponentRole.CLIP_G)
                if (evidence?.containsT5xxl != true) add(SdComponentRole.T5XXL)
            }
        }
        return if (layout == SdMainLayout.STANDALONE_DIFFUSION) {
            spec?.requiredRoles.orEmpty().toMutableSet().apply {
                if (mode == com.example.llamadroid.service.SDMode.IMG2IMG &&
                    spec?.requiresVisionForImg2Img == true
                ) {
                    add(SdComponentRole.LLM_VISION)
                }
            }
        } else {
            emptySet()
        }
    }

    private fun optionalExternalRoles(
        family: SdModelFamily?,
        layout: SdMainLayout,
        spec: SdModelFamilySpec?
    ): Set<SdComponentRole> {
        if (family == SdModelFamily.SD3 && layout == SdMainLayout.FULL_MODEL) {
            return linkedSetOf(SdComponentRole.VAE, SdComponentRole.LORA)
        }
        if (layout != SdMainLayout.STANDALONE_DIFFUSION) return emptySet()
        return spec?.optionalRoles.orEmpty()
    }
}

private fun SDConfig.inferredMainModelType(): ModelType = when {
    modelLayout == SdMainLayout.FULL_MODEL -> ModelType.SD_CHECKPOINT
    modelLayout == SdMainLayout.STANDALONE_DIFFUSION -> ModelType.SD_DIFFUSION
    llmPath != null || clipLPath != null || clipGPath != null || t5xxlPath != null -> ModelType.SD_DIFFUSION
    else -> ModelType.SD_CHECKPOINT
}

private fun inferLegacyLayout(
    config: SDConfig,
    family: SdModelFamily?,
    inferredType: ModelType
): SdMainLayout = when {
    inferredType == ModelType.SD_CHECKPOINT && family == SdModelFamily.CHECKPOINT -> SdMainLayout.FULL_MODEL
    inferredType == ModelType.SD_DIFFUSION && family != null -> SdMainLayout.STANDALONE_DIFFUSION
    // A configured non-checkpoint family has historically represented a
    // standalone diffusion artifact.  Keep that migration path explicit while
    // leaving an entirely unknown artifact unresolved.
    config.modelFamily != null && family != SdModelFamily.CHECKPOINT -> SdMainLayout.STANDALONE_DIFFUSION
    else -> SdMainLayout.UNKNOWN
}

private fun SDConfig.componentPaths(): Map<SdComponentRole, String> = buildMap {
    vaePath?.trim()?.takeIf { it.isNotBlank() }?.let { put(SdComponentRole.VAE, it) }
    taePath?.trim()?.takeIf { it.isNotBlank() }?.let { put(SdComponentRole.TAE, it) }
    clipLPath?.trim()?.takeIf { it.isNotBlank() }?.let { put(SdComponentRole.CLIP_L, it) }
    clipGPath?.trim()?.takeIf { it.isNotBlank() }?.let { put(SdComponentRole.CLIP_G, it) }
    t5xxlPath?.trim()?.takeIf { it.isNotBlank() }?.let { put(SdComponentRole.T5XXL, it) }
    llmPath?.trim()?.takeIf { it.isNotBlank() }?.let { put(SdComponentRole.LLM, it) }
    llmVisionPath?.trim()?.takeIf { it.isNotBlank() }?.let { put(SdComponentRole.LLM_VISION, it) }
    controlNetPath?.trim()?.takeIf { it.isNotBlank() }?.let { put(SdComponentRole.CONTROLNET, it) }
    photoMakerPath?.trim()?.takeIf { it.isNotBlank() }?.let { put(SdComponentRole.PHOTOMAKER, it) }
}
