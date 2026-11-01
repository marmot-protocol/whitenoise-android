package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.vectorResource
import dev.ipf.whitenoise.android.R

/** Immutable artwork may cross windows; each control still owns its own painter and interaction state. */
@Immutable
internal data class MediaViewerChromeIcons(
    val close: ImageVector,
    val more: ImageVector,
    val share: ImageVector,
    val forward: ImageVector,
)

/** A live screen retains artwork lazily across viewer sessions, until its resources or theme change. */
@Composable
internal fun rememberMediaViewerChromeIconProvider(): Lazy<MediaViewerChromeIcons> {
    val resources = LocalResources.current
    val theme = LocalContext.current.theme
    val configuration = LocalConfiguration.current
    return remember(resources, theme, configuration) {
        lazy(LazyThreadSafetyMode.NONE) {
            MediaViewerChromeIcons(
                close = ImageVector.vectorResource(theme = theme, res = resources, resId = R.drawable.ic_close),
                more = ImageVector.vectorResource(theme = theme, res = resources, resId = R.drawable.ic_more_vert),
                share = ImageVector.vectorResource(theme = theme, res = resources, resId = R.drawable.ic_share),
                forward = ImageVector.vectorResource(theme = theme, res = resources, resId = R.drawable.ic_forward),
            )
        }
    }
}
