package com.example.llamadroid.tama.world.training

import kotlin.math.ceil

/**
 * Shared controller/presentation decision for an evaluated candidate. The controller remains the
 * authority at Apply time; the projection uses this same pure rule to hide an unavailable button.
 */
internal fun guidedAdoptionGateFailure(
    comparison: PolicyComparison,
    actualAdoptedReference: Boolean
): String? {
    val curriculum = runCatching { CurriculumLevel.fromId(comparison.candidate.curriculumId) }
        .getOrDefault(CurriculumLevel.VISIBLE_TARGET)
    val requiredSuccesses = ceil(
        CurriculumCatalog.definition(curriculum).successThreshold * comparison.candidate.seedCount
    ).toInt()
    if (comparison.candidate.successfulEpisodes < requiredSuccesses) {
        return "guided_gate_success_threshold"
    }
    if (comparison.candidate.invalidActionRate > comparison.current.invalidActionRate) {
        return "guided_gate_invalid_actions"
    }
    if (comparison.candidate.stuckRate > comparison.current.stuckRate) {
        return "guided_gate_stuck_rate"
    }
    if (comparison.candidate.criticalNeedsRate > comparison.current.criticalNeedsRate) {
        return "guided_gate_critical_needs"
    }
    if (actualAdoptedReference && !comparison.candidateImproves) {
        return "guided_gate_no_improvement"
    }
    return null
}
