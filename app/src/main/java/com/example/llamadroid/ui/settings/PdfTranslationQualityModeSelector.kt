package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.PdfTranslationQualityMode

@Composable
fun PdfTranslationQualityModeSelector(
    value: PdfTranslationQualityMode,
    onValueChange: (PdfTranslationQualityMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val description = when (value) {
        PdfTranslationQualityMode.BEST_QUALITY -> stringResource(R.string.pdf_translation_quality_best_desc)
        PdfTranslationQualityMode.BALANCED -> stringResource(R.string.pdf_translation_quality_balanced_desc)
        PdfTranslationQualityMode.FASTER -> stringResource(R.string.pdf_translation_quality_faster_desc)
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.pdf_translation_quality_title),
            fontWeight = FontWeight.Bold
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PdfTranslationQualityMode.values().forEach { mode ->
                FilterChip(
                    modifier = Modifier.fillMaxWidth(),
                    selected = value == mode,
                    onClick = { onValueChange(mode) },
                    label = {
                        Text(
                            when (mode) {
                                PdfTranslationQualityMode.BEST_QUALITY -> stringResource(R.string.pdf_translation_quality_best)
                                PdfTranslationQualityMode.BALANCED -> stringResource(R.string.pdf_translation_quality_balanced)
                                PdfTranslationQualityMode.FASTER -> stringResource(R.string.pdf_translation_quality_faster)
                            },
                            maxLines = 1
                        )
                    }
                )
            }
        }
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
