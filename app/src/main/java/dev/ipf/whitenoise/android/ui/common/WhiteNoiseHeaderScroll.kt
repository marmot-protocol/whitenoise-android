package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/** Sync restored/programmatic list position as well as native nested scroll without replacing the list state. */
@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.trackWhiteNoiseHeader(state: LazyListState): Modifier =
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
