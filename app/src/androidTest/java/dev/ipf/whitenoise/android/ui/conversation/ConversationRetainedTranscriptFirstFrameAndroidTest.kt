package dev.ipf.whitenoise.android.ui.conversation

import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.TimelineMessage
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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Checks content at the production transcript draw, before any post-resume UI polling can hide stale frames. */
@PullRequestDeviceSmoke
@ResponsivenessDeviceAcceptance
@RunWith(AndroidJUnit4::class)
class ConversationRetainedTranscriptFirstFrameAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Already consumed background content must be in the first live draw with roster enrichment still held. */
    @Test
    fun consumedStoppedWindowIsInFirstLiveTranscriptDraw() =
        assertFirstDraw(UpdateBoundary.Consumed)

    /** Preparation already received on IO cannot lose the first draw to a coherent geometry-only gate. */
    @Test
    fun queuedLocalPreparationCommitsBeforeFirstLiveTranscriptDraw() =
        assertFirstDraw(UpdateBoundary.Queued)

    /** A second foreground epoch cannot inherit the first epoch's permission to reveal held content. */
    @Test
    fun supersededForegroundEpochStillWaitsForCurrentLocalWindow() =
        assertFirstDraw(UpdateBoundary.Queued, supersedeForeground = true)

    /** Reconnect's replacement snapshot is sufficient even when no later live update arrives. */
    @Test
    fun replacementSnapshotNeedsNoFollowingEventToReachFirstLiveDraw() =
        assertFirstDraw(UpdateBoundary.Replacement)

    /** A focused editor stays usable after the current local window reaches the resumed transcript. */
    @Test
    fun queuedLocalPreparationWithFocusedComposerReachesFirstLiveDraw() =
        assertFirstDraw(UpdateBoundary.Queued, focusComposer = true)

    /** A real window that refuses IME visibility still draws current content and accepts editor input. */
    @Test
    fun deniedImeVisibilityDoesNotHideCurrentLocalContentForever() =
        assertFirstDraw(UpdateBoundary.Consumed, focusComposer = true, denyIme = true)

    /** Background content can update the bounded window without pulling an older reader to the tail. */
    @Test
    fun olderReadingAnchorSurvivesFreshFirstLiveTranscriptDraw() =
        assertFirstDraw(UpdateBoundary.Consumed, olderReader = true)

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
            fixture.close()
        }
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
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            fixture.recording.set(true)
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.draws.isNotEmpty() }
            assertTrue(fixture.hasB)
            assertTrue(fixture.draws.first().visibleItemKeys.isNotEmpty())
            assertFalse(
                "new controller content cannot make stale painted A pass",
                ConversationTimelineTestIds.MESSAGE_B in fixture.draws.first().messageIds,
            )
        } finally {
            fixture.close()
        }
    }

    /** Runs a real Activity STOP/RESUME without a subsequent event, navigation, remote catch-up or roster reply. */
    private fun assertFirstDraw(
        boundary: UpdateBoundary,
        focusComposer: Boolean = false,
        denyIme: Boolean = false,
        olderReader: Boolean = false,
        supersedeForeground: Boolean = false,
    ) {
        val fixture = mountFixture(replacement = boundary == UpdateBoundary.Replacement, olderReader = olderReader)
        val original = composeRule.activity
        try {
            if (focusComposer) establishFocusedComposer(fixture)
            val olderAnchor =
                if (olderReader) {
                    val key = checkNotNull(fixture.readingSnapshot?.anchorItemId)
                    checkNotNull(fixture.lastDraw?.visibleItemOffsets?.entries?.firstOrNull { it.key == key }) {
                        "the intended older anchor must be painted before STOP"
                    }
                } else {
                    null
                }
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
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
            val preDrawCheckpoint = fixture.preDraws.get()
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            if (boundary == UpdateBoundary.Queued) {
                composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) {
                    fixture.preDraws.get() > preDrawCheckpoint
                }
                assertTrue("known local preparation must still hold the first draw", fixture.draws.isEmpty())
                if (supersedeForeground) {
                    composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                    composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                    assertTrue("retired foreground work cannot reveal held content", fixture.draws.isEmpty())
                }
                fixture.dispatcher.release()
            }
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS) { fixture.draws.isNotEmpty() }
            assertSame(original, composeRule.activity)
            assertResumedTranscript(fixture, olderAnchor, resumedAt, denyIme)
            composeRule.onNode(hasSetTextAction()).performTextInput(" editable")
        } finally {
            composeRule.runOnUiThread { original.window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) }
            fixture.close()
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
        Log.i(
            "WNFirstFrameTest",
            "ime_denied=$denyIme on_resume_to_draw_ms=${fixture.firstDrawAt - fixture.lastResumeAt}",
        )
    }

    /** Installs the real conversation screen with a bounded local subscription and independently delayed roster. */
    private fun mountFixture(
        replacement: Boolean = false,
        olderReader: Boolean = false,
    ): RetainedFixture {
        lateinit var fixture: RetainedFixture
        composeRule.runOnUiThread { fixture = RetainedFixture(replacement, olderReader) }
        // This suite starts from a retained, authoritative transcript, not a cold route whose
        // unread boundary still depends on the native read-state initialization fixture.
        awaitFixtureSetup(fixture) {
            fixture.controller.hasPublishedAuthoritativeTimeline && fixture.controller.timeline.isNotEmpty()
        }
        ConversationTranscriptDrawProbe.observer = fixture::observe
        ConversationTranscriptDrawProbe.compositionRowsOverride = { controller, rows ->
            if (controller === fixture.controller && fixture.paintStaleControl) fixture.staleRows else rows
        }
        composeRule.setContent {
            val view = LocalView.current
            fixture.keyboard = LocalSoftwareKeyboardController.current
            DisposableEffect(view) {
                val lifecycle = composeRule.activity.lifecycle
                val lifecycleObserver = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME && fixture.recording.get()) {
                        fixture.lastResumeAt = SystemClock.uptimeMillis()
                    }
                }
                lifecycle.addObserver(lifecycleObserver)
                val observer = view.viewTreeObserver
                val listener = android.view.ViewTreeObserver.OnPreDrawListener {
                    fixture.preDraws.incrementAndGet()
                    true
                }
                val drawListener = android.view.ViewTreeObserver.OnDrawListener {
                    val completed = ConversationTranscriptDrawProbe.beginRootDraw()
                    val live = composeRule.activity.lifecycle.currentState == Lifecycle.State.RESUMED
                    view.post { if (live) completed?.invoke() }
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
            if (fixture.mounted) {
                WhiteNoiseTheme {
                    ConversationScreen(
                        appState = fixture.appState,
                        chat = fixture.chat,
                        controller = fixture.controller,
                        onBack = {},
                        restoredScrollSnapshot = fixture.readingSnapshot,
                    )
                }
            }
        }
        awaitFixtureSetup(fixture) { fixture.initialDrawObserved.get() }
        return fixture
    }

    /** A setup timeout must release the controller before a caller can take ownership of the fixture. */
    private fun awaitFixtureSetup(fixture: RetainedFixture, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = FIRST_FRAME_TIMEOUT_MS, condition = condition)
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            fixture.close()
            throw failure
        }
    }

    /** Complete-window replacements are the only data source; no test edits controller timeline state. */
    private class RetainedFixture(replacement: Boolean, olderReader: Boolean) {
        private val a = timelineRecord(ConversationTimelineTestIds.MESSAGE_A, 1uL, "fixture A")
        private val baseRows =
            if (olderReader) {
                listOf(a) + (2..24).map { index ->
                    timelineRecord(
                        "e" + index.toString(16).padStart(63, '0'),
                        index.toULong(),
                        "fixture older row $index",
                    )
                }
            } else {
                listOf(a)
            }
        private val b = timelineRecord(ConversationTimelineTestIds.MESSAGE_B, 25uL, "fixture B")
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
                conversationTimelineTestGroup(),
            )
        val appState = conversationTimelineTestAppState(scripts.subscriptions)
        val rosterReply = CompletableDeferred<dev.ipf.marmotkit.GroupRosterFfi>()
        val dispatcher = HeldPreparationDispatcher()
        val expectedOpenCount = if (replacement) 2 else 1
        private var rosterReads = 0
        val applicationTimes = CopyOnWriteArrayList<Long>()
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupRosterReader = { _, _ ->
                    rosterReads += 1
                    if (replacement && rosterReads == 1) conversationTimelineGroupRoster() else rosterReply.await()
                },
                windowPreparationDispatcher = dispatcher,
                onWindowApplyMeasured = { applicationTimes.add(SystemClock.uptimeMillis()) },
                startOnConstruction = true,
            )
        val chat =
            ChatListItem(
                group = conversationTimelineTestGroup(),
                latest = null,
                otherMemberAccount = null,
                memberCount = 1,
                memberSnapshot = conversationTimelineMemberSnapshot(),
                projection = retainedChatRow(baseRows.last()),
            )
        var mounted by mutableStateOf(true)
        var paintStaleControl by mutableStateOf(false)
        var staleRows: List<TimelineMessage> = emptyList()
        val preDraws = java.util.concurrent.atomic.AtomicInteger()
        var keyboard: androidx.compose.ui.platform.SoftwareKeyboardController? = null
        val recording = AtomicBoolean()
        val initialDrawObserved = AtomicBoolean()
        val draws = CopyOnWriteArrayList<ConversationTranscriptDraw>()
        var firstDrawAt = 0L
        var lastResumeAt = 0L
        var lastDraw: ConversationTranscriptDraw? = null
        val hasB: Boolean
            get() = controller.timeline.any { it.record.messageIdHex == ConversationTimelineTestIds.MESSAGE_B }

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

        /** Removes the probe before releasing controller scopes and their held local work. */
        fun close() {
            ConversationTranscriptDrawProbe.observer = null
            rosterReply.complete(conversationTimelineGroupRoster())
            dispatcher.release()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.onCleared() }
        }
    }

    /** Holds only the production off-main page preparation; the receiver and main thread continue normally. */
    private class HeldPreparationDispatcher : CoroutineDispatcher() {
        val hold = AtomicBoolean()
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
                Dispatchers.Default.dispatch(EmptyCoroutineContext, task)
            }
        }
    }

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
