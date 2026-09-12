@file:Suppress("FunctionNaming") // Compose startup destinations follow framework naming conventions.

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
    StartupStatusPage(Modifier.testTag(STARTUP_LOADING_TEST_TAG)) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.startup_loading_message),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

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
