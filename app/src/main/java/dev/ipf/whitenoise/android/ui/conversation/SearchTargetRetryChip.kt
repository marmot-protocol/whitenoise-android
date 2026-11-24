@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.shapedPressFeedback
import dev.ipf.whitenoise.android.ui.common.shapedTouchTarget
import dev.ipf.whitenoise.android.ui.navigation.FloatingControlPillHeight
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseElevation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val SEARCH_TARGET_RETRY_CHIP_TAG = "conversation.search_target_retry"

/**
 * Offered when a global-search result's window could not load (#1945): one 48dp target that names what failed and
 * retries the same, still-current navigation. It is announced politely once, without taking focus, and search
 * state on the chat list stays as it was.
 */
@Composable
internal fun SearchTargetRetryChip(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val press = remember { MutableInteractionSource() }
    // A 34 dp pill, the floating controls' pill height, inside an unchanged 48 dp target. The highlight is
    // drawn on the pill, and the pill grows with large text rather than clipping it.
    Box(
        modifier =
            modifier
                .testTag(SEARCH_TARGET_RETRY_CHIP_TAG)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .shapedTouchTarget(press, onClick = onRetry),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shadowElevation = WhiteNoiseElevation.FloatingControl,
            modifier = Modifier.heightIn(min = FloatingControlPillHeight),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .shapedPressFeedback(press, CircleShape)
                        .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = Dimens.spaceXs),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = null,
                    modifier = Modifier.padding(end = WhiteNoiseSpacing.Related).size(18.dp),
                )
                Text(
                    text = stringResource(R.string.search_result_open_failed_retry),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
