package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class ConversationRouteFrameStabilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cachedDmConvergesMonotonicallyWhenFinalFramesHydrateAt60HzInLtr() {
        verifyForwardRoute(LayoutDirection.Ltr, "Cached DM", frameMillis = 16L)
    }

    @Test
    fun cachedGroupConvergesMonotonicallyWhenFinalFramesHydrateAt90HzInRtl() {
        verifyForwardRoute(LayoutDirection.Rtl, "Cached group", frameMillis = 11L)
    }

    @Test
    fun cachedDmConvergesMonotonicallyWhenFinalFramesHydrateAt120HzInLtr() {
        verifyForwardRoute(LayoutDirection.Ltr, "Cached DM", frameMillis = 8L)
    }

    @Test
    fun rapidBackReversalDoesNotSnapReturningChatListLayerAt120Hz() {
        val state = RouteHarnessState()
        composeRule.setContent {
            RouteHarness(state, LayoutDirection.Ltr, "Cached DM")
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { state.route = "Cached DM" }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(1L)
        composeRule.runOnIdle { }
        repeat(8) {
            composeRule.mainClock.advanceTimeBy(8L)
            composeRule.runOnIdle { }
        }
        val sourceBefore = composeRule.onNodeWithTag(SOURCE_TAG).getUnclippedBoundsInRoot()
        composeRule.onNodeWithTag(DESTINATION_TAG).assertExists()

        composeRule.runOnUiThread { state.route = null }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(1L)
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeBy(8L)
        composeRule.runOnIdle { }
        val sourceAfter = composeRule.onNodeWithTag(SOURCE_TAG).getUnclippedBoundsInRoot()

        assertContinuousReversalStep("source", sourceBefore.left.value, sourceAfter.left.value)
        assertTrue(kotlin.math.abs(sourceAfter.left.value) <= CONVERSATION_ROUTE_LAYER_TRAVEL.value)
    }

    @Test
    fun reducedMotionNeverTranslatesOrFadesEitherLayer() {
        listOf(0f, 0.5f, 1f).forEach { progress ->
            listOf(false, true).forEach { conversationRoute ->
                assertEquals(
                    0f,
                    conversationRouteLayerTranslation(progress, conversationRoute, suppressMotion = true),
                )
                assertEquals(
                    1f,
                    conversationRouteLayerAlpha(progress, conversationRoute, suppressMotion = true),
                )
            }
        }
    }

    private fun assertContinuousReversalStep(
        label: String,
        before: Float,
        after: Float,
    ) {
        assertTrue(
            "$label snapped when Back interrupted enter: $before -> $after",
            kotlin.math.abs(after - before) <= MAX_120_HZ_REVERSAL_STEP_DP,
        )
    }

    private fun verifyForwardRoute(
        layoutDirection: LayoutDirection,
        destinationLabel: String,
        frameMillis: Long,
    ) {
        val state = RouteHarnessState()
        composeRule.setContent {
            RouteHarness(state, layoutDirection, destinationLabel)
        }
        composeRule.mainClock.autoAdvance = false
        val outgoingBounds = mutableListOf<DpRect>()
        val forward = captureForwardFrames(state, destinationLabel, outgoingBounds, frameMillis)
        assertStableOuterBounds(outgoingBounds)
        assertConverges(forward, "forward destination")
        assertAlphaConverges(forward, conversationRoute = true, label = "forward destination")
        composeRule.onNodeWithTag(DESTINATION_TAG).assertIsDisplayed()

        val back = captureBackFrames(state, frameMillis)
        assertConverges(back, "back destination")
        assertAlphaConverges(back, conversationRoute = false, label = "back destination")
        composeRule.onNodeWithTag(SOURCE_TAG).assertIsDisplayed()
    }

    @Composable
    @Suppress("FunctionNaming")
    private fun RouteHarness(
        state: RouteHarnessState,
        layoutDirection: LayoutDirection,
        destinationLabel: String,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            WhiteNoiseTheme {
                Surface(Modifier.requiredSize(360.dp, 640.dp)) {
                    val transition = updateTransition(state.route, label = "conversation route test")
                    val isRunning = transition.isRunning
                    SideEffect { state.transitionRunning = isRunning }
                    ConversationRouteAnimatedContent(
                        transition = transition,
                        routeForwardDirection = conversationRouteForwardDirection(layoutDirection),
                        suppressMotion = false,
                        contentKey = { it ?: "chat-list" },
                    ) { destination ->
                        if (destination == null) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                                    .testTag(SOURCE_TAG),
                            )
                        } else {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .testTag(DESTINATION_TAG),
                            ) {
                                HydratingRouteChrome(state.hydrationStage, destinationLabel)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    @Suppress("FunctionNaming")
    private fun ColumnScope.HydratingRouteChrome(
        hydrationStage: Int,
        destinationLabel: String,
    ) {
        Box(
            Modifier
                .height(if (hydrationStage >= 1) 96.dp else 56.dp)
                .testTag(TOP_CHROME_TAG),
        ) {
            val title =
                if (hydrationStage >= 1) {
                    "$destinationLabel · 7 members"
                } else {
                    destinationLabel
                }
            Text(title)
        }
        if (hydrationStage >= 2) Text("Avatar loaded and timeline reconciled")
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .height(if (hydrationStage >= 2) 144.dp else 64.dp)
                .testTag(BOTTOM_CHROME_TAG),
        )
    }

    private fun captureForwardFrames(
        state: RouteHarnessState,
        destinationLabel: String,
        outgoingBounds: MutableList<DpRect>,
        frameMillis: Long,
    ): RouteFrameSamples {
        composeRule.runOnUiThread { state.route = destinationLabel }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(1L)
        composeRule.runOnIdle { }
        val samples = RouteFrameSamples()
        val animationFrames = animationFrameCount(frameMillis)
        repeat(animationFrames + POST_SETTLE_FRAMES) { frame ->
            if (frame == animationFrames - 2) composeRule.runOnUiThread { state.hydrationStage = 1 }
            if (frame == animationFrames - 1) composeRule.runOnUiThread { state.hydrationStage = 2 }
            composeRule.mainClock.advanceTimeBy(frameMillis)
            composeRule.runOnIdle { }
            captureRouteFrame(state, samples, outgoingBounds)
        }
        return samples
    }

    private fun captureRouteFrame(
        state: RouteHarnessState,
        samples: RouteFrameSamples,
        outgoingBounds: MutableList<DpRect>,
    ) {
        composeRule.onNodeWithTag(DESTINATION_TAG).assertExists()
        if (composeRule.onAllNodesWithTag(SOURCE_TAG).fetchSemanticsNodes().isNotEmpty()) {
            outgoingBounds.add(composeRule.onNodeWithTag(SOURCE_TAG).getUnclippedBoundsInRoot())
        }
        samples.bounds.add(composeRule.onNodeWithTag(DESTINATION_TAG).getUnclippedBoundsInRoot())
        samples.running.add(state.transitionRunning)
    }

    private fun captureBackFrames(
        state: RouteHarnessState,
        frameMillis: Long,
    ): RouteFrameSamples {
        composeRule.runOnUiThread { state.route = null }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(1L)
        composeRule.runOnIdle { }
        val samples = RouteFrameSamples()
        repeat(animationFrameCount(frameMillis) + POST_SETTLE_FRAMES) {
            composeRule.mainClock.advanceTimeBy(frameMillis)
            composeRule.runOnIdle { }
            captureBackFrame(state, samples)
        }
        return samples
    }

    private fun captureBackFrame(
        state: RouteHarnessState,
        samples: RouteFrameSamples,
    ) {
        composeRule.onNodeWithTag(SOURCE_TAG).assertExists()
        samples.bounds.add(composeRule.onNodeWithTag(SOURCE_TAG).getUnclippedBoundsInRoot())
        samples.running.add(state.transitionRunning)
    }

    private fun animationFrameCount(frameMillis: Long): Int =
        (
            (CONVERSATION_ROUTE_TRANSITION_MILLIS + frameMillis - 1L) /
                frameMillis
        ).toInt()

    private fun assertConverges(
        samples: RouteFrameSamples,
        label: String,
    ) {
        assertStableOuterBounds(samples.bounds)
        samples.bounds
            .map { kotlin.math.abs(it.left.value) }
            .zipWithNext()
            .forEachIndexed { frame, (before, after) ->
                assertTrue("$label reversed between frames $frame and ${frame + 1}", after <= before)
            }
        val settledFrame =
            samples.running.indices.first { frame ->
                !samples.running[frame] && samples.running.take(frame).any { it }
            }
        val terminal = samples.bounds[settledFrame]
        assertEquals(0f, terminal.left.value, 0.5f)
        assertEquals(terminal, samples.bounds[settledFrame + 1])
    }

    private fun assertAlphaConverges(
        samples: RouteFrameSamples,
        conversationRoute: Boolean,
        label: String,
    ) {
        val travel = CONVERSATION_ROUTE_LAYER_TRAVEL.value
        val alphas =
            samples.bounds.map { bounds ->
                val hiddenFraction = kotlin.math.abs(bounds.left.value) / travel
                val conversationVisibility =
                    if (conversationRoute) 1f - hiddenFraction else hiddenFraction
                conversationRouteLayerAlpha(
                    conversationVisibility = conversationVisibility,
                    conversationRoute = conversationRoute,
                    suppressMotion = false,
                )
            }
        alphas
            .zipWithNext()
            .forEachIndexed { frame, (before, after) ->
                assertTrue(
                    "$label alpha reversed between frames $frame and ${frame + 1}: $before -> $after",
                    after + ALPHA_TOLERANCE >= before,
                )
            }
        val settledFrame =
            samples.running.indices.first { frame ->
                !samples.running[frame] && samples.running.take(frame).any { it }
            }
        assertTrue(
            "$label did not reach an opaque terminal frame: ${alphas[settledFrame]}",
            alphas[settledFrame] >= OPAQUE_CHANNEL_MINIMUM,
        )
        assertEquals(alphas[settledFrame], alphas[settledFrame + 1])
    }

    private fun assertStableOuterBounds(samples: List<DpRect>) {
        val first = samples.first()
        samples.forEachIndexed { frame, sample ->
            assertEquals("width changed at frame $frame", first.right - first.left, sample.right - sample.left)
            assertEquals("height changed at frame $frame", first.bottom - first.top, sample.bottom - sample.top)
        }
    }

    private companion object {
        const val SOURCE_TAG = "conversation-route-source"
        const val DESTINATION_TAG = "conversation-route-destination"
        const val TOP_CHROME_TAG = "conversation-route-top-chrome"
        const val BOTTOM_CHROME_TAG = "conversation-route-bottom-chrome"
        const val POST_SETTLE_FRAMES = 3
        const val ALPHA_TOLERANCE = 0.005f
        const val OPAQUE_CHANNEL_MINIMUM = 0.99f
        const val MAX_120_HZ_REVERSAL_STEP_DP = 8f
    }

    private class RouteHarnessState {
        var route by mutableStateOf<String?>(null)
        var hydrationStage by mutableIntStateOf(0)
        var transitionRunning = false
    }

    private data class RouteFrameSamples(
        val bounds: MutableList<DpRect> = mutableListOf(),
        val running: MutableList<Boolean> = mutableListOf(),
    )
}
