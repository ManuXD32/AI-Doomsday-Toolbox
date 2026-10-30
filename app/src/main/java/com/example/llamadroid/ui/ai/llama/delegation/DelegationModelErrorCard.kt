package com.example.llamadroid.ui.ai.llama.delegation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.llamadroid.R
import com.example.llamadroid.harness.HarnessModelFailure
import com.example.llamadroid.harness.HarnessModelFailureExplain
import com.example.llamadroid.harness.HarnessModelFailureKind
import com.example.llamadroid.harness.HarnessModelFailureLog
import com.example.llamadroid.ui.components.AppSectionCard

/** "Last model error" card: plain-language cause, raw metadata and a clipboard diagnostics button. */
@Composable
internal fun DelegationModelErrorCard(runtimeId: String, failure: HarnessModelFailure) {
    val context = LocalContext.current
    val copiedText = stringResource(R.string.native_chat_delegation_viewer_model_error_copied)
    val plain = when (HarnessModelFailureExplain.kind(failure)) {
        HarnessModelFailureKind.SERVER_UNSTABLE -> stringResource(R.string.native_chat_delegation_viewer_model_error_unstable)
        HarnessModelFailureKind.CONTEXT_TOO_LARGE -> stringResource(R.string.native_chat_delegation_viewer_model_error_context)
        HarnessModelFailureKind.SERVER_DIED ->
            if (HarnessModelFailureExplain.likelyOutOfMemory(failure)) {
                stringResource(
                    R.string.native_chat_delegation_viewer_model_error_died_oom,
                    HarnessModelFailureExplain.formatGb(failure.freeRamBytes),
                )
            } else stringResource(R.string.native_chat_delegation_viewer_model_error_died)
        HarnessModelFailureKind.SERVER_REJECTED ->
            failure.body?.let { stringResource(R.string.native_chat_delegation_viewer_model_error_rejected_body, it) }
                ?: stringResource(R.string.native_chat_delegation_viewer_model_error_rejected)
        HarnessModelFailureKind.CONNECTION_LOST -> stringResource(R.string.native_chat_delegation_viewer_model_error_connection)
        HarnessModelFailureKind.ADAPTER_STREAM -> stringResource(R.string.native_chat_delegation_viewer_model_error_adapter)
        HarnessModelFailureKind.LITERT_CRASH -> stringResource(R.string.native_chat_delegation_viewer_model_error_litert)
        HarnessModelFailureKind.OTHER -> stringResource(R.string.native_chat_delegation_viewer_model_error_other)
    }
    val details = stringResource(
        R.string.native_chat_delegation_viewer_model_error_details,
        failure.code,
        "%.1f s".format(java.util.Locale.US, failure.elapsedMs / 1000.0),
        failure.promptTokensProcessed?.let { done ->
            failure.promptTokens?.let { "$done/$it" } ?: done.toString()
        } ?: failure.promptTokens?.toString() ?: "?",
        failure.generatedTokens.toString(),
    )
    AppSectionCard {
        Text(
            stringResource(R.string.native_chat_delegation_viewer_model_error_title),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.error
        )
        Text(plain, style = MaterialTheme.typography.bodyMedium)
        Text(
            details,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = {
                // Background stops and locked-screen speed often explain a "server stopped" error.
                val text = listOfNotNull(
                    HarnessModelFailureLog.get(context, runtimeId).diagnosticsText(),
                    com.example.llamadroid.diagnostics.ProcessExitRecorder.summary(context),
                    com.example.llamadroid.service.perf.LockedScreenSpeedRecorder.summary(context)
                ).joinToString("\n")
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("DeepSeek Harness diagnostics", text))
                Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.native_chat_delegation_viewer_model_error_copy),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
