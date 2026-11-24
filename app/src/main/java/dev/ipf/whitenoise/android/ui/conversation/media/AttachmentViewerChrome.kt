package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBarNavigation
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha

/** Locale-independent handle for a viewer chrome's leading Close or Back control. */
internal const val ATTACHMENT_VIEWER_CLOSE_TAG = "attachment.viewer.close"

/** The minimum height of a viewer chrome row above its insets, matching the shared page top bar. */
internal val ATTACHMENT_VIEWER_CHROME_HEIGHT = 64.dp

/** Colours for a viewer chrome: a docked reader header or a translucent bar over edge-to-edge media. */
internal object AttachmentViewerChromeDefaults {
    /** A reader's header sits on the page surface, like a page top bar. */
    @Composable
    fun dockedColor(): Color = MaterialTheme.colorScheme.surface

    /** Over edge-to-edge media the chrome is the themed background under a heavy scrim, so media shows through. */
    @Composable
    fun overlayColor(): Color = MaterialTheme.colorScheme.background.copy(alpha = ScrimAlpha.HEAVY)
}

/**
 * The one toolbar every attachment viewer uses: image, video, PDF and document, text, Nostr and the photo editor.
 *
 * It leads with [navigation], which for a viewer is Close (an X labelled Close) because a viewer returns to the
 * message it opened from (see docs/ui-patterns.md); the icon and its label always match. The [title] uses one
 * style everywhere, with an optional [subtitle], and [titleContent] replaces both for a viewer whose title row
 * carries its own controls. [actions] hold the viewer's direct actions and [overflow] its existing More menu,
 * passed through unchanged. The chrome draws only theme colours, [containerColor] chooses docked or overlay,
 * and [windowInsets] pads the row clear of the status bar and side cutouts while the media stays edge to edge.
 * [navigationButton] replaces the default control for a viewer with its own press feedback.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun AttachmentViewerChrome(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: WhiteNoiseTopBarNavigation = WhiteNoiseTopBarNavigation.Close,
    closeEnabled: Boolean = true,
    containerColor: Color = AttachmentViewerChromeDefaults.dockedColor(),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    navigationButton: (@Composable () -> Unit)? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    overflow: (@Composable () -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = containerColor, contentColor = contentColor) {
        Row(
            modifier =
                Modifier
                    .windowInsetsPadding(windowInsets)
                    .fillMaxWidth()
                    .heightIn(min = ATTACHMENT_VIEWER_CHROME_HEIGHT)
                    .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationButton != null) {
                navigationButton()
            } else {
                AttachmentViewerNavigationButton(navigation, closeEnabled, onClose)
            }
            if (titleContent != null) {
                titleContent()
            } else {
                AttachmentViewerTitle(title, subtitle, Modifier.weight(1f))
            }
            actions()
            overflow?.invoke()
        }
    }
}

/** The default leading control: an X labelled Close, or a back arrow labelled Back. */
@Suppress("FunctionNaming")
@Composable
private fun AttachmentViewerNavigationButton(
    navigation: WhiteNoiseTopBarNavigation,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).testTag(ATTACHMENT_VIEWER_CLOSE_TAG),
    ) {
        Icon(
            painterResource(
                when (navigation) {
                    WhiteNoiseTopBarNavigation.Close -> R.drawable.ic_close
                    WhiteNoiseTopBarNavigation.Back -> R.drawable.ic_arrow_back
                },
            ),
            contentDescription =
                stringResource(
                    when (navigation) {
                        WhiteNoiseTopBarNavigation.Close -> R.string.close
                        WhiteNoiseTopBarNavigation.Back -> R.string.back
                    },
                ),
        )
    }
}

/** The shared viewer title: one line of `titleMedium`, with an optional quieter second line. */
@Suppress("FunctionNaming")
@Composable
internal fun AttachmentViewerTitle(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    subtitleModifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 8.dp)) {
        Text(
            text = title,
            modifier = titleModifier,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                modifier = subtitleModifier,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
