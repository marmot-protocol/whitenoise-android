@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val SEARCH_TARGET_RETRY_CHIP_TAG = "conversation.search_target_retry"

/**
 * Offered when a global-search result's window could not load (#1945): one 48dp button that names what failed and
 * retries the same, still-current navigation. It is announced politely once, without taking focus, and search
 * state on the chat list stays as it was.
 */
@Composable
internal fun SearchTargetRetryChip(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shadowElevation = 2.dp,
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .testTag(SEARCH_TARGET_RETRY_CHIP_TAG)
                .clickable(role = Role.Button, onClick = onRetry)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = 12.dp),
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
