package com.example.llamadroid.ui.ai

import androidx.annotation.StringRes
import com.example.llamadroid.R

/** Contextual examples used only as gray placeholders; they never populate prompt state. */
enum class SdWorkflowPromptContext {
    FACE_NEUTRAL,
    EYES,
    HANDS,
    OBJECTS,
    INPAINT
}

data class SdWorkflowPromptExample(
    @StringRes val positiveRes: Int,
    @StringRes val negativeRes: Int
)

fun sdWorkflowPromptExample(context: SdWorkflowPromptContext): SdWorkflowPromptExample = when (context) {
    SdWorkflowPromptContext.FACE_NEUTRAL -> SdWorkflowPromptExample(
        R.string.sd_workflow_prompt_face_neutral_positive,
        R.string.sd_workflow_prompt_face_neutral_negative
    )
    SdWorkflowPromptContext.EYES -> SdWorkflowPromptExample(
        R.string.sd_workflow_prompt_eyes_positive,
        R.string.sd_workflow_prompt_eyes_negative
    )
    SdWorkflowPromptContext.HANDS -> SdWorkflowPromptExample(
        R.string.sd_workflow_prompt_hands_positive,
        R.string.sd_workflow_prompt_hands_negative
    )
    SdWorkflowPromptContext.OBJECTS -> SdWorkflowPromptExample(
        R.string.sd_workflow_prompt_objects_positive,
        R.string.sd_workflow_prompt_objects_negative
    )
    SdWorkflowPromptContext.INPAINT -> SdWorkflowPromptExample(
        R.string.sd_workflow_prompt_inpaint_positive,
        R.string.sd_workflow_prompt_inpaint_negative
    )
}

fun sdWorkflowPromptContextForDetector(path: String?): SdWorkflowPromptContext {
    val filename = path.orEmpty().substringAfterLast('/').lowercase()
    return when {
        "hand" in filename -> SdWorkflowPromptContext.HANDS
        "coco" in filename || "yolo" in filename && "object" in filename -> SdWorkflowPromptContext.OBJECTS
        "face" in filename -> SdWorkflowPromptContext.FACE_NEUTRAL
        // An unfamiliar detector must never suggest a face-specific repair. The
        // neutral inpaint example is the safest generic replacement guidance.
        else -> SdWorkflowPromptContext.INPAINT
    }
}
