package dev.ipf.whitenoise.android.ui.conversation

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ResponsivenessDeviceAcceptance
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DestructiveAccountWipeRuntimeState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks content at the production transcript draw, before any post-resume UI polling can hide stale frames. */
@PullRequestDeviceSmoke
@ResponsivenessDeviceAcceptance
@RunWith(AndroidJUnit4::class)
class ConversationRetainedTranscriptFirstFrameAndroidTest {
    @get:Rule(order = 0)
    val stallWatchdog = ResponsivenessStallWatchdog()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule(order = 2)
    val bodyFailureReporter = stallWatchdog.bodyFailureReporter()

    private lateinit var retainedActivity: ComponentActivity

    /** Already consumed background content must be in the first live draw with roster enrichment still held. */
    @Test
    fun consumedStoppedWindowIsInFirstLiveTranscriptDraw() {
        assertFirstDraw(UpdateBoundary.Consumed)
    }

    /** Preparation already received on IO cannot lose the first draw to a coherent geometry-only gate. */
    @Test
    fun queuedLocalPreparationCommitsBeforeFirstLiveTranscriptDraw() {
        assertFirstDraw(UpdateBoundary.Queued)
    }

    /** Controlled owner epochs supersede pending work without Android task-transition scheduling delays. */
    @Test
    fun controlledOwnerSupersessionWaitsForCurrentLocalWindow() {
        val fixture = mountFixture(controlledOwner = true)
        val owner = checkNotNull(fixture.owner)
        var bodyFailure: Throwable? = null
        try {
            composeRule.runOnUiThread {
                assertEquals(Lifecycle.State.RESUMED, retainedActivity.lifecycle.currentState)
                owner.moveTo(Lifecycle.State.CREATED)
            }
            fixture.dispatcher.hold.set(true)
            fixture.first.emitWindow(fixture.pageWithB)
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.dispatcher.hasPending }
            assertFalse(fixture.hasB)
            fixture.recording.set(true)
            val resumedAt = SystemClock.uptimeMillis()
            composeRule.runOnUiThread { owner.moveTo(Lifecycle.State.RESUMED) }
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.draws.isNotEmpty() }
            assertTrue(ConversationTimelineTestIds.MESSAGE_A in fixture.draws.first().messageIds)
            assertFalse("retained A is live while the newer handoff is held", fixture.hasB)
            composeRule.runOnUiThread {
                assertTrue(fixture.controller.pendingTimelineAtForeground()?.isCompleted == false)
                owner.moveTo(Lifecycle.State.CREATED)
                fixture.draws.clear() // The second foreground epoch owns its own first-live-frame evidence.
                owner.moveTo(Lifecycle.State.RESUMED)
            }
            fixture.dispatcher.release()
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) {
                fixture.draws.any { ConversationTimelineTestIds.MESSAGE_B in it.messageIds }
            }
            assertSame(retainedActivity, composeRule.activity)
            assertEquals(Lifecycle.State.RESUMED, retainedActivity.lifecycle.currentState)
            assertResumedTranscript(fixture, null, resumedAt, false, allowRetainedPendingFrame = true)
            composeRule.onNode(hasSetTextAction()).performTextInput(" editable")
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            closeFirstDrawFixture(fixture, retainedActivity, bodyFailure)
        }
    }

    /** Actual Back routing must draw its result before the deliberately held local handoff completes. */
    @Test
    fun retainedConversationBackDrawsWhileLocalPreparationIsStillHeld() {
        val fixture = mountFixture(controlledOwner = true)
        val owner = checkNotNull(fixture.owner)
        try {
            composeRule.runOnUiThread { owner.moveTo(Lifecycle.State.CREATED) }
            fixture.dispatcher.hold.set(true)
            fixture.first.emitWindow(fixture.pageWithB)
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.dispatcher.hasPending }
            fixture.recording.set(true)
            composeRule.runOnUiThread { owner.moveTo(Lifecycle.State.RESUMED) }
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.draws.isNotEmpty() }
            assertFalse(fixture.hasB)
            composeRule.runOnUiThread { retainedActivity.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.navigationDrawObserved.get() }
            assertTrue("navigation is visibly live before the held handoff", fixture.dispatcher.hold.get())
            assertTrue(fixture.dispatcher.hasPending)
            assertFalse(fixture.hasB)
        } finally {
            fixture.close()
        }
    }

    /** Reconnect's replacement snapshot is sufficient even when no later live update arrives. */
    @Test
    fun replacementSnapshotNeedsNoFollowingEventToReachFirstLiveDraw() {
        assertFirstDraw(UpdateBoundary.Replacement)
    }

    /** A focused editor stays usable after the current local window reaches the resumed transcript. */
    @Test
    fun queuedLocalPreparationWithFocusedComposerReachesFirstLiveDraw() {
        assertFirstDraw(UpdateBoundary.Queued, focusComposer = true)
    }

    /** A real window that refuses IME visibility still draws current content and accepts editor input. */
    @Test
    fun deniedImeVisibilityDoesNotHideCurrentLocalContentForever() {
        assertFirstDraw(UpdateBoundary.Consumed, focusComposer = true, denyIme = true)
    }

    /** Background content can update the bounded window without pulling an older reader to the tail. */
    @Test
    fun olderReadingAnchorSurvivesFreshFirstLiveTranscriptDraw() {
        assertFirstDraw(UpdateBoundary.Consumed, olderReader = true)
    }

    /** A disposed route cannot draw the old controller when delayed preparation finally completes. */
    @Test
    fun disposedRouteRejectsHeldPreparationAndOldDrawEvidence() {
        val fixture = mountFixture()
        try {
            fixture.dispatcher.hold.set(true)
            fixture.first.emitWindow(fixture.pageWithB)
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.dispatcher.hasPending }
            composeRule.runOnUiThread {
                fixture.mounted = false
                fixture.controller.onCleared()
                fixture.recording.set(true)
            }
            fixture.dispatcher.release()
            composeRule.waitForIdle()
            assertTrue(fixture.draws.isEmpty())
        } finally {
            stallWatchdog.phase("fixture cleanup")
            fixture.close()
        }
    }

    /** Actual production lifecycle wiring keeps a retired runtime behind the bounded draw fence. */
    @Test
    fun retiredRuntimeAtResumeDoesNotSynchronouslyExposeOldTranscript() {
        val fixture = mountFixture(controlledOwner = true)
        val owner = checkNotNull(fixture.owner)
        try {
            composeRule.runOnUiThread {
                owner.moveTo(Lifecycle.State.CREATED)
                publishFixtureOwner(fixture.appState, ConversationTimelineTestIds.ACCOUNT_REF, true)
                assertFalse(fixture.controller.ownsRetainedForegroundPresentation)
                fixture.recording.set(true)
            }
            val preDrawsBeforeResume = fixture.preDraws.get()
            val resumedAt = SystemClock.uptimeMillis()
            composeRule.runOnUiThread { owner.moveTo(Lifecycle.State.RESUMED) }
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) {
                fixture.preDraws.get() > preDrawsBeforeResume && fixture.firstBlockedAt != null
            }
            assertTrue("a retired runtime cannot draw synchronously on resume", fixture.draws.isEmpty())
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.draws.isNotEmpty() }
            assertTrue(
                "only the bounded fallback can release this retired owner",
                fixture.firstDrawAt - resumedAt >= 1_500L,
            )
        } finally {
            fixture.close()
        }
    }

    /** A new chat cannot inherit a delayed page or cached painted layer from the previous chat. */
    @Test
    fun chatReplacementRejectsHeldOldRoutePreparation() {
        assertOwnerReplacement(RouteReplacement.Chat)
    }

    /** Switching the active account retires held work even when both accounts use the same group key. */
    @Test
    fun accountReplacementRejectsHeldOldRoutePreparation() {
        assertOwnerReplacement(RouteReplacement.Account)
    }

    /** A rebuilt runtime for the same account and chat must reject its predecessor's held page. */
    @Test
    fun runtimeReplacementRejectsHeldOldRoutePreparation() {
        assertOwnerReplacement(RouteReplacement.Runtime)
    }

    /** Keeps one root probe through replacement so the test cannot clear stale painted evidence itself. */
    private fun assertOwnerReplacement(kind: RouteReplacement) {
        val fixture = mountFixture(includeSecondAccount = true)
        try {
            fixture.dispatcher.hold.set(true)
            fixture.first.emitWindow(fixture.pageWithB)
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.dispatcher.hasPending }
            lateinit var next: RetainedFixture
            composeRule.runOnUiThread {
                val route = replacementRoute(kind)
                publishFixtureOwner(fixture.appState, route.accountRef, kind == RouteReplacement.Runtime)
                if (kind != RouteReplacement.Chat) {
                    assertFalse(
                        "old account/runtime cannot admit an early retained frame",
                        fixture.controller.ownsRetainedForegroundPresentation,
                    )
                }
                next = RetainedFixture(false, false, route, fixture.appState)
                fixture.ownedReplacement = next
            }
            awaitFixtureSetup(fixture) { next.controller.hasPublishedAuthoritativeTimeline }
            composeRule.runOnUiThread {
                fixture.collectEveryDraw.set(true)
                fixture.activeRoute = next
            }
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { next.initialDrawObserved.get() }
            fixture.dispatcher.release()
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) {
                fixture.dispatcher.releasedWorkCompleted.get() > 0
            }
            var drawsAfterRelease = 0
            composeRule.runOnUiThread {
                drawsAfterRelease = fixture.everyDraw.count { it.expectedOwner === next.controller }
                checkNotNull(next.composition).invalidate()
            }
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) {
                fixture.everyDraw.count { it.expectedOwner === next.controller } > drawsAfterRelease
            }
            val newOwnerFrames = fixture.everyDraw.filter { it.expectedOwner === next.controller }
            assertTrue("replacement must produce a real root draw", newOwnerFrames.isNotEmpty())
            newOwnerFrames.forEach { evidence ->
                val frame = evidence.frame
                assertSame(next.controller, frame.controller)
                assertTrue(next.route.messageA in frame.messageIds)
                assertTrue("msg:${next.route.messageA}" in frame.visibleItemKeys)
                assertFalse(ConversationTimelineTestIds.MESSAGE_B in frame.messageIds)
            }
            assertFalse(next.rosterReply.isCompleted)
        } finally {
            stallWatchdog.phase("fixture cleanup")
            fixture.close()
        }
    }

    /** Varies exactly one route owner while using distinct new transcript keys as the draw oracle. */
    private fun replacementRoute(kind: RouteReplacement): FixtureRoute =
        FixtureRoute(
            accountRef =
                if (kind == RouteReplacement.Account) "fixture-other" else ConversationTimelineTestIds.ACCOUNT_REF,
            accountId =
                if (kind == RouteReplacement.Account) "ab".repeat(32) else ConversationTimelineTestIds.ACCOUNT_ID,
            groupId = if (kind == RouteReplacement.Chat) "bc".repeat(32) else ConversationTimelineTestIds.GROUP_ID,
            messageA = "d3".repeat(32),
            messageB = "d4".repeat(32),
        )

    /** Replays owner-field publication only; this existing seam does not invoke an engine wipe. */
    private fun publishFixtureOwner(
        appState: WhiteNoiseAppState,
        accountRef: String,
        replaceRuntime: Boolean,
    ) {
        val state =
            DestructiveAccountWipeRuntimeState(
                activeAccountRef = accountRef,
                activeConversationAccountRef = null,
                activeConversationGroupIdHex = null,
                runtimeGeneration = appState.runtimeGeneration + if (replaceRuntime) 1 else 0,
            )
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("applyDestructiveWipeRuntimeState", DestructiveAccountWipeRuntimeState::class.java)
            .apply { isAccessible = true }
            .invoke(appState, state)
    }

    /** The oracle captures painted composition rows, not a newer controller value sampled at the root draw. */
    @Test
    fun drawProbeNegativeControlRejectsStalePaintedContent() {
        val fixture = mountFixture()
        try {
            val paintedA = fixture.controller.timeline.toList()
            fixture.first.emitWindow(fixture.pageWithB)
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.hasB }
            composeRule.runOnUiThread {
                fixture.staleRows = paintedA
                fixture.paintStaleControl = true
            }
            stopRetainedActivity()
            fixture.recording.set(true)
            stallWatchdog.phase("resume task")
            resumeRetainedActivity()
            stallWatchdog.phase("await first live draw")
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.draws.isNotEmpty() }
            assertTrue(fixture.hasB)
            val staleDraw = fixture.draws.first()
            assertTrue(staleDraw.visibleItemKeys.isNotEmpty())
            assertFalse(
                "new controller content cannot make stale painted A pass",
                ConversationTimelineTestIds.MESSAGE_B in fixture.draws.first().messageIds,
            )
        } finally {
            stallWatchdog.phase("fixture cleanup")
            fixture.close()
        }
    }

    /** Runs a real Activity STOP/RESUME without a subsequent event, navigation, remote catch-up or roster reply. */
    private fun assertFirstDraw(
        boundary: UpdateBoundary,
        focusComposer: Boolean = false,
        denyIme: Boolean = false,
        olderReader: Boolean = false,
    ) {
        val fixture = mountFixture(replacement = boundary == UpdateBoundary.Replacement, olderReader = olderReader)
        val original = retainedActivity
        var bodyFailure: Throwable? = null
        try {
            if (focusComposer) establishFocusedComposer(fixture)
            val olderAnchor = if (olderReader) paintedOlderAnchor(fixture) else null
            stopRetainedActivity()
            assertEquals(Lifecycle.State.CREATED, original.lifecycle.currentState)
            if (denyIme) {
                composeRule.runOnUiThread { original.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) }
            }
            if (boundary == UpdateBoundary.Queued) fixture.dispatcher.hold.set(true)
            if (boundary == UpdateBoundary.Replacement) {
                fixture.first.endWindows()
            } else {
                fixture.first.emitWindow(fixture.pageWithB)
            }
            if (boundary == UpdateBoundary.Queued) {
                composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.dispatcher.hasPending }
                assertFalse(fixture.hasB)
                assertTrue(fixture.controller.pendingTimelineAtForeground() != null)
            } else {
                composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.hasB }
            }
            fixture.recording.set(true)
            val resumedAt = SystemClock.uptimeMillis()
            resumeForFirstDraw(fixture, boundary)
            stallWatchdog.phase("await eventual current transcript draw")
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) {
                fixture.draws.any { ConversationTimelineTestIds.MESSAGE_B in it.messageIds }
            }
            stallWatchdog.phase("post-draw activity lookup")
            assertSame(original, composeRule.activity)
            assertResumedTranscript(fixture, olderAnchor, resumedAt, denyIme, boundary == UpdateBoundary.Queued)
            stallWatchdog.phase("post-draw editor input")
            composeRule.onNode(hasSetTextAction()).performTextInput(" editable")
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            closeFirstDrawFixture(fixture, original, bodyFailure)
        }
    }

    /** Keeps the original assertion primary even if disposing the failed fixture also throws. */
    private fun closeFirstDrawFixture(
        fixture: RetainedFixture,
        original: ComponentActivity,
        bodyFailure: Throwable?,
    ) {
        var primaryFailure = bodyFailure
        val cleanups =
            listOf<() -> Unit>(
                {
                    stallWatchdog.phase("cleanup window flags main hop")
                    composeRule.runOnUiThread {
                        original.window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    }
                },
                {
                    stallWatchdog.phase("fixture cleanup")
                    fixture.close()
                },
            )
        cleanups.forEach { cleanup ->
            try {
                cleanup()
            } catch (failure: Throwable) {
                val prior = primaryFailure
                if (prior == null) primaryFailure = failure else prior.addSuppressed(failure)
            }
        }
        if (bodyFailure == null) primaryFailure?.let { throw it }
    }

    /** Keeps local preparation held across the requested real foreground transitions. */
    private fun resumeForFirstDraw(
        fixture: RetainedFixture,
        boundary: UpdateBoundary,
    ) {
        val checkpoint = fixture.preDraws.get()
        stallWatchdog.phase("resume task")
        resumeRetainedActivity()
        if (boundary != UpdateBoundary.Queued) return
        stallWatchdog.phase("await live retained pre-draw")
        composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) {
            fixture.preDraws.get() > checkpoint && fixture.draws.isNotEmpty()
        }
        assertFalse("held local preparation must remain pending", fixture.hasB)
        assertTrue(ConversationTimelineTestIds.MESSAGE_A in fixture.draws.first().messageIds)
        assertFalse(ConversationTimelineTestIds.MESSAGE_B in fixture.draws.first().messageIds)
        stallWatchdog.phase("release queued preparation")
        fixture.dispatcher.release()
        composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) {
            fixture.draws.any { ConversationTimelineTestIds.MESSAGE_B in it.messageIds }
        }
    }

    /** Requires the exact saved older anchor to have been painted before backgrounding. */
    private fun paintedOlderAnchor(fixture: RetainedFixture): Map.Entry<Any, Int> {
        val key = checkNotNull(fixture.readingSnapshot?.anchorItemId)
        val paintedOffsets = fixture.lastDraw?.visibleItemOffsets
        return checkNotNull(paintedOffsets?.entries?.firstOrNull { it.key == key }) {
            "the intended older anchor must be painted before STOP"
        }
    }

    /** Backgrounds the real task without ActivityScenario's idle wait while a pre-draw is deliberately held. */
    private fun stopRetainedActivity() {
        val original = retainedActivity
        stallWatchdog.phase("background task main hop")
        composeRule.runOnUiThread { assertTrue(original.moveTaskToBack(true)) }
        stallWatchdog.phase("await stopped lifecycle")
        composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) {
            original.lifecycle.currentState == Lifecycle.State.CREATED
        }
    }

    /** Reorders the existing task Activity to the front and keeps the retained-instance assertion explicit. */
    private fun resumeRetainedActivity() {
        val original = retainedActivity
        stallWatchdog.phase("reorder task main hop")
        composeRule.runOnUiThread {
            original.startActivity(Intent(original, original.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        stallWatchdog.phase("await resumed lifecycle")
        composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) {
            original.lifecycle.currentState == Lifecycle.State.RESUMED
        }
    }

    /** Establishes actual focused IME geometry before capturing the retained foreground state. */
    private fun establishFocusedComposer(fixture: RetainedFixture) {
        composeRule.onNode(hasSetTextAction()).performClick().performTextInput("retained draft")
        composeRule.runOnUiThread { fixture.keyboard?.show() }
        composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) {
            ViewCompat
                .getRootWindowInsets(composeRule.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
    }

    /** Checks the first painted bounded window, its reading coordinate and actual commit-to-draw ordering. */
    private fun assertResumedTranscript(
        fixture: RetainedFixture,
        olderAnchor: Map.Entry<Any, Int>?,
        resumedAt: Long,
        denyIme: Boolean,
        allowRetainedPendingFrame: Boolean = false,
    ) {
        val drawn =
            if (allowRetainedPendingFrame) {
                fixture.draws.first { ConversationTimelineTestIds.MESSAGE_B in it.messageIds }
            } else {
                fixture.draws.first()
            }
        val newestKey = "msg:${ConversationTimelineTestIds.MESSAGE_B}"
        assertTrue(ConversationTimelineTestIds.MESSAGE_B in drawn.messageIds)
        assertSame(fixture.controller, drawn.controller)
        if (olderAnchor != null) {
            assertEquals(olderAnchor.value, drawn.visibleItemOffsets[olderAnchor.key])
            assertFalse("background B must not pull an older reader to the tail", newestKey in drawn.visibleItemKeys)
            assertFalse(ConversationTimelineTestIds.MESSAGE_B == fixture.controller.lastReadMessageId)
        } else {
            assertTrue("tail B was actually composed in the painted viewport", newestKey in drawn.visibleItemKeys)
        }
        if (denyIme) {
            val imeVisible =
                ViewCompat
                    .getRootWindowInsets(composeRule.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            assertFalse(imeVisible)
        }
        assertFalse("independent roster work stays held", fixture.rosterReply.isCompleted)
        assertEquals(fixture.expectedOpenCount, fixture.scripts.timelineSubscriptionOpenCount)
        assertTrue(fixture.firstDrawAt >= resumedAt)
        assertTrue(fixture.applicationTimes.last() <= drawn.rootDrawAtUptimeMs)
        assertTrue(fixture.lastResumeAt >= resumedAt)
        assertTrue(fixture.firstDrawAt >= fixture.lastResumeAt)
        val gateHeldMs =
            fixture.firstBlockedAt?.let { blockedAt ->
                val releasedAt = checkNotNull(fixture.gateReleasedAt)
                assertTrue(releasedAt >= blockedAt && releasedAt <= fixture.firstDrawAt)
                releasedAt - blockedAt
            }
        val ownerKind = if (fixture.owner == null) "activity" else "controlled"
        Log.i(
            "WNFirstFrameTest",
            "owner_kind=$ownerKind ime_denied=$denyIme " +
                "on_resume_to_draw_ms=${fixture.firstDrawAt - fixture.lastResumeAt} " +
                "gate_held_ms=${gateHeldMs ?: "not_observed"}",
        )
    }

    /** Installs the real conversation screen with a bounded local subscription and independently delayed roster. */
    private fun mountFixture(
        replacement: Boolean = false,
        olderReader: Boolean = false,
        controlledOwner: Boolean = false,
        includeSecondAccount: Boolean = false,
    ): RetainedFixture {
        stallWatchdog.phase("mount activity lookup")
        retainedActivity = composeRule.activity
        lateinit var fixture: RetainedFixture
        stallWatchdog.phase("mount fixture main hop")
        composeRule.runOnUiThread {
            fixture = RetainedFixture(replacement, olderReader, includeSecondAccount = includeSecondAccount)
            if (controlledOwner) fixture.owner = ControlledConversationOwner(retainedActivity)
        }
        // This suite starts from a retained, authoritative transcript, not a cold route whose
        // unread boundary still depends on the native read-state initialization fixture.
        stallWatchdog.phase("await authoritative setup page")
        awaitFixtureSetup(fixture) {
            fixture.controller.hasPublishedAuthoritativeTimeline && fixture.controller.timeline.isNotEmpty()
        }
        ConversationTranscriptDrawProbe.observer = { frame ->
            if (fixture.collectEveryDraw.get()) {
                fixture.everyDraw.add(ExpectedOwnerDraw(fixture.rootOwnerAtCompletion, frame))
            }
            fixture.observe(frame)
            fixture.activeRoute?.observe(frame)
        }
        ConversationTranscriptDrawProbe.foregroundGateObserver = fixture::observeGate
        ConversationTranscriptDrawProbe.compositionRowsOverride = { controller, rows ->
            if (controller === fixture.controller && fixture.paintStaleControl) fixture.staleRows else rows
        }
        stallWatchdog.phase("mount setContent")
        composeRule.setContent {
            val active = fixture.activeRoute ?: fixture
            ObserveRetainedFixtureDraws(active, fixture, retainedActivity)
            if (active.mounted) {
                CompositionLocalProvider(LocalContext provides (active.owner ?: LocalContext.current)) {
                    WhiteNoiseTheme {
                        ConversationScreen(
                            appState = active.appState,
                            chat = active.chat,
                            controller = active.controller,
                            onBack = {
                                active.mounted = false
                                active.navigatedAway = true
                            },
                            restoredScrollSnapshot = active.readingSnapshot,
                        )
                    }
                }
            } else if (active.navigatedAway) {
                BasicText(
                    "Fixture chat list",
                    Modifier.drawWithContent {
                        drawContent()
                        active.navigationDrawObserved.set(true)
                    },
                )
            }
        }
        stallWatchdog.phase("await initial transcript draw")
        awaitFixtureSetup(fixture) { fixture.initialDrawObserved.get() }
        return fixture
    }

    /** A setup timeout must release the controller before a caller can take ownership of the fixture. */
    private fun awaitFixtureSetup(
        fixture: RetainedFixture,
        condition: () -> Boolean,
    ) {
        try {
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS, condition = condition)
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            stallWatchdog.phase("fixture cleanup")
            fixture.close()
            throw failure
        }
    }

    private enum class RouteReplacement { Chat, Account, Runtime }

    private enum class UpdateBoundary { Consumed, Queued, Replacement }
}

private const val FIRST_FRAME_TIMEOUT_MS = 10_000L
