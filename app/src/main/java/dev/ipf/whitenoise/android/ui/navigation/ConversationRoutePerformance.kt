package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import kotlinx.coroutines.delay

/** Benchmark-only marker emitted after the route tween has consumed its final frame. */
@Composable
internal fun ConversationRouteSettledPerformanceMarker(
    conversationId: String?,
    transitionAnimated: Boolean,
) {
    if (!BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS) return

    val targetKey = conversationId ?: MAIN_SHELL_ROUTE_KEY
    var settledKey by remember { mutableStateOf<String?>(targetKey) }
    LaunchedEffect(targetKey, transitionAnimated) {
        if (settledKey == targetKey) return@LaunchedEffect
        settledKey = null
        if (transitionAnimated) {
            delay(CONVERSATION_ROUTE_TRANSITION_MILLIS.toLong())
            withFrameNanos { }
        }
        settledKey = targetKey
    }
    if (settledKey == targetKey) {
        Box(
            Modifier
                .size(1.dp)
                .performanceTestTag(
                    if (conversationId == null) {
                        PerformanceTestTags.MAIN_SHELL_ROUTE_SETTLED
                    } else {
                        PerformanceTestTags.CONVERSATION_ROUTE_SETTLED
                    },
                ),
        )
    }
}

internal const val CONVERSATION_ROUTE_TRANSITION_MILLIS = 240
private const val MAIN_SHELL_ROUTE_KEY = "main-shell"
