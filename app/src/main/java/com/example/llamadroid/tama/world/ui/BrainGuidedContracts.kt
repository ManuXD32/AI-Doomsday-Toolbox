package com.example.llamadroid.tama.world.ui

import androidx.compose.runtime.Immutable
import com.example.llamadroid.tama.world.training.BrainGuidedPhase

/** Presentation-only state for the bounded lesson flow. */
@Immutable
data class BrainGuidedUiState(
    val phase: BrainGuidedPhase = BrainGuidedPhase.IDLE,
    val curriculumId: Int = 1,
    val activeMillis: Long = 0L,
    val sessionMillis: Long = 0L,
    val sessionBudgetMillis: Long = 5L * 60L * 1000L,
    val candidateCheckpointId: String? = null,
    val evaluationPassed: Boolean = false,
    val applied: Boolean = false,
    val referenceWasAdopted: Boolean = false,
    val gateFailure: String? = null
)

/** Brain-specific callbacks kept separate from the shared world UI contract. */
data class BrainGuidedCallbacks(
    val onLessonSelected: (Int) -> Unit = {},
    val onStart: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onFinishAndEvaluate: () -> Unit = {},
    val onApply: (String) -> Unit = {},
    val onOpenWorld: () -> Unit = {}
)
