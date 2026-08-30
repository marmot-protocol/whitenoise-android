package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

    /** Samples a cached DM at 60 Hz while late chrome hydration lands near settlement. */
    @Test
    fun cachedDmConvergesMonotonicallyWhenFinalFramesHydrateAt60HzInLtr() {
        verifyForwardRoute(LayoutDirection.Ltr, "Cached DM", frameMillis = 16L)
    }

    /** Samples an RTL cached group at 90 Hz while late chrome hydration lands. */
    @Test
    fun cachedGroupConvergesMonotonicallyWhenFinalFramesHydrateAt90HzInRtl() {
        verifyForwardRoute(LayoutDirection.Rtl, "Cached group", frameMillis = 11L)
    }

    /** Samples a cached DM at 120 Hz to expose single-frame terminal reversals. */
    @Test
    fun cachedDmConvergesMonotonicallyWhenFinalFramesHydrateAt120HzInLtr() {
        verifyForwardRoute(LayoutDirection.Ltr, "Cached DM", frameMillis = 8L)
    }

    /** A Back interruption continues from the exact current draw-layer position. */
    @Test
    fun rapidBackReversalDoesNotSnapReturningChatListLayerAt120Hz() {
        val progressBefore =
            FastOutSlowInEasing.transform(
                EIGHT_FORWARD_120_HZ_FRAMES_MILLIS.toFloat() / CONVERSATION_ROUTE_TRANSITION_MILLIS,
            )
        val firstReverseFraction =
            FastOutSlowInEasing.transform(
                FRAME_120_HZ_MILLIS.toFloat() / CONVERSATION_ROUTE_TRANSITION_MILLIS,
            )
        val progressAfter = progressBefore * (1f - firstReverseFraction)
        val sourceBefore =
            conversationRouteLayerTranslation(
                conversationVisibility = progressBefore,
                conversationRoute = false,
                suppressMotion = false,
            )
        val sourceAfter =
            conversationRouteLayerTranslation(
                conversationVisibility = progressAfter,
                conversationRoute = false,
                suppressMotion = false,
            )

        assertContinuousReversalStep("source", sourceBefore, sourceAfter)
        assertTrue(kotlin.math.abs(sourceAfter) <= CONVERSATION_ROUTE_LAYER_TRAVEL.value)
    }

    /** Reduced motion keeps both route layers fully opaque and untranslated. */
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

    /** Bounds the first reverse step to one physically plausible 120 Hz frame. */
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

    /** Exercises forward, settled, and reversed route samples for one device shape. */
    private fun verifyForwardRoute(
        layoutDirection: LayoutDirection,
        destinationLabel: String,
        frameMillis: Long,
    ) {
        val state = SeekableRouteHarnessState()
        composeRule.setContent {
            SeekableRouteHarness(state, layoutDirection, destinationLabel)
        }
        composeRule.mainClock.autoAdvance = false
        val outgoingBounds = mutableListOf<DpRect>()
        val forward = captureSeekableForwardFrames(state, destinationLabel, outgoingBounds, frameMillis)
        assertStableOuterBounds(outgoingBounds)
        assertConverges(forward, "forward destination")
        assertAlphaConverges(forward, conversationRoute = true, label = "forward destination")
        composeRule.onNodeWithTag(DESTINATION_TAG).assertIsDisplayed()

        snapSeekableRoute(state, destinationLabel)
        val back = captureSeekableBackFrames(state, frameMillis)
        assertConverges(back, "back destination")
        assertAlphaConverges(back, conversationRoute = false, label = "back destination")
        composeRule.onNodeWithTag(SOURCE_TAG).assertIsDisplayed()
    }

    /** Owns the seekable transition and exposes its coroutine scope to the frame driver. */
    @Composable
    @Suppress("FunctionNaming")
    private fun SeekableRouteHarness(
        state: SeekableRouteHarnessState,
        layoutDirection: LayoutDirection,
        destinationLabel: String,
    ) {
        val coroutineScope = rememberCoroutineScope()
        val transition = rememberTransition(state.transitionState, label = "seekable conversation route test")
        SideEffect { state.coroutineScope = coroutineScope }
        RouteHarnessContent(
            transition = transition,
            hydrationStage = state.hydrationStage,
            layoutDirection = layoutDirection,
            destinationLabel = destinationLabel,
        )
    }

    /** Renders stable full-screen source and destination slots for manual seeking. */
    @Composable
    @Suppress("FunctionNaming")
    private fun RouteHarnessContent(
        transition: Transition<String?>,
        hydrationStage: Int,
        layoutDirection: LayoutDirection,
        destinationLabel: String,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            WhiteNoiseTheme {
                Surface(Modifier.requiredSize(360.dp, 640.dp)) {
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
                                HydratingRouteChrome(hydrationStage, destinationLabel)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Injects deliberately late top, avatar, timeline, and bottom-chrome changes. */
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

    /** Captures every forward fraction and injects hydration on the final running frames. */
    private fun captureSeekableForwardFrames(
        state: SeekableRouteHarnessState,
        destinationLabel: String,
        outgoingBounds: MutableList<DpRect>,
        frameMillis: Long,
    ): RouteFrameSamples {
        val samples = RouteFrameSamples()
        val fractions = routeFrameFractions(frameMillis)
        val runningFrames = fractions.count { it < 1f }
        val firstHydrationFrame = runningFrames - 2
        val secondHydrationFrame = runningFrames - 1
        fractions.forEachIndexed { frame, fraction ->
            if (frame == firstHydrationFrame || frame == secondHydrationFrame) {
                assertTrue("hydration stage $frame is not a running frame", fraction < 1f)
                composeRule.runOnUiThread {
                    state.hydrationStage = if (frame == firstHydrationFrame) 1 else 2
                }
            }
            seekRoute(state, fraction, destinationLabel)
            captureRouteFrame(samples, outgoingBounds, running = fraction < 1f)
        }
        return samples
    }

    /** Records destination bounds and outgoing-slot retention for one forward frame. */
    private fun captureRouteFrame(
        samples: RouteFrameSamples,
        outgoingBounds: MutableList<DpRect>,
        running: Boolean,
    ) {
        composeRule.onNodeWithTag(DESTINATION_TAG).assertExists()
        if (composeRule.onAllNodesWithTag(SOURCE_TAG).fetchSemanticsNodes().isNotEmpty()) {
            outgoingBounds.add(composeRule.onNodeWithTag(SOURCE_TAG).getUnclippedBoundsInRoot())
        }
        samples.bounds.add(composeRule.onNodeWithTag(DESTINATION_TAG).getUnclippedBoundsInRoot())
        samples.running.add(running)
    }

    /** Captures each reverse fraction through the first post-settle frames. */
    private fun captureSeekableBackFrames(
        state: SeekableRouteHarnessState,
        frameMillis: Long,
    ): RouteFrameSamples {
        val samples = RouteFrameSamples()
        routeFrameFractions(frameMillis).forEach { fraction ->
            seekRoute(state, fraction, null)
            captureBackFrame(samples, running = fraction < 1f)
        }
        return samples
    }

    /** Records the returning chat-list bounds for one reverse frame. */
    private fun captureBackFrame(
        samples: RouteFrameSamples,
        running: Boolean,
    ) {
        composeRule.onNodeWithTag(SOURCE_TAG).assertExists()
        samples.bounds.add(composeRule.onNodeWithTag(SOURCE_TAG).getUnclippedBoundsInRoot())
        samples.running.add(running)
    }

    /** Produces refresh-rate-shaped fractions plus terminal stability samples. */
    private fun routeFrameFractions(frameMillis: Long): List<Float> =
        buildList {
            var playTimeMillis = 0L
            while (playTimeMillis < CONVERSATION_ROUTE_TRANSITION_MILLIS) {
                add(playTimeMillis.toFloat() / CONVERSATION_ROUTE_TRANSITION_MILLIS)
                playTimeMillis += frameMillis
            }
            repeat(POST_SETTLE_FRAMES) { add(1f) }
        }

    /** Seeks the transition and waits until Compose commits the requested fraction. */
    private fun seekRoute(
        state: SeekableRouteHarnessState,
        fraction: Float,
        target: String?,
    ) {
        val job = launchSeekableOperation(state) { state.transitionState.seekTo(fraction, target) }
        awaitSeekableOperation(job)
    }

    /** Snaps the harness to a known endpoint before testing an interruption. */
    private fun snapSeekableRoute(
        state: SeekableRouteHarnessState,
        target: String?,
    ) {
        val job = launchSeekableOperation(state) { state.transitionState.snapTo(target) }
        awaitSeekableOperation(job)
    }

    /** Launches transition-state mutation on the Compose UI thread. */
    private fun launchSeekableOperation(
        state: SeekableRouteHarnessState,
        operation: suspend () -> Unit,
    ): Job {
        lateinit var job: Job
        composeRule.runOnUiThread {
            job = state.coroutineScope.launch { operation() }
        }
        return job
    }

    /** Advances bounded presentation frames until the seek operation commits. */
    private fun awaitSeekableOperation(job: Job) {
        repeat(MAX_SEEK_COMMIT_FRAMES) {
            if (job.isCompleted) return
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
        }
        assertTrue("seekable transition did not commit", job.isCompleted)
        assertTrue("seekable transition was cancelled", !job.isCancelled)
    }

    /** Requires monotonic position and identical terminal/post-settle geometry. */
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

    /** Requires monotonic opacity and an opaque, stable terminal frame. */
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

    /** Requires every sampled full-screen layer to retain its original size. */
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
        const val MAX_SEEK_COMMIT_FRAMES = 4
        const val FRAME_120_HZ_MILLIS = 8L
        const val EIGHT_FORWARD_120_HZ_FRAMES_MILLIS = FRAME_120_HZ_MILLIS * 8L
        const val ALPHA_TOLERANCE = 0.005f
        const val OPAQUE_CHANNEL_MINIMUM = 0.99f
        const val MAX_120_HZ_REVERSAL_STEP_DP = 8f
    }

    private class SeekableRouteHarnessState {
        val transitionState = SeekableTransitionState<String?>(null)
        var hydrationStage by mutableIntStateOf(0)
        lateinit var coroutineScope: CoroutineScope
    }

    private data class RouteFrameSamples(
        val bounds: MutableList<DpRect> = mutableListOf(),
        val running: MutableList<Boolean> = mutableListOf(),
    )
}
