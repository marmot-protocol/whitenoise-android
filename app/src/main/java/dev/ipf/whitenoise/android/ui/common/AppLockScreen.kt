package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.outlineButtonColors

/**
 * Opaque, secure app-owned cover. Only the caller's native authentication result may remove it;
 * retry requests authentication and does not optimistically reveal protected content.
 * [evaluating] represents the actual persisted lock decision, before the platform prompt can open.
 */
@Composable
@Suppress("FunctionNaming", "LongMethod") // The complete opaque cover shares one authentication state.
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
        AdaptiveContent(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(WhiteNoiseSpacing.CompactScreenMargin),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(WhiteNoiseSpacing.Section))
                Text(
                    text = stringResource(R.string.app_locked_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(WhiteNoiseSpacing.Related))
                Text(text = stringResource(R.string.app_locked_body), textAlign = TextAlign.Center)
                error?.let {
                    Spacer(Modifier.height(WhiteNoiseSpacing.FormField))
                    Text(
                        text = it.resolve(context),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("app.lock.error").semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Spacer(Modifier.height(WhiteNoiseSpacing.Section))
                if (evaluating) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.app_unlock_checking),
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .padding(top = WhiteNoiseSpacing.Related)
                                .testTag("app.lock.checking")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.testTag("app.unlock"),
                        border = amoledOutlineBorder(),
                        colors = outlineButtonColors(),
                    ) {
                        Text(stringResource(R.string.app_unlock))
                    }
                }
            }
        }
    }
}
