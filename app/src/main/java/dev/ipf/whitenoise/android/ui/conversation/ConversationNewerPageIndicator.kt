package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.delay

/**
 * How long a newer page must be in flight before the indicator shows, so a page that lands within a
 * frame or two never flashes it.
 */
internal const val NEWER_PAGE_INDICATOR_DELAY_MS = 300L

/**
 * Whether the newer-page indicator should be on screen: true once a newer page has been in flight
 * for [NEWER_PAGE_INDICATOR_DELAY_MS], false the moment it lands or fails.
 */
@Composable
internal fun rememberNewerPageIndicatorVisible(isLoadingNewer: Boolean): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(isLoadingNewer) {
        if (isLoadingNewer) {
            delay(NEWER_PAGE_INDICATOR_DELAY_MS)
            visible = true
        } else {
            visible = false
        }
    }
    return visible
}

/**
 * A quiet spinner in the transcript's overlay column while a newer page is being fetched — the
 * forward counterpart of the older-history header. It lives beside the jump button rather than in
 * the list, so the bottom edge never moves for it and it can never be mistaken for a row. It takes
 * the jump button's 42 dp footprint around its 34 dp disc, so the two discs line up in the column,
 * and reads to a screen reader as one element.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ConversationNewerPageIndicator(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.conversation_loading_newer)
    Box(
        modifier = modifier.size(42.dp).semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shadowElevation = 2.dp,
            modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}
