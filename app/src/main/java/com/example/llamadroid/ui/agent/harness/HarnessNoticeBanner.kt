package com.example.llamadroid.ui.agent.harness

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel

/** Management errors stay visible beside the editor that produced them. */
@Composable
internal fun HarnessNoticeBanner(notice: HarnessNoticeUi, onAction: (NativeHarnessUiAction) -> Unit) {
    AppStatePanel(
        kind = AppStateKind.Error,
        title = notice.title ?: notice.titleRes?.let { stringResource(it) }
            ?: stringResource(R.string.harness_notice_error),
        message = notice.message ?: notice.messageRes?.let { stringResource(it, *notice.messageArgs.toTypedArray()) }.orEmpty(),
        actionLabel = stringResource(R.string.harness_dismiss_notice),
        onAction = { onAction(NativeHarnessUiAction.DismissNotice) },
    )
}
