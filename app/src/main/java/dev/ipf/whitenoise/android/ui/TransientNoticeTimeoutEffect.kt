package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalAccessibilityManager
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.state.TransientNoticeKind
import kotlinx.coroutines.delay

internal const val TRANSIENT_NOTICE_DURATION_MILLIS = 2_000L

@Composable
@Suppress("FunctionNaming")
internal fun TransientNoticeTimeoutEffect(
    notice: TransientNotice?,
    onClear: (TransientNotice) -> Unit,
) {
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(notice, accessibilityManager) {
        if (notice == null || notice.kind == TransientNoticeKind.Partial) return@LaunchedEffect
        val timeout =
            accessibilityManager?.calculateRecommendedTimeoutMillis(
                TRANSIENT_NOTICE_DURATION_MILLIS,
                containsIcons = true,
                containsText = true,
            ) ?: TRANSIENT_NOTICE_DURATION_MILLIS
        delay(timeout)
        onClear(notice)
    }
}
