@file:Suppress("FunctionNaming") // Compose startup destinations follow framework naming conventions.

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ErrorPresentation

/** Literal prototype loading chrome; indeterminate feedback never estimates native bootstrap progress. */
@Composable
internal fun StartupProgressContent() {
    StatePage(
        modifier = Modifier.testTag(STARTUP_LOADING_TEST_TAG),
        icon = R.drawable.ic_white_noise_mark,
        progress = true,
        progressLabel = stringResource(R.string.startup_loading_message),
    )
}

/**
 * Startup-only recovery preserves the native retry grant and safe report while adopting prototype chrome.
 * A retained-profile chooser is unavailable until native bootstrap establishes an eligible account route.
 */
@Composable
internal fun StartupFailureScreen(
    title: String,
    error: ErrorPresentation,
    onShareDiagnostics: (() -> Unit)? = null,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    StartupStatusPage(Modifier.testTag(STARTUP_FAILURE_TEST_TAG)) {
        StatePage(
            title = title,
            message = error.message.resolve(context),
            selectableMessage = true,
            messageModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            primary = if (error.retryable) StateAction(stringResource(R.string.retry), onRetry, testTag = STARTUP_RETRY_TEST_TAG) else null,
            secondary =
                StateAction(
                    stringResource(R.string.copy),
                    { clipboard.setText(AnnotatedString(error.report)) },
                    testTag = STARTUP_COPY_TEST_TAG,
                ),
            footer = {
                if (onShareDiagnostics != null) {
                    Text(
                        stringResource(R.string.startup_diagnostics_share_notice),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onShareDiagnostics, modifier = Modifier.testTag(STARTUP_SHARE_TEST_TAG)) {
                        Text(stringResource(R.string.startup_diagnostics_share))
                    }
                }
            },
        )
    }
}

internal const val STARTUP_FAILURE_TEST_TAG = "startup-failure"
internal const val STARTUP_RETRY_TEST_TAG = "startup-retry"
internal const val STARTUP_COPY_TEST_TAG = "startup-copy-report"
internal const val STARTUP_SHARE_TEST_TAG = "startup-share-diagnostics"

/** Keeps no-snapshot recovery in the preparation branch so Retry cannot remount the shell's bind. */
@Composable
internal fun StartupLocalProjectionScreen(
    error: ErrorPresentation?,
    onRetry: () -> Unit,
) {
    if (error == null) {
        StartupLoadingScreen()
    } else {
        StartupFailureScreen(stringResource(R.string.white_noise_couldnt_start), error, onRetry = onRetry)
    }
}

/** Safe scaffold around a [StatePage]; banners use the app root's own slot over this surface. */
@Composable
private fun StartupStatusPage(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // No top bar to sit below.
    WhiteNoiseScaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topNoticeSlot = false,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
    }
}
