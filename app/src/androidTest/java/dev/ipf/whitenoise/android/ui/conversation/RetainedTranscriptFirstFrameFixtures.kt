package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Observes actual lifecycle and root draws without changing the production conversation composition. */
@Suppress("FunctionNaming")
@Composable
internal fun ObserveRetainedFixtureDraws(
    fixture: RetainedFixture,
    rootFixture: RetainedFixture,
    retainedActivity: ComponentActivity,
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

/** Complete-window replacements are the only data source; no test edits controller timeline state. */
internal class RetainedFixture(
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
            // The direct-screen fixture owns a ready synthetic account, bypassing only bootstrap setup.
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("setPhase", AppPhase::class.java)
                .apply { isAccessible = true }
                .invoke(it, AppPhase.Ready)
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
    var navigatedAway by mutableStateOf(false)
    val navigationDrawObserved = AtomicBoolean()
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
        if (recording.get()) {
            // Keep the first-frame oracle immutable while observing eventual local convergence.
            if (draws.isEmpty()) firstDrawAt = frame.rootDrawAtUptimeMs
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
internal class ControlledConversationOwner(
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
internal class HeldPreparationDispatcher : CoroutineDispatcher() {
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
internal data class ExpectedOwnerDraw(
    val expectedOwner: ConversationController?,
    val frame: ConversationTranscriptDraw,
)

/** Owner tuple and distinct bounded rows for one mounted fixture route. */
internal data class FixtureRoute(
    val accountRef: String = ConversationTimelineTestIds.ACCOUNT_REF,
    val accountId: String = ConversationTimelineTestIds.ACCOUNT_ID,
    val groupId: String = ConversationTimelineTestIds.GROUP_ID,
    val messageA: String = ConversationTimelineTestIds.MESSAGE_A,
    val messageB: String = ConversationTimelineTestIds.MESSAGE_B,
)

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
