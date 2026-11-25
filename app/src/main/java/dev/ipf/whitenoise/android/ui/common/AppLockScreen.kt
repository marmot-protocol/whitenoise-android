package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText

/**
 * Opaque, secure app-owned cover. Only the caller's native authentication result may remove it;
 * retry requests authentication and does not optimistically reveal protected content.
 * [evaluating] represents the actual persisted lock decision, before the platform prompt can open.
 */
@Composable
@Suppress("FunctionNaming") // The complete opaque cover shares one authentication state.
internal fun AppLockScreen(
    error: AppText?,
    onRetry: () -> Unit,
    evaluating: Boolean = false,
) {
    WindowSecureFlag()
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxSize().testTag("app.lock"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            StatePage(
                icon = R.drawable.ic_lock,
                title = stringResource(R.string.app_locked_title),
                message = stringResource(R.string.app_locked_body),
                progress = evaluating,
                progressLabel = stringResource(R.string.app_unlock_checking).takeIf { evaluating },
                progressLabelModifier = Modifier.testTag("app.lock.checking"),
                primary =
                    if (evaluating) {
                        null
                    } else {
                        StateAction(stringResource(R.string.app_unlock), onRetry, testTag = "app.unlock")
                    },
                extra = {
                    error?.let {
                        Text(
                            text = it.resolve(context),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("app.lock.error")
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                },
            )
        }
    }
}
