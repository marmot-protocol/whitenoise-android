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
    fun cachedDmConvergesMonotonicallyWhenFinalFramesHydrateInLtr() {
        verifyForwardRoute(LayoutDirection.Ltr, "Cached DM")
    }

    @Test
    fun cachedGroupConvergesMonotonicallyWhenFinalFramesHydrateInRtl() {
        verifyForwardRoute(LayoutDirection.Rtl, "Cached group")
    }

    private fun verifyForwardRoute(
        layoutDirection: LayoutDirection,
        destinationLabel: String,
    ) {
        val state = RouteHarnessState()
        composeRule.setContent {
            RouteHarness(state, layoutDirection, destinationLabel)
        }
        composeRule.mainClock.autoAdvance = false
        val outgoingBounds = mutableListOf<DpRect>()
        val forward = captureForwardFrames(state, destinationLabel, outgoingBounds)
        assertStableOuterBounds(outgoingBounds)
        assertConverges(forward, "forward destination")
        composeRule.onNodeWithTag(DESTINATION_TAG).assertIsDisplayed()

        val back = captureBackFrames(state)
        assertConverges(back, "back destination")
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
    ): RouteFrameSamples {
        composeRule.runOnUiThread { state.route = destinationLabel }
        composeRule.runOnIdle { }
        val samples = RouteFrameSamples()
        repeat(20) { frame ->
            if (frame == 13) composeRule.runOnUiThread { state.hydrationStage = 1 }
            if (frame == 14) composeRule.runOnUiThread { state.hydrationStage = 2 }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            composeRule.onNodeWithTag(DESTINATION_TAG).assertExists()
            if (composeRule.onAllNodesWithTag(SOURCE_TAG).fetchSemanticsNodes().isNotEmpty()) {
                outgoingBounds.add(composeRule.onNodeWithTag(SOURCE_TAG).getUnclippedBoundsInRoot())
            }
            samples.bounds.add(composeRule.onNodeWithTag(DESTINATION_TAG).getUnclippedBoundsInRoot())
            samples.running.add(state.transitionRunning)
        }
        return samples
    }

    private fun captureBackFrames(state: RouteHarnessState): RouteFrameSamples {
        composeRule.runOnUiThread { state.route = null }
        composeRule.runOnIdle { }
        val samples = RouteFrameSamples()
        repeat(20) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            composeRule.onNodeWithTag(SOURCE_TAG).assertExists()
            samples.bounds.add(composeRule.onNodeWithTag(SOURCE_TAG).getUnclippedBoundsInRoot())
            samples.running.add(state.transitionRunning)
        }
        return samples
    }

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
