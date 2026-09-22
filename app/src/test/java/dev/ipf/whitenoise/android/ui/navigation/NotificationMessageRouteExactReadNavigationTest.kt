package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MarmotWindowTestFakes
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    @get:Rule
    val composeRule = createComposeRule()

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

    /** Renders MainShell over a ready, empty chat list with one pending message notification. */
    private fun render(answer: RowAnswer): Fixture {
        val exactReads = AtomicInteger(0)
        val appState = appState(fakeMarmot(answer, exactReads))
        val handled = AtomicBoolean(false)
        val handledCount = AtomicInteger(0)
        val fixture = Fixture(appState, exactReads, handled, handledCount)

        appState.setAppInForeground(true)
        composeRule.setContent {
            var inboundTarget by remember { mutableStateOf<NotificationTarget?>(messageTarget()) }
            WhiteNoiseTheme {
                MainShell(
                    appState = appState,
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

    /** An engine whose broad list is empty and whose per-group read answers as the case requires. */
    @Suppress("CyclomaticComplexMethod")
    private fun fakeMarmot(
        answer: RowAnswer,
        exactReads: AtomicInteger,
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
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
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
            lastMessage = null,
            unreadCount = 1uL,
            hasUnread = true,
            firstUnreadMessageIdHex = MESSAGE_ID,
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
        val MESSAGE_ID = "cc".repeat(32)
    }
}
