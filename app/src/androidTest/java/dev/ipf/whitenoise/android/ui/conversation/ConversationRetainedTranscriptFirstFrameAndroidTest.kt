package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ResponsivenessDeviceAcceptance
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DestructiveAccountWipeRuntimeState
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineGroupRoster
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.firstBlockedAt != null }
            composeRule.runOnUiThread {
                assertTrue("supersede before fallback", SystemClock.uptimeMillis() - fixture.lastResumeAt < 1_500L)
                assertTrue(fixture.controller.pendingTimelineAtForeground()?.isCompleted == false)
                assertTrue(fixture.draws.isEmpty())
                assertTrue(fixture.gateReleasedAt == null)
                owner.moveTo(Lifecycle.State.CREATED)
                owner.moveTo(Lifecycle.State.RESUMED)
            }
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.firstBlockedAt != null }
            assertTrue("retired foreground work cannot reveal held content", fixture.draws.isEmpty())
            fixture.dispatcher.release()
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.draws.isNotEmpty() }
            assertSame(retainedActivity, composeRule.activity)
            assertEquals(Lifecycle.State.RESUMED, retainedActivity.lifecycle.currentState)
            assertResumedTranscript(fixture, null, resumedAt, false)
            composeRule.onNode(hasSetTextAction()).performTextInput(" editable")
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            closeFirstDrawFixture(fixture, retainedActivity, bodyFailure)
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
            stallWatchdog.phase("await first live draw")
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.draws.isNotEmpty() }
            stallWatchdog.phase("post-draw activity lookup")
            assertSame(original, composeRule.activity)
            assertResumedTranscript(fixture, olderAnchor, resumedAt, denyIme)
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
        stallWatchdog.phase("await blocked pre-draw")
        composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.preDraws.get() > checkpoint }
        assertTrue("known local preparation must still hold the first draw", fixture.draws.isEmpty())
        assertTrue("queued preparation produced a blocked pre-draw", fixture.firstBlockedAt != null)
        stallWatchdog.phase("release queued preparation")
        fixture.dispatcher.release()
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
    ) {
        val drawn = fixture.draws.first()
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
        assertTrue(fixture.applicationTimes.last() <= fixture.firstDrawAt)
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
            ObserveFixtureDraws(active, fixture)
            if (active.mounted) {
                CompositionLocalProvider(LocalContext provides (active.owner ?: LocalContext.current)) {
                    WhiteNoiseTheme {
                        ConversationScreen(
                            appState = active.appState,
                            chat = active.chat,
                            controller = active.controller,
                            onBack = {},
                            restoredScrollSnapshot = active.readingSnapshot,
                        )
                    }
                }
            }
        }
        stallWatchdog.phase("await initial transcript draw")
        awaitFixtureSetup(fixture) { fixture.initialDrawObserved.get() }
        return fixture
    }

    /** Observes actual lifecycle and root draws without changing the production conversation composition. */
    @Suppress("FunctionNaming")
    @Composable
    private fun ObserveFixtureDraws(
        fixture: RetainedFixture,
        rootFixture: RetainedFixture,
    ) {
        val view = LocalView.current
        fixture.keyboard = LocalSoftwareKeyboardController.current
        DisposableEffect(view, fixture) {
            fixture.composition = checkNotNull(view.parent as? AbstractComposeView)
            val lifecycle = fixture.owner?.lifecycle ?: retainedActivity.lifecycle
            val lifecycleObserver =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME && fixture.recording.get()) {
                        fixture.lastResumeAt = SystemClock.uptimeMillis()
                        fixture.firstBlockedAt = null
                        fixture.gateReleasedAt = null
                    }
                }
            lifecycle.addObserver(lifecycleObserver)
            val observer = view.viewTreeObserver
            val listener =
                android.view.ViewTreeObserver.OnPreDrawListener {
                    fixture.preDraws.incrementAndGet()
                    true
                }
            val drawListener =
                android.view.ViewTreeObserver.OnDrawListener {
                    val completed = ConversationTranscriptDrawProbe.beginRootDraw()
                    val live = lifecycle.currentState == Lifecycle.State.RESUMED
                    val expectedOwner = fixture.controller
                    view.post {
                        if (live) {
                            rootFixture.rootOwnerAtCompletion = expectedOwner
                            completed?.invoke()
                        }
                    }
                }
            observer.addOnPreDrawListener(listener)
            observer.addOnDrawListener(drawListener)
            onDispose {
                lifecycle.removeObserver(lifecycleObserver)
                if (observer.isAlive) {
                    observer.removeOnPreDrawListener(listener)
                    observer.removeOnDrawListener(drawListener)
                }
            }
        }
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

    /** Complete-window replacements are the only data source; no test edits controller timeline state. */
    private class RetainedFixture(
        replacement: Boolean,
        olderReader: Boolean,
        val route: FixtureRoute = FixtureRoute(),
        appStateOverride: WhiteNoiseAppState? = null,
        includeSecondAccount: Boolean = false,
    ) {
        private val group =
            conversationTimelineTestGroup().copy(groupIdHex = route.groupId, admins = listOf(route.accountId))
        private val members =
            conversationTimelineMemberSnapshot().let { snapshot ->
                snapshot.copy(
                    members =
                        snapshot.members.map { it.copy(account = route.accountRef, memberIdHex = route.accountId) },
                )
            }
        private val roster =
            conversationTimelineGroupRoster().let { value ->
                value.copy(
                    groupIdHex = route.groupId,
                    members = value.members.map { it.copy(account = route.accountRef, memberIdHex = route.accountId) },
                )
            }
        private val a = timelineRecord(route.messageA, 1uL, "fixture A").copy(groupIdHex = route.groupId)
        private val baseRows =
            if (olderReader) {
                listOf(a) +
                    (2..24).map { index ->
                        timelineRecord(
                            "e" + index.toString(16).padStart(63, '0'),
                            index.toULong(),
                            "fixture older row $index",
                        )
                    }
            } else {
                listOf(a)
            }
        private val b = timelineRecord(route.messageB, 25uL, "fixture B").copy(groupIdHex = route.groupId)
        val pageWithB = timelinePage(*(baseRows + b).toTypedArray())
        val first = ScriptedConversationTimelineSubscription(timelinePage(*baseRows.toTypedArray()))
        val readingSnapshot =
            if (olderReader) {
                val anchor = baseRows[7].messageIdHex
                ConversationScrollSnapshot(16, 12, "msg:$anchor", anchor)
            } else {
                null
            }
        val scripts =
            ScriptedConversationLiveSubscriptions(
                if (replacement) listOf(first, ScriptedConversationTimelineSubscription(pageWithB)) else listOf(first),
                group,
            )
        val appState =
            (appStateOverride ?: createFixtureAppState(includeSecondAccount)).also {
                it.liveSubscriptionOverrides.conversation = scripts.subscriptions
            }
        val rosterReply = CompletableDeferred<dev.ipf.marmotkit.GroupRosterFfi>()
        val dispatcher = HeldPreparationDispatcher()
        val expectedOpenCount = if (replacement) 2 else 1
        private var rosterReads = 0
        val applicationTimes = CopyOnWriteArrayList<Long>()
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialMemberSnapshot = members,
                groupRosterReader = { _, _ ->
                    rosterReads += 1
                    if (replacement && rosterReads == 1) roster else rosterReply.await()
                },
                windowPreparationDispatcher = dispatcher,
                onWindowApplyMeasured = { applicationTimes.add(SystemClock.uptimeMillis()) },
                startOnConstruction = true,
            )
        val chat =
            ChatListItem(
                group = group,
                latest = null,
                otherMemberAccount = null,
                memberCount = 1,
                memberSnapshot = members,
                projection = retainedChatRow(baseRows.last()).copy(groupIdHex = route.groupId),
            )
        var ownedReplacement: RetainedFixture? = null
        var activeRoute by mutableStateOf<RetainedFixture?>(null)
        val collectEveryDraw = AtomicBoolean()
        var rootOwnerAtCompletion: ConversationController? = null
        val everyDraw = CopyOnWriteArrayList<ExpectedOwnerDraw>()
        var owner: ControlledConversationOwner? = null
        var composition: AbstractComposeView? = null
        var mounted by mutableStateOf(true)
        var paintStaleControl by mutableStateOf(false)
        var staleRows: List<TimelineMessage> = emptyList()
        val preDraws = AtomicInteger()
        var keyboard: androidx.compose.ui.platform.SoftwareKeyboardController? = null
        val recording = AtomicBoolean()
        val initialDrawObserved = AtomicBoolean()
        val draws = CopyOnWriteArrayList<ConversationTranscriptDraw>()
        var firstDrawAt = 0L
        var lastResumeAt = 0L
        var firstBlockedAt: Long? = null
        var gateReleasedAt: Long? = null
        var lastDraw: ConversationTranscriptDraw? = null
        val hasB: Boolean
            get() = controller.timeline.any { it.record.messageIdHex == ConversationTimelineTestIds.MESSAGE_B }

        /** Times actual production pre-draw decisions, separate from lifecycle and first-content draw. */
        fun observeGate(
            blocked: Boolean,
            atUptimeMs: Long,
        ) {
            if (!recording.get()) return
            if (blocked && firstBlockedAt == null) firstBlockedAt = atUptimeMs
            if (!blocked && firstBlockedAt != null && gateReleasedAt == null) gateReleasedAt = atUptimeMs
        }

        /** Records bounded in-memory evidence, without emitting message text or identifiers to a log. */
        fun observe(frame: ConversationTranscriptDraw) {
            if (frame.controller !== controller) return
            if (!recording.get()) lastDraw = frame
            initialDrawObserved.set(true)
            if (recording.get() && draws.isEmpty()) {
                firstDrawAt = frame.rootDrawAtUptimeMs
                draws.add(frame)
            }
        }

        /** Disposes the draw gate before controller cancellation so ActivityScenario cleanup can become idle. */
        fun close() {
            ConversationTranscriptDrawProbe.observer = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val host = composition
                composition = null
                try {
                    (host?.parent as? ViewGroup)?.removeView(host)
                    host?.disposeComposition()
                } finally {
                    releaseController()
                    ownedReplacement?.releaseController()
                }
            }
        }

        /** Supplies two real fixture account summaries only for cross-account ownership cases. */
        private fun createFixtureAppState(includeSecondAccount: Boolean): WhiteNoiseAppState =
            conversationTimelineTestAppState(
                scripts.subscriptions,
                accountRef = route.accountRef,
                additionalAccounts =
                    if (includeSecondAccount) {
                        listOf(AccountSummaryFfi("fixture-other", "ab".repeat(32), true, false, false, true))
                    } else {
                        emptyList()
                    },
            )

        /** Releases one controller without detaching the shared replacement composition or resetting the probe. */
        private fun releaseController() {
            rosterReply.complete(roster)
            dispatcher.release()
            controller.onCleared()
        }
    }

    /** Supplies the production context-owner lookup with deterministic epochs while the Activity stays real. */
    private class ControlledConversationOwner(
        context: Context,
    ) : ContextWrapper(context),
        LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle
            get() = registry

        /** Emits the same ordered owner events consumed by production foreground effects. */
        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    /** Holds only the production off-main page preparation; the receiver and main thread continue normally. */
    private class HeldPreparationDispatcher : CoroutineDispatcher() {
        val hold = AtomicBoolean()
        val releasedWorkCompleted = AtomicInteger()
        private val pending = ConcurrentLinkedQueue<Runnable>()
        val hasPending: Boolean get() = pending.isNotEmpty()

        /** Dispatches preparation normally until the test holds the next local receipt. */
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            if (hold.get()) pending.add(block) else Dispatchers.Default.dispatch(context, block)
        }

        /** Resumes held computation without supplying another message, roster or network event. */
        fun release() {
            hold.set(false)
            while (true) {
                val task = pending.poll() ?: return
                Dispatchers.Default.dispatch(EmptyCoroutineContext) {
                    try {
                        task.run()
                    } finally {
                        releasedWorkCompleted.incrementAndGet()
                    }
                }
            }
        }
    }

    /** Correlates the committed listener owner with painted evidence from that exact root traversal. */
    private data class ExpectedOwnerDraw(
        val expectedOwner: ConversationController?,
        val frame: ConversationTranscriptDraw,
    )

    /** Owner tuple and distinct bounded rows for one mounted fixture route. */
    private data class FixtureRoute(
        val accountRef: String = ConversationTimelineTestIds.ACCOUNT_REF,
        val accountId: String = ConversationTimelineTestIds.ACCOUNT_ID,
        val groupId: String = ConversationTimelineTestIds.GROUP_ID,
        val messageA: String = ConversationTimelineTestIds.MESSAGE_A,
        val messageB: String = ConversationTimelineTestIds.MESSAGE_B,
    )

    private enum class RouteReplacement { Chat, Account, Runtime }

    private enum class UpdateBoundary { Consumed, Queued, Replacement }
}

private const val FIRST_FRAME_TIMEOUT_MS = 10_000L

/** Supplies the authoritative fully-read entry metadata required by the retained-resume fixture. */
private fun retainedChatRow(lastRecord: TimelineMessageRecordFfi): ChatListRowFfi =
    ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = ConversationTimelineTestIds.GROUP_ID,
        archived = false,
        pendingConfirmation = false,
        title = "Retained transcript fixture",
        groupName = "Retained transcript fixture",
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = lastRecord.messageIdHex,
        lastReadTimelineAt = lastRecord.timelineAt,
        conversationCreatedAt = 1uL,
        activitySortAt = lastRecord.timelineAt,
        updatedAt = lastRecord.timelineAt,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = ChatConversationKindFfi.GROUP,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )
