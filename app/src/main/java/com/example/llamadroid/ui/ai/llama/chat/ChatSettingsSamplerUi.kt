package com.example.llamadroid.ui.ai.llama.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.ai.llama.LlamaChatSamplerValidation
import com.example.llamadroid.ui.ai.llama.LlamaSamplerField

@StringRes
internal fun samplerLabelRes(field: LlamaSamplerField): Int = when (field) {
    LlamaSamplerField.TEMPERATURE -> R.string.llama_sampler_temperature_label
    LlamaSamplerField.TOP_P -> R.string.llama_sampler_top_p_label
    LlamaSamplerField.TOP_K -> R.string.llama_sampler_top_k_label
    LlamaSamplerField.MIN_P -> R.string.llama_sampler_min_p_label
    LlamaSamplerField.REPETITION_PENALTY -> R.string.llama_sampler_repetition_penalty_label
    LlamaSamplerField.PRESENCE_PENALTY -> R.string.chat_sampler_presence_penalty
    LlamaSamplerField.FREQUENCY_PENALTY -> R.string.chat_sampler_frequency_penalty
    LlamaSamplerField.REPEAT_LAST_N -> R.string.chat_sampler_repeat_last_n
    LlamaSamplerField.TYPICAL_P -> R.string.chat_sampler_typical_p
    LlamaSamplerField.TOP_N_SIGMA -> R.string.chat_sampler_top_n_sigma
    LlamaSamplerField.XTC_PROBABILITY -> R.string.chat_sampler_xtc_probability
    LlamaSamplerField.XTC_THRESHOLD -> R.string.chat_sampler_xtc_threshold
    LlamaSamplerField.DRY_MULTIPLIER -> R.string.chat_sampler_dry_multiplier
    LlamaSamplerField.DRY_BASE -> R.string.chat_sampler_dry_base
    LlamaSamplerField.DRY_ALLOWED_LENGTH -> R.string.chat_sampler_dry_allowed_length
    LlamaSamplerField.DRY_PENALTY_LAST_N -> R.string.chat_sampler_dry_penalty_last_n
    LlamaSamplerField.DYNATEMP_RANGE -> R.string.chat_sampler_dynatemp_range
    LlamaSamplerField.DYNATEMP_EXPONENT -> R.string.chat_sampler_dynatemp_exponent
    LlamaSamplerField.MIROSTAT -> R.string.chat_sampler_mirostat
    LlamaSamplerField.MIROSTAT_TAU -> R.string.chat_sampler_mirostat_tau
    LlamaSamplerField.MIROSTAT_ETA -> R.string.chat_sampler_mirostat_eta
}

/** One-line description for the advanced fields; the basic five need none. */
@StringRes
internal fun samplerDescriptionRes(field: LlamaSamplerField): Int? = when (field) {
    LlamaSamplerField.PRESENCE_PENALTY -> R.string.chat_sampler_presence_penalty_desc
    LlamaSamplerField.FREQUENCY_PENALTY -> R.string.chat_sampler_frequency_penalty_desc
    LlamaSamplerField.REPEAT_LAST_N -> R.string.chat_sampler_repeat_last_n_desc
    LlamaSamplerField.TYPICAL_P -> R.string.chat_sampler_typical_p_desc
    LlamaSamplerField.TOP_N_SIGMA -> R.string.chat_sampler_top_n_sigma_desc
    LlamaSamplerField.XTC_PROBABILITY -> R.string.chat_sampler_xtc_probability_desc
    LlamaSamplerField.XTC_THRESHOLD -> R.string.chat_sampler_xtc_threshold_desc
    LlamaSamplerField.DRY_MULTIPLIER -> R.string.chat_sampler_dry_multiplier_desc
    LlamaSamplerField.DRY_BASE -> R.string.chat_sampler_dry_base_desc
    LlamaSamplerField.DRY_ALLOWED_LENGTH -> R.string.chat_sampler_dry_allowed_length_desc
    LlamaSamplerField.DRY_PENALTY_LAST_N -> R.string.chat_sampler_dry_penalty_last_n_desc
    LlamaSamplerField.DYNATEMP_RANGE -> R.string.chat_sampler_dynatemp_range_desc
    LlamaSamplerField.DYNATEMP_EXPONENT -> R.string.chat_sampler_dynatemp_exponent_desc
    LlamaSamplerField.MIROSTAT -> R.string.chat_sampler_mirostat_desc
    LlamaSamplerField.MIROSTAT_TAU -> R.string.chat_sampler_mirostat_tau_desc
    LlamaSamplerField.MIROSTAT_ETA -> R.string.chat_sampler_mirostat_eta_desc
    else -> null
}

/** Numeric row with the Auto chip for one catalog field. */
@Composable
internal fun ChatSettingsState.SamplerRow(field: LlamaSamplerField) {
    val liteRt = activeServerIsLiteRt
    val invalidNumber = stringResource(R.string.llama_sampler_invalid_number)
    val invalidRange = stringResource(R.string.llama_sampler_invalid_range)
    LlamaSamplerNumericField(
        label = stringResource(samplerLabelRes(field)),
        value = samplerTexts[field].orEmpty(),
        rangeText = LlamaChatSamplerValidation.rangeText(field, liteRt),
        auto = isSamplerAuto(field),
        integer = field.integer,
        error = samplerErrorMessage.takeIf { samplerErrorField == field },
        onAutoChange = { setSamplerAuto(field, it, liteRt) },
        onValueChange = { next -> updateSamplerText(field, next, liteRt, invalidNumber, invalidRange) }
    )
}

/** Mirostat: Auto plus the three modes as chips. */
@Composable
private fun ChatSettingsState.SamplerChoiceRow(field: LlamaSamplerField) {
    val current = samplerValues[field]?.toInt()
    val options = listOf(
        null to stringResource(R.string.chat_sampler_auto),
        0 to stringResource(R.string.chat_sampler_mirostat_off),
        1 to stringResource(R.string.chat_sampler_mirostat_v1),
        2 to stringResource(R.string.chat_sampler_mirostat_v2)
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (choice, label) ->
            FilterChip(
                selected = current == choice,
                onClick = { setSamplerChoice(field, choice) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
    }
}

/** Collapsible llama.cpp-only block with one description line per field and a reset button. */
@Composable
internal fun ChatSettingsState.AdvancedSamplingBlock() {
    var expanded by rememberSaveable("advanced_sampling") { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.chat_sampler_advanced_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.native_chat_settings_collapse else R.string.native_chat_settings_expand
            )
        )
    }
    if (!expanded) return
    LlamaSamplerField.entries.filter { !it.basic }.forEach { field ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (field.choice) {
                Text(
                    text = stringResource(samplerLabelRes(field)),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            samplerDescriptionRes(field)?.let { res ->
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (field.choice) SamplerChoiceRow(field) else SamplerRow(field)
        }
    }
}

/** "Reset all to Auto" button shared by the sampling block. */
@Composable
internal fun ChatSettingsState.ResetSamplersButton() {
    OutlinedButton(onClick = { resetSamplersToAuto() }, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.chat_sampler_reset_all),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
