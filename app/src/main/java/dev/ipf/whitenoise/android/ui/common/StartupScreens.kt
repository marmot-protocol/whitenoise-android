@file:Suppress("FunctionNaming") // Compose startup destinations follow framework naming conventions.

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ErrorPresentation

/** Literal prototype loading chrome; indeterminate feedback never estimates native bootstrap progress. */
@Composable
internal fun StartupProgressContent() {
    // The mark, the indicator and the caption sit together in the middle of the screen, as the app has
    // always started; the scrollable status column is for recovery copy that may not fit.
    Box(
        Modifier.fillMaxSize().padding(24.dp).testTag(STARTUP_LOADING_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_white_noise_mark),
                contentDescription = null,
                modifier = Modifier.size(STARTUP_MARK_SIZE).testTag(STARTUP_MARK_TEST_TAG),
            )
            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            Text(
                stringResource(R.string.startup_loading_message),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private val STARTUP_MARK_SIZE = 96.dp
internal const val STARTUP_MARK_TEST_TAG = "startup-mark"

/**
 * Startup-only recovery preserves the native retry grant and safe report while adopting prototype chrome.
 * A retained-profile chooser is unavailable until native bootstrap establishes an eligible account route.
 */
@Composable
internal fun StartupFailureScreen(
    title: String,
    error: ErrorPresentation,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    StartupStatusPage(Modifier.testTag(STARTUP_FAILURE_TEST_TAG)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        SelectionContainer {
            Text(error.message.resolve(context), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (error.retryable) {
            WhiteNoiseButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag(STARTUP_RETRY_TEST_TAG)) {
                Text(stringResource(R.string.retry))
            }
        }
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(error.report)) },
            modifier = Modifier.testTag(STARTUP_COPY_TEST_TAG),
        ) { Text(stringResource(R.string.copy)) }
    }
}

internal const val STARTUP_FAILURE_TEST_TAG = "startup-failure"
internal const val STARTUP_RETRY_TEST_TAG = "startup-retry"
internal const val STARTUP_COPY_TEST_TAG = "startup-copy-report"

/** Pinned AccessUi.StartupScreen: safe scaffold, adaptive pane, 520 dp column, 24 dp inset and 16 dp gaps. */
@Composable
private fun StartupStatusPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    WhiteNoiseScaffold(modifier = modifier, contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        AdaptiveContent(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .whiteNoiseVerticalScroll(rememberScrollState())
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}
