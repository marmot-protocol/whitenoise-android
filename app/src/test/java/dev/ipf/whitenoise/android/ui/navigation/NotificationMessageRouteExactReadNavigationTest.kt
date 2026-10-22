package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.notifications.NotificationScenario
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ChatsSubscriptionHandle
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.FfiChatListWindowHandle
import dev.ipf.whitenoise.android.state.MarmotWindowTestFakes
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.notifiedMessagePreview
import dev.ipf.whitenoise.android.ui.conversation.NotificationLandingTarget
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A bound, finished chat list that does not carry the tapped conversation is not proof the
 * conversation is gone. These start from exactly that snapshot on the already-active account and
 * bind each outcome of the exact per-group read to what the shell then does.
 *
 * Scope boundary: Robolectric with a fake engine. It cannot reproduce a real device's projection
 * timing — only which branch the shell takes for each answer the read gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationMessageRouteExactReadNavigationTest {
    private val effectJobs = MainLooperEffectJobs()

    @get:Rule
    val composeRule = createComposeRule(effectContext = effectJobs)

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A row the broad list never carried still opens the conversation, once, with its message id. */
    @Test
    fun anExactReadThatFindsTheRowOpensAndConsumesTheTapOnce() {
        val fixture = render(RowAnswer.FOUND)

        awaitCondition("the tap was never consumed") { fixture.handled.get() }

        assertTrue("the exact per-group read was never invoked", fixture.exactReads.get() >= 1)
        assertEquals(1, fixture.handledCount.get())
        assertEquals(MESSAGE_ID, fixture.handledMessageId)
        assertNull("no unavailable message may be shown", fixture.appState.toast)
    }

    /**
     * The card's message reaches the conversation open as its own captured target, paired with the account
     * and group it was issued for. It is neither the oldest unread row nor the newest message, so a landing
     * on either of those could not be mistaken for it, and the durable read-through cursor stays separate.
     */
    @Test
    fun theCapturedMessageIsNeitherTheOldestUnreadNorTheNewestAndCarriesItsAccountAndGroup() {
        val fixture = render(RowAnswer.FOUND, withHolder = true)
        val holder = requireNotNull(fixture.holder)

        try {
            awaitCondition("the tap was never consumed") { fixture.handled.get() }

            val chat = requireNotNull(holder.selectedChat.value)
            assertEquals(OLDEST_UNREAD_ID, chat.projection?.firstUnreadMessageIdHex)
            assertEquals(NEWEST_ID, chat.projection?.lastMessage?.messageIdHex)
            assertNotEquals(OLDEST_UNREAD_ID, MESSAGE_ID)
            assertNotEquals(NEWEST_ID, MESSAGE_ID)
            val context = holder.selectedChatOpenContext.value
            assertEquals(NotificationLandingTarget(ACCOUNT_REF, GROUP_ID, MESSAGE_ID), context.notificationTarget)
            assertEquals(MESSAGE_ID, context.notificationReadThroughMessageId)
            assertNull("search focus is a different owner", context.focusMessageId)
        } finally {
            // MainShell releases only a holder it created, so this injected one would keep its controllers
            // and their coroutines alive into later tests on the same JVM.
            composeRule.runOnIdle { holder.release() }
        }
    }

    /** A missing row is inconclusive: the tap stays pending with no toast and no handled callback. */
    @Test
    fun aMissingRowKeepsTheTapPendingWithoutClaimingTheConversationIsGone() {
        val fixture = render(RowAnswer.MISSING)

        awaitCondition("the exact read never ran") { fixture.exactReads.get() >= 1 }
        composeRule.waitForIdle()

        assertFalse("an inconclusive read must not consume the tap", fixture.handled.get())
        assertNull("an inconclusive read must not claim the conversation is gone", fixture.appState.toast)
    }

    /** A thrown exact read is inconclusive in exactly the same way as a missing row. */
    @Test
    fun aThrownExactReadKeepsTheTapPendingWithoutClaimingTheConversationIsGone() {
        val fixture = render(RowAnswer.THROWS)

        awaitCondition("the exact read never ran") { fixture.exactReads.get() >= 1 }
        composeRule.waitForIdle()

        assertFalse("a storage failure must not consume the tap", fixture.handled.get())
        assertNull("a storage failure must not claim the conversation is gone", fixture.appState.toast)
    }

    /**
     * A newer tap for another message replaces the pending one while its exact read is still held. The newer read
     * opens the conversation and is acknowledged once. Releasing the retired read afterwards must neither consume
     * nor reopen anything, so the selection keeps the newer request's open context (#3309).
     */
    @Test
    fun aHeldExactReadReleasedAfterANewerTapNeitherConsumesNorReopensIt() {
        val held = HeldRouteReads(effectJobs)
        val exactReads = AtomicInteger(0)
        val appState =
            appState(
                fakeMarmot(RowAnswer.FOUND, exactReads, beforeRead = held::awaitRelease),
            )
        val holder = MainShellStateHolder(appState, SavedStateHandle())
        val inbound = mutableStateOf<NotificationTarget?>(null)
        val requestId = mutableLongStateOf(REQUEST_ID)
        val handled = CopyOnWriteArrayList<Pair<Long, String?>>()
        appState.setAppInForeground(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                MainShell(
                    appState = appState,
                    stateHolder = holder,
                    inboundNotificationTarget = inbound.value,
                    inboundNotificationRequestId = requestId.longValue,
                    onNotificationTargetHandled = { target, handledRequestId ->
                        handled += handledRequestId to target.messageIdHex
                        if (handledRequestId == requestId.longValue) inbound.value = null
                    },
                )
            }
        }
        try {
            awaitBoundChatList(holder, appState)
            composeRule.runOnIdle { inbound.value = messageTarget() }
            awaitCondition("the first read never ran") { held.started.count == 0L }
            assertTrue("the held read must not consume its tap", handled.isEmpty())
            composeRule.runOnIdle {
                held.stopHolding()
                inbound.value = messageTarget().copy(messageIdHex = NEWEST_ID)
                requestId.longValue = REQUEST_ID + 1L
            }
            awaitCondition("the newer tap was never consumed") {
                handled.any { it.first == REQUEST_ID + 1L && it.second == NEWEST_ID }
            }
            val openedContext = holder.selectedChatOpenContext.value

            settleReleasedReads(held)

            assertEquals(listOf((REQUEST_ID + 1L) to NEWEST_ID), handled.toList())
            assertEquals(openedContext, holder.selectedChatOpenContext.value)
            assertEquals(REQUEST_ID + 1L, openedContext.notificationRouteTraceRequestId)
            assertEquals(NEWEST_ID, openedContext.notificationTarget?.messageIdHex)
            assertNull("a retired read must not present anything", appState.toast)
        } finally {
            held.release.countDown()
            composeRule.runOnIdle { holder.release() }
        }
    }

    /** Waits for every retired routing owner and its native children before checking the newer route's state. */
    private fun settleReleasedReads(held: HeldRouteReads) {
        held.release.countDown()
        awaitCondition("the retired routing jobs never completed") { held.completed() }
        composeRule.waitForIdle()
    }

    /** Captures every held read's route owner atomically with deciding whether that read must wait. */
    private class HeldRouteReads(
        private val effectJobs: MainLooperEffectJobs,
    ) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val lock = Any()
        private var holding = true
        private val jobs = mutableListOf<Job>()

        /** Holds each accepted read until release, preserving its actual routing job for a completion barrier. */
        fun awaitRelease() {
            val held =
                synchronized(lock) {
                    holding.also { if (it) jobs += effectJobs.currentMainOwner() }
                }
            if (held) {
                started.countDown()
                check(release.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "retired reads were never released"
                }
            }
        }

        /** Prevents subsequent reads from joining the held set before a newer tap is submitted. */
        fun stopHolding() {
            synchronized(lock) { holding = false }
        }

        /** A completed route owner has also finished its blocking IO child and any stale continuation. */
        fun completed(): Boolean = synchronized(lock) { jobs.isNotEmpty() && jobs.all { it.isCompleted } }
    }

    /** Test-local context follows Compose effects into IO without adding a production routing hook. */
    private class MainLooperEffectJobs : AbstractCoroutineContextElement(Key), ThreadContextElement<Job?> {
        private val current = ThreadLocal<Job?>()
        private val mainJobs = ConcurrentHashMap.newKeySet<Job>()

        /** Remembers jobs executing on Main before their IO children run the fake binding. */
        override fun updateThreadContext(context: CoroutineContext): Job? {
            val previous = current.get()
            val job = context[Job]
            current.set(job)
            if (job != null && Looper.myLooper() == Looper.getMainLooper()) mainJobs += job
            return previous
        }

        /** Restores the prior coroutine when a dispatcher leaves this thread. */
        override fun restoreThreadContext(
            context: CoroutineContext,
            oldState: Job?,
        ) {
            current.set(oldState)
        }

        /** Finds the nearest captured Main ancestor, the effect that owns this particular native read. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun currentMainOwner(): Job {
            var job = checkNotNull(current.get()) { "the binding did not inherit its Compose effect context" }
            while (job !in mainJobs) job = checkNotNull(job.parent) { "the read has no captured Main effect owner" }
            return job
        }

        private companion object Key : CoroutineContext.Key<MainLooperEffectJobs>
    }

    /** Finishes the broad-list binding before arming the exact-read race under the documented ready-list state. */
    private fun awaitBoundChatList(
        holder: MainShellStateHolder,
        appState: WhiteNoiseAppState,
    ) {
        val chats = composeRule.runOnIdle { holder.chatsController(ACCOUNT_REF, appState.runtimeGeneration) }
        awaitCondition("the initial chat list never finished binding") {
            chats.boundAccountRef == ACCOUNT_REF && chats.hasLoadedLocalSnapshot && !chats.isLoading
        }
    }

    /**
     * Renders MainShell over a ready, empty chat list with one pending message notification. With
     * [withHolder] the shell shares a state holder the test can read the committed open context from.
     */
    private fun render(
        answer: RowAnswer,
        withHolder: Boolean = false,
    ): Fixture {
        val exactReads = AtomicInteger(0)
        val appState = appState(fakeMarmot(answer, exactReads))
        val handled = AtomicBoolean(false)
        val handledCount = AtomicInteger(0)
        val fixture = Fixture(appState, exactReads, handled, handledCount)
        fixture.holder = if (withHolder) MainShellStateHolder(appState, SavedStateHandle()) else null

        appState.setAppInForeground(true)
        composeRule.setContent {
            var inboundTarget by remember { mutableStateOf<NotificationTarget?>(messageTarget()) }
            WhiteNoiseTheme {
                MainShell(
                    appState = appState,
                    stateHolder = fixture.holder,
                    inboundNotificationTarget = inboundTarget,
                    inboundNotificationRequestId = REQUEST_ID,
                    onNotificationTargetHandled = { target, _ ->
                        handled.set(true)
                        handledCount.incrementAndGet()
                        fixture.handledMessageId = target.messageIdHex
                        inboundTarget = null
                    },
                )
            }
        }
        return fixture
    }

    /** What the shell observed while routing one tap. */
    private class Fixture(
        val appState: WhiteNoiseAppState,
        val exactReads: AtomicInteger,
        val handled: AtomicBoolean,
        val handledCount: AtomicInteger,
    ) {
        var handledMessageId: String? = null
        var holder: MainShellStateHolder? = null
    }

    /** The notification under test: a message in a group the broad list does not carry. */
    private fun messageTarget() =
        NotificationTarget(
            accountRef = ACCOUNT_REF,
            groupIdHex = GROUP_ID,
            messageIdHex = MESSAGE_ID,
            kind = NotificationTargetKind.MESSAGE,
        )

    /** Polls the composition until [condition] holds or the route budget runs out. */
    private fun awaitCondition(
        failureMessage: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + ROUTE_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError(failureMessage)
    }

    /**
     * An engine whose broad list is empty and whose per-group read answers as the case requires. [beforeRead] runs
     * on the read's worker thread first, so a case can hold reads there.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun fakeMarmot(
        answer: RowAnswer,
        exactReads: AtomicInteger,
        beforeRead: () -> Unit = {},
    ): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name.substringBefore('-')) {
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                "onboardingRecoveryRequired" -> false
                "onboardingSnapshot" -> null
                "openChatListWindow" ->
                    MarmotWindowTestFakes.chatListWindow(
                        view = arguments?.getOrNull(1) as? ChatListViewFfi ?: ChatListViewFfi.CHATS,
                        rows = emptyList(),
                    )
                "subscribeAccountAttention" -> MarmotWindowTestFakes.accountAttention()
                "subscribeBlockedUsers" -> MarmotWindowTestFakes.blockList()
                "chatListRow" -> {
                    exactReads.incrementAndGet()
                    beforeRead()
                    when (answer) {
                        RowAnswer.FOUND -> chatListRow()
                        RowAnswer.MISSING -> throw NoSuchElementException("no such chat-list row")
                        RowAnswer.THROWS -> error("projection storage is unavailable")
                    }
                }
                "toString" -> "NotificationMessageRouteExactReadMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("Unexpected Marmot call: ${method.name}")
            }
        } as MarmotInterface

    /** One signed-in account, already active, with the engine above behind it. */
    private fun appState(marmot: MarmotInterface): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(NoopDraftPersistence),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        ).also { state ->
            state.liveSubscriptionOverrides.chatList = quietEmptyChatList()
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }

    /** Keeps the broad projection ready and empty without an absent native subscription causing rebinding. */
    private fun quietEmptyChatList() =
        ChatListLiveSubscriptions(
            openChatListWindow = { _, view ->
                FfiChatListWindowHandle(
                    MarmotWindowTestFakes.chatListWindow(view, emptyList(), next = { awaitCancellation() }),
                )
            },
            openChats = { _, _ -> NoGroupRecords },
        )

    /** The account has no broad-list groups; notification routing must use the exact read instead. */
    private object NoGroupRecords : ChatsSubscriptionHandle {
        /** The complete initial group snapshot is empty. */
        override fun snapshot(): List<AppGroupRecordFfi> = emptyList()

        /** A quiet live stream stays open until its owning bind is cancelled. */
        override suspend fun next(): AppGroupRecordFfi? = awaitCancellation()

        /** The fixture owns no native handle. */
        override fun close() = Unit
    }

    /** The row the exact read returns when the conversation is in fact still there. */
    private fun chatListRow() =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = GROUP_ID,
            archived = false,
            pendingConfirmation = false,
            title = "Still here",
            groupName = "Still here",
            avatarUrl = null,
            avatar = null,
            lastMessage = notifiedMessagePreview().copy(messageIdHex = NEWEST_ID),
            unreadCount = 3uL,
            hasUnread = true,
            firstUnreadMessageIdHex = OLDEST_UNREAD_ID,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 1uL,
            activitySortAt = 2uL,
            updatedAt = 2uL,
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

    /** How the exact per-group read answers for one case. */
    private enum class RowAnswer { FOUND, MISSING, THROWS }

    private object NoopDraftPersistence : DraftPersistence {
        /** No persisted drafts participate in notification routing. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards writes; drafts are irrelevant to this suite. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        const val REQUEST_ID = 41L
        const val ROUTE_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        val ACCOUNT_ID = "aa".repeat(32)
        val GROUP_ID = "bb".repeat(32)
        val MESSAGE_ID = NotificationScenario.NOTIFIED_ID
        val OLDEST_UNREAD_ID = NotificationScenario.OLDEST_UNREAD_ID
        val NEWEST_ID = NotificationScenario.NEWEST_ID
    }
}
