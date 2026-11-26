@file:Suppress(
    "FunctionNaming",
    "MatchingDeclarationName",
) // Compose recovery primitives intentionally share this file.

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.state.TransientNoticeKind

internal enum class LoadFailurePlacement {
    None,
    FullScreen,
    Inline,
}

/** Chooses full-screen startup recovery or an inline retained-content banner. */
internal fun loadFailurePlacement(
    hasFailure: Boolean,
    hasLoadedContent: Boolean,
): LoadFailurePlacement =
    when {
        !hasFailure -> LoadFailurePlacement.None
        hasLoadedContent -> LoadFailurePlacement.Inline
        else -> LoadFailurePlacement.FullScreen
    }

/**
 * Fills an established destination with neutral progress feedback. A [message] appears under the
 * indicator once the caller knows the wait is longer than usual, so a long spinner reads as intentional
 * rather than as a hang.
 */
@Composable
fun LoadingScreen(message: String? = null) {
    StateProgressPage(
        modifier = Modifier.testTag(FULL_SCREEN_LOADING_TEST_TAG),
        progressLabel = message,
        progressLabelModifier = Modifier.testTag(FULL_SCREEN_LOADING_MESSAGE_TEST_TAG),
    )
}

/** Presents the prototype startup indicator while native bootstrap owns all progress and readiness. */
@Composable
fun StartupLoadingScreen() {
    StartupProgressContent()
}

internal const val FULL_SCREEN_LOADING_TEST_TAG = "full-screen-loading"
internal const val FULL_SCREEN_LOADING_MESSAGE_TEST_TAG = "full-screen-loading-message"
internal const val STARTUP_LOADING_TEST_TAG = "startup-loading"
internal const val WARM_RESUME_USEFUL_SURFACE_TEST_TAG = "warm-resume-useful-surface"

/** Marks a rendered, interactive destination rather than the shell's possibly blank root. */
@Composable
internal fun WarmResumeUsefulSurface(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().testTag(WARM_RESUME_USEFUL_SURFACE_TEST_TAG)) {
        content()
    }
}

/** Keeps supplemental status or a dismissible partial outcome visible in the notice region. */
@Composable
internal fun InlineConfirmationNotice(
    notice: TransientNotice,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painterResource(
                    if (notice.kind == TransientNoticeKind.Partial) R.drawable.ic_warning else R.drawable.ic_info,
                ),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(notice.title.resolve(context), style = MaterialTheme.typography.bodyMedium)
                notice.detail?.let {
                    Text(
                        it.resolve(context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.dismiss))
                }
            }
        }
    }
}

/**
 * Presents a retryable failure with copyable diagnostics.
 */
@Composable
internal fun ErrorContent(
    title: String,
    error: ErrorPresentation,
    onRetry: () -> Unit,
) {
    val copier = rememberClipboardCopier()
    val context = LocalContext.current
    StatePage(
        icon = R.drawable.ic_info,
        title = title,
        message = error.message.resolve(context),
        selectableMessage = true,
        primary =
            if (error.retryable) StateAction(stringResource(R.string.retry), onRetry, R.drawable.ic_refresh) else null,
        secondary =
            StateAction(
                stringResource(R.string.copy),
                { copier.copy(error.report) },
                R.drawable.ic_content_copy,
            ),
    )
}
