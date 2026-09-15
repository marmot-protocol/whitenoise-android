package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseHeaderScroll

/** Synchronizes the real restored grid position with its pinned header, including programmatic filter restoration. */
@OptIn(ExperimentalMaterial3Api::class)
internal fun Modifier.trackSharedContentHeader(state: LazyGridState): Modifier =
    composed {
        val behavior = LocalWhiteNoiseHeaderScroll.current
        DisposableEffect(behavior) { onDispose { behavior?.state?.contentOffset = 0f } }
        LaunchedEffect(state, behavior) {
            if (behavior == null) return@LaunchedEffect
            snapshotFlow {
                Triple(
                    state.layoutInfo.totalItemsCount,
                    state.firstVisibleItemIndex,
                    state.firstVisibleItemScrollOffset,
                ) to behavior.state.heightOffsetLimit
            }.collect { (position, heightOffsetLimit) ->
                val (count, index, offset) = position
                behavior.state.contentOffset =
                    when {
                        count == 0 -> 0f
                        index > 0 -> heightOffsetLimit
                        else -> -offset.toFloat()
                    }
            }
        }
        this
    }
