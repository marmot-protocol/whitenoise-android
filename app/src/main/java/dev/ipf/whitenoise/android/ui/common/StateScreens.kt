@file:Suppress(
    "FunctionNaming",
    "MatchingDeclarationName",
) // Compose recovery primitives intentionally share this file.

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.TransientNotice

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

/** Fills an established destination with neutral progress feedback. */
@Composable
fun LoadingScreen() {
    Box(
        Modifier.fillMaxSize().testTag(FULL_SCREEN_LOADING_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/** Presents branded startup progress before any interactive destination exists. */
@Composable
fun StartupLoadingScreen() {
    Box(
        Modifier.fillMaxSize().padding(24.dp).testTag(STARTUP_LOADING_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WhiteNoiseLogoLockup(size = 72.dp)
            Text(
                text = stringResource(R.string.white_noise),
                style = MaterialTheme.typography.headlineMedium,
            )
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp,
            )
            Text(
                text = stringResource(R.string.starting_securely),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

internal const val FULL_SCREEN_LOADING_TEST_TAG = "full-screen-loading"
internal const val STARTUP_LOADING_TEST_TAG = "startup-loading"
internal const val WARM_RESUME_USEFUL_SURFACE_TEST_TAG = "warm-resume-useful-surface"

/** Marks a rendered, interactive destination rather than the shell's possibly blank root. */
@Composable
internal fun WarmResumeUsefulSurface(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().testTag(WARM_RESUME_USEFUL_SURFACE_TEST_TAG)) {
        content()
    }
}

/** Presents a non-blocking confirmation while preserving the destination beneath it. */
@Composable
internal fun InlineConfirmationNotice(
    notice: TransientNotice,
    modifier: Modifier = Modifier,
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
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
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
        }
    }
}

/**
 * Presents a retryable failure with copyable diagnostics.
 *
 * [copyActionColor] lets a caller provide a foreground with sufficient contrast for its local
 * recovery surface while the default preserves the existing Material text-button treatment.
 */
@Composable
internal fun ErrorContent(
    title: String,
    error: ErrorPresentation,
    onRetry: () -> Unit,
    copyActionColor: Color? = null,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val message = error.message.resolve(context)
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(40.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            SelectionContainer {
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (error.retryable) {
                Button(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.retry))
                }
            }
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(error.report)) },
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = copyActionColor ?: MaterialTheme.colorScheme.primary,
                    ),
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.copy))
            }
        }
    }
}

/** Keeps a loaded surface visible while exposing copy and optional retry actions. */
@Composable
internal fun InlineErrorBanner(
    error: ErrorPresentation,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                error.message.resolve(context),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(error.report)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy))
            }
            if (error.retryable) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}
