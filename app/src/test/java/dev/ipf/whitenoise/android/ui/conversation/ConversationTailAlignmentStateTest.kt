package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lifecycle coverage for the real grouped conversation-tail state holders. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationTailAlignmentStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sameKeysPreserveMutationsWhenSeedInputsChange() {
        val fixture = HolderFixture()
        show(fixture)
        val first = fixture.requireCapture()

        composeRule.runOnUiThread {
            first.bottom.measuredBottomChromeHeightPx.value = 240
            first.bottom.bottomInputRevision.longValue = 7L
            first.bottom.routePresentationFrozen.value = false
            first.seeded.initialTimelineAnchored.value = true
            first.seeded.committed.value = true
            first.seeded.recoveryVisible.value = true
            first.seeded.retryGeneration.longValue = 3L
            first.legacyBottom.measuredBottomChromeHeightPx.value = 240
            first.legacyBottom.bottomInputRevision.longValue = 7L
            first.legacyBottom.routePresentationFrozen.value = false
            first.legacySeeded.initialTimelineAnchored.value = true
            first.legacySeeded.committed.value = true
            first.legacySeeded.recoveryVisible.value = true
            first.legacySeeded.retryGeneration.longValue = 3L
            fixture.routeSeed.value = true
            fixture.anchorSeed.value = false
            fixture.committedSeed.value = false
        }
        composeRule.waitForIdle()

        val current = fixture.requireCapture()
        assertSame(first.bottom, current.bottom)
        assertSame(first.seeded, current.seeded)
        assertLegacyStateIdentityMatches(first, current, expectedBottomReset = false, expectedSeededReset = false)
        assertTrue(current.transcriptVisibilityCommitted)
        assertTrue(current.seeded.recoveryVisible.value)
        assertEquals(3L, current.seeded.retryGeneration.longValue)
        assertEquals(240, current.bottom.measuredBottomChromeHeightPx.value)
        assertEquals(7L, current.bottom.bottomInputRevision.longValue)
        assertFalse(current.bottom.routePresentationFrozen.value)
    }

    @Test
    fun requestAndControllerKeysResetOnlyTheirOwnedHolders() {
        val fixture = HolderFixture()
        show(fixture)
        val first = fixture.requireCapture()

        composeRule.runOnUiThread {
            fixture.anchorSeed.value = false
            fixture.committedSeed.value = true
            fixture.requestId.longValue = 2L
        }
        composeRule.waitForIdle()
        val requestReset = fixture.requireCapture()
        assertSame(first.bottom, requestReset.bottom)
        assertNotSame(first.seeded, requestReset.seeded)
        assertLegacyStateIdentityMatches(first, requestReset, expectedBottomReset = false, expectedSeededReset = true)
        assertFalse(requestReset.seeded.initialTimelineAnchored.value)
        assertTrue(requestReset.seeded.committed.value)

        composeRule.runOnUiThread {
            fixture.routeSeed.value = true
            fixture.anchorSeed.value = true
            fixture.committedSeed.value = false
            fixture.controllerKey.value = "controller-b"
        }
        composeRule.waitForIdle()
        val controllerReset = fixture.requireCapture()
        assertNotSame(requestReset.bottom, controllerReset.bottom)
        assertNotSame(requestReset.seeded, controllerReset.seeded)
        assertLegacyStateIdentityMatches(
            requestReset,
            controllerReset,
            expectedBottomReset = true,
            expectedSeededReset = true,
        )
        assertTrue(controllerReset.bottom.routePresentationFrozen.value)
        assertTrue(controllerReset.seeded.initialTimelineAnchored.value)
        assertFalse(controllerReset.seeded.committed.value)
    }

    @Test
    fun requestOnlyResetRetainsPreexistingUnreadCaptureSemantics() {
        val fixture = HolderFixture()
        show(fixture)
        assertEquals(4, fixture.requireCapture().unreadIncomingCount)

        composeRule.runOnUiThread {
            fixture.anchorSeed.value = false
            fixture.requestId.longValue = 2L
        }
        composeRule.waitForIdle()

        val requestReset = fixture.requireCapture()
        assertFalse(requestReset.seeded.initialTimelineAnchored.value)
        assertEquals(
            "the request-only reset still leaves the preexisting unread derivation keyed only by controller and chat",
            4,
            requestReset.unreadIncomingCount,
        )
    }

    @Test
    fun disposalAndReentryCreateFreshHolders() {
        val fixture = HolderFixture()
        show(fixture)
        val first = fixture.requireCapture()

        composeRule.runOnUiThread { fixture.mounted.value = false }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { fixture.mounted.value = true }
        composeRule.waitForIdle()

        val reentered = fixture.requireCapture()
        assertNotSame(first.bottom, reentered.bottom)
        assertNotSame(first.seeded, reentered.seeded)
        assertLegacyStateIdentityMatches(first, reentered, expectedBottomReset = true, expectedSeededReset = true)
    }

    @Test
    fun controllerReplacementDuringControlledSettleSuspensionMatchesIndependentRememberCapture() {
        val fixture =
            HolderFixture(
                routeSeed = true,
                routeTransitionInProgress = true,
                controlledRouteSettle = true,
            )
        show(fixture)
        val first = fixture.requireCapture()
        assertTrue(first.bottom.routePresentationFrozen.value)
        assertTrue(first.legacyBottom.routePresentationFrozen.value)

        composeRule.runOnUiThread {
            fixture.routeSeed.value = false
            fixture.controlledSettleGeneration.longValue = 1L
        }
        composeRule.waitForIdle()
        assertEquals(1L, fixture.routeSettleAwaitCount.longValue)
        composeRule.runOnUiThread { fixture.controllerKey.value = "controller-b" }
        composeRule.waitForIdle()
        assertEquals("controller-b", fixture.capture?.controllerKey)

        val replacementWhileSuspended = fixture.requireCapture()
        assertNotSame(first.bottom, replacementWhileSuspended.bottom)
        assertNotSame(
            first.legacyBottom.routePresentationFrozen,
            replacementWhileSuspended.legacyBottom.routePresentationFrozen,
        )
        assertTrue(
            "the old grouped state must remain frozen while its controlled settle is suspended",
            first.bottom.routePresentationFrozen.value,
        )
        assertTrue(
            "the independent pre-change state must have the same suspended capture",
            first.legacyBottom.routePresentationFrozen.value,
        )
        assertFalse(replacementWhileSuspended.bottom.routePresentationFrozen.value)
        assertFalse(replacementWhileSuspended.legacyBottom.routePresentationFrozen.value)

        fixture.routeSettleRelease.complete(Unit)
        composeRule.waitForIdle()
        assertFalse(first.legacyBottom.routePresentationFrozen.value)
        assertFalse(first.bottom.routePresentationFrozen.value)
        assertFalse(replacementWhileSuspended.bottom.routePresentationFrozen.value)
        assertFalse(replacementWhileSuspended.legacyBottom.routePresentationFrozen.value)
    }

    private fun assertLegacyStateIdentityMatches(
        before: HolderCapture,
        after: HolderCapture,
        expectedBottomReset: Boolean,
        expectedSeededReset: Boolean,
    ) {
        if (expectedBottomReset) {
            assertNotSame(before.legacyBottom.routePresentationFrozen, after.legacyBottom.routePresentationFrozen)
        } else {
            assertSame(before.legacyBottom.routePresentationFrozen, after.legacyBottom.routePresentationFrozen)
        }
        if (expectedSeededReset) {
            assertNotSame(before.legacySeeded.initialTimelineAnchored, after.legacySeeded.initialTimelineAnchored)
        } else {
            assertSame(before.legacySeeded.initialTimelineAnchored, after.legacySeeded.initialTimelineAnchored)
        }
    }

    private fun show(fixture: HolderFixture) {
        composeRule.setContent { HolderHarness(fixture) }
        composeRule.waitForIdle()
    }

    // One composition deliberately mirrors all pre-holder remember keys for identity comparisons.
    @Suppress("LongMethod")
    @Composable
    private fun HolderHarness(fixture: HolderFixture) {
        if (!fixture.mounted.value) return
        val controller = fixture.controllerKey.value
        val requestId = fixture.requestId.longValue
        val routeTransitionInProgress = fixture.routeTransitionInProgress.value
        val controlledSettleGeneration = fixture.controlledSettleGeneration.longValue
        val bottom =
            remember(controller) {
                ConversationBottomInsetState(
                    initialRoutePresentationFrozen = fixture.routeSeed.value,
                )
            }
        val seeded =
            remember(controller, requestId) {
                ConversationSeededTailState(
                    initialTimelineAnchored = fixture.anchorSeed.value,
                    initialCommitted = fixture.committedSeed.value,
                )
            }
        val legacyBottom =
            LegacyBottom(
                measuredBottomChromeHeightPx = remember(controller) { mutableStateOf<Int?>(null) },
                bottomInputRevision = remember(controller) { mutableLongStateOf(0L) },
                routePresentationFrozen = remember(controller) { mutableStateOf(fixture.routeSeed.value) },
            )
        val legacySeeded =
            LegacySeeded(
                initialTimelineAnchored = remember(controller, requestId) { mutableStateOf(fixture.anchorSeed.value) },
                committed = remember(controller, requestId) { mutableStateOf(fixture.committedSeed.value) },
                recoveryVisible = remember(controller, requestId) { mutableStateOf(false) },
                retryGeneration = remember(controller, requestId) { mutableLongStateOf(0L) },
            )
        var routePresentationFrozen by bottom.routePresentationFrozen
        var legacyRoutePresentationFrozen by legacyBottom.routePresentationFrozen
        val initialTimelineAnchored by seeded.initialTimelineAnchored
        val committed by seeded.committed
        val transcriptVisibilityCommitted by
            remember(controller, requestId) {
                derivedStateOf { initialTimelineAnchored && committed }
            }
        val unreadIncomingCount by
            remember(controller, fixture.chatKey.value) {
                derivedStateOf {
                    if (!initialTimelineAnchored) 0 else fixture.unreadSource.value
                }
            }

        if (fixture.controlledRouteSettle) {
            LaunchedEffect(controlledSettleGeneration) {
                if (controlledSettleGeneration == 0L) return@LaunchedEffect
                fixture.routeSettleAwaitCount.longValue += 1L
                fixture.routeSettleRelease.await()
                routePresentationFrozen = false
                legacyRoutePresentationFrozen = false
            }
        } else {
            LaunchedEffect(routeTransitionInProgress) {
                if (routeTransitionInProgress) {
                    routePresentationFrozen = true
                    legacyRoutePresentationFrozen = true
                    return@LaunchedEffect
                }
                if (!routePresentationFrozen) return@LaunchedEffect
                fixture.routeSettleAwaitCount.longValue += 1L
                withFrameNanos { }
                routePresentationFrozen = false
                legacyRoutePresentationFrozen = false
            }
        }
        SideEffect {
            fixture.capture =
                HolderCapture(
                    controllerKey = controller,
                    bottom = bottom,
                    seeded = seeded,
                    legacyBottom = legacyBottom,
                    legacySeeded = legacySeeded,
                    transcriptVisibilityCommitted = transcriptVisibilityCommitted,
                    unreadIncomingCount = unreadIncomingCount,
                )
        }
    }

    private class HolderFixture(
        routeSeed: Boolean = false,
        routeTransitionInProgress: Boolean = false,
        val controlledRouteSettle: Boolean = false,
    ) {
        val mounted = mutableStateOf(true)
        val controllerKey = mutableStateOf("controller-a")
        val chatKey = mutableStateOf("chat-a")
        val requestId = mutableLongStateOf(1L)
        val routeSeed = mutableStateOf(routeSeed)
        val anchorSeed = mutableStateOf(true)
        val committedSeed = mutableStateOf(false)
        val unreadSource = mutableStateOf(4)
        val routeTransitionInProgress = mutableStateOf(routeTransitionInProgress)
        val routeSettleAwaitCount = mutableLongStateOf(0L)
        val controlledSettleGeneration = mutableLongStateOf(0L)
        val routeSettleRelease = CompletableDeferred<Unit>()
        var capture: HolderCapture? = null

        fun requireCapture(): HolderCapture = checkNotNull(capture)
    }

    private data class LegacyBottom(
        val measuredBottomChromeHeightPx: MutableState<Int?>,
        val bottomInputRevision: MutableLongState,
        val routePresentationFrozen: MutableState<Boolean>,
    )

    private data class LegacySeeded(
        val initialTimelineAnchored: MutableState<Boolean>,
        val committed: MutableState<Boolean>,
        val recoveryVisible: MutableState<Boolean>,
        val retryGeneration: MutableLongState,
    )

    private data class HolderCapture(
        val controllerKey: String,
        val bottom: ConversationBottomInsetState,
        val seeded: ConversationSeededTailState,
        val legacyBottom: LegacyBottom,
        val legacySeeded: LegacySeeded,
        val transcriptVisibilityCommitted: Boolean,
        val unreadIncomingCount: Int,
    )
}
