package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/** True only when the route transition has landed and its destination is ready. */
internal fun conversationRouteSettled(
    currentStateMatchesTarget: Boolean,
    transitionRunning: Boolean,
    destinationContentReady: Boolean,
): Boolean = currentStateMatchesTarget && !transitionRunning && destinationContentReady

/**
 * Benchmark-only marker emitted once the route observably settles: the
 * transition reached its target state and stopped, the destination content is
 * ready, and one further frame has rendered. A nominal duration wait would
 * diverge from reality under dropped or delayed frames, so the marker never
 * assumes the tween's configured length.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ConversationRouteSettledPerformanceMarker(
    conversationId: String?,
    routeTransition: Transition<*>,
    destinationContentReady: Boolean,
) {
    if (!BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS) return

    val targetKey = conversationId ?: MAIN_SHELL_ROUTE_KEY
    var settledKey by remember { mutableStateOf<String?>(targetKey) }
    LaunchedEffect(targetKey, destinationContentReady) {
        if (settledKey == targetKey) return@LaunchedEffect
        settledKey = null
        if (!destinationContentReady) return@LaunchedEffect
        snapshotFlow {
            conversationRouteSettled(
                currentStateMatchesTarget = routeTransition.currentState == routeTransition.targetState,
                transitionRunning = routeTransition.isRunning,
                destinationContentReady = true,
            )
        }.filter { it }.first()
        withFrameNanos { }
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

/** Extra frames the outgoing route is retained past its tween before release. */
internal const val CONVERSATION_ROUTE_EXIT_RETENTION_SLACK_MILLIS = 32L
private const val MAIN_SHELL_ROUTE_KEY = "main-shell"
