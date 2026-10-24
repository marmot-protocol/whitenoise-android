package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarBytesFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ChatListWindowHandle
import dev.ipf.whitenoise.android.state.ChatsSubscriptionHandle
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.MarmotWindowTestFakes
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineGroupRoster
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.emptyGroupRecord
import dev.ipf.whitenoise.android.state.emptyTimelinePage
import dev.ipf.whitenoise.android.state.notificationChatListRow
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_HEAD_INPUT_GATE_MILLIS
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_ROW_PLACEMENT_MILLIS
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

/** Layout control through the production Back route; pixel/alpha visibility requires separate qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatListConversationReturnRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A coherent hidden promotion retains every row in the returning layout and settles at native order. */
    @Test
    fun returningAfterHiddenPromotionKeepsEveryRetainedRowInTheLayout() {
        val fixture = ReturnFixture()
        val mounted = mutableStateOf(true)
        val holder = MainShellStateHolder(fixture.appState, SavedStateHandle())
        val controller =
            holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, fixture.appState.runtimeGeneration)
        fixture.appState.setAppInForeground(true)
        composeRule.setContent {
            if (mounted.value) {
                WhiteNoiseTheme {
                    MainShell(appState = fixture.appState, stateHolder = holder)
                }
            }
        }
        try {
            awaitCondition { controller.items.size == fixture.rows.size && !controller.isLoading }
            // The first live head closes row input during its bounded placement transition.
            composeRule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 500L)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chat.row.${fixture.target.groupIdHex}").assertIsDisplayed().performClick()
            awaitCondition { holder.selectedChat.value?.id == fixture.target.groupIdHex }
            // Wait for the outgoing list slot to leave composition before mutating the hidden projection.
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.waitForIdle()
            val promoted = fixture.target.copy(activitySortAt = 100uL, updatedAt = 100uL)
            fixture.activeWindow.replaceRows(listOf(promoted) + fixture.rows.dropLast(1))
            awaitCondition {
                controller.chatRows.firstOrNull { it.groupIdHex == promoted.groupIdHex }?.activitySortAt == 100uL
            }
            assertEquals(fixture.rows.map { it.groupIdHex }, controller.items.map { it.id })

            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithContentDescription("Back").performClick()
            val returnFrames = mutableListOf<Map<String, Rect>>()
            repeat(75) { frame ->
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.runOnIdle { }
                if (frame >= 14) returnFrames += rowBounds(fixture.rows)
            }

            val expected = listOf(promoted.groupIdHex) + fixture.rows.dropLast(1).map { it.groupIdHex }
            assertTrue("the actual return route must have drawn", returnFrames.isNotEmpty())
            assertEquals(expected, controller.items.map { it.id })
            returnFrames.forEachIndexed { frame, bounds ->
                bounds.forEach { (id, rowBounds) ->
                    assertTrue(
                        "return frame $frame lost row $id from the layout",
                        rowBounds.width > 0f && rowBounds.height > 0f,
                    )
                }
            }
            fixture.rows.forEach { row -> composeRule.onNodeWithTag("chat.row.${row.groupIdHex}").assertIsDisplayed() }
        } finally {
            composeRule.mainClock.autoAdvance = true
            composeRule.runOnIdle {
                mounted.value = false
                holder.release()
            }
            composeRule.waitForIdle()
        }
    }

    /** The actual shell must retain the view choice after disposing the outgoing ChatsScreen. */
    @Test
    fun unreadFilterSurvivesConversationBackUntilShowAll() {
        val fixture = ReturnFixture(unreadTarget = true)
        withMountedUnreadShell(fixture) { holder ->
            composeRule.onNodeWithTag("chats.unread_filter").performClick()
            awaitCondition { fixture.unreadSnapshotReads.get() > 0 }
            composeRule.onNodeWithTag("chats.unread_filter").assertTextContains("Show all")
            composeRule.onNodeWithTag("chat.row.${fixture.rows.first().groupIdHex}").assertDoesNotExist()
            // Filtering changes both the head and keyed layout; real pointer gates must settle before touching a row.
            composeRule.mainClock.advanceTimeBy(
                maxOf(CHAT_LIST_HEAD_INPUT_GATE_MILLIS, CHAT_LIST_ROW_PLACEMENT_MILLIS.toLong()) + 16L,
            )
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chat.row.${fixture.target.groupIdHex}").assertIsDisplayed().performClick()
            awaitCondition { holder.selectedChat.value?.id == fixture.target.groupIdHex }
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.waitForIdle()
            val readsBeforeBack = fixture.unreadSnapshotReads.get()
            composeRule.onNodeWithContentDescription("Back").performClick()
            awaitCondition { holder.selectedChat.value == null && fixture.unreadSnapshotReads.get() > readsBeforeBack }
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chats.unread_filter").assertTextContains("Show all")
            composeRule.onNodeWithTag("chat.row.${fixture.rows.first().groupIdHex}").assertDoesNotExist()
            composeRule.onNodeWithTag("chats.unread_filter").performClick()
            composeRule.onNodeWithTag("chats.unread_filter").assertTextContains("Unread only")
            composeRule.onNodeWithTag("chat.row.${fixture.rows.first().groupIdHex}").assertIsDisplayed()
        }
    }

    /** A new folder or account cannot inherit the old view choice, including a return to that account. */
    @Test
    fun unreadFilterClearsOnFolderAndAccountChanges() {
        val fixture = ReturnFixture(unreadTarget = true)
        val folder =
            checkNotNull(
                fixture.appState.chatFolderPreferences.createFolder(
                    ConversationTimelineTestIds.ACCOUNT_REF,
                    "Unread scope",
                ),
            )
        assertTrue(
            fixture.appState.chatFolderPreferences.setChatInFolder(
                ConversationTimelineTestIds.ACCOUNT_REF,
                folder.id,
                fixture.target.groupIdHex,
                included = true,
            ),
        )
        withMountedUnreadShell(fixture) {
            composeRule.onNodeWithTag("chats.unread_filter").performClick()
            awaitCondition { fixture.unreadSnapshotReads.get() > 0 }
            composeRule.onNodeWithTag("chats.folders").performScrollToNode(hasTestTag("chats.folder.${folder.id}"))
            composeRule.onNodeWithTag("chats.folder.${folder.id}").performClick()
            composeRule.onNodeWithTag("chats.unread_filter").assertTextContains("Unread only")
            composeRule.onNodeWithTag("chats.folders").performScrollToNode(hasTestTag("chats.scope.chats"))
            composeRule.onNodeWithTag("chats.scope.chats").performClick()
            composeRule.onNodeWithTag("chats.unread_filter").performClick()
            composeRule.onNodeWithTag("chats.unread_filter").assertTextContains("Show all")
            composeRule.runOnIdle { replaceUnreadAccount(fixture.appState, "other-unread-account") }
            composeRule.waitForIdle()
            composeRule.runOnIdle { replaceUnreadAccount(fixture.appState, ConversationTimelineTestIds.ACCOUNT_REF) }
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.onNodeWithTag("chats.unread_filter").assertTextContains("Unread only")
        }
    }

    /** Mounts production navigation with the existing real controller and always releases its jobs. */
    private fun withMountedUnreadShell(
        fixture: ReturnFixture,
        exercise: (MainShellStateHolder) -> Unit,
    ) {
        val mounted = mutableStateOf(true)
        val holder = MainShellStateHolder(fixture.appState, SavedStateHandle())
        val controller =
            holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, fixture.appState.runtimeGeneration)
        fixture.appState.setAppInForeground(true)
        composeRule.setContent {
            if (mounted.value) WhiteNoiseTheme { MainShell(appState = fixture.appState, stateHolder = holder) }
        }
        try {
            awaitCondition { controller.items.size == fixture.rows.size && !controller.isLoading }
            composeRule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 500L)
            composeRule.waitForIdle()
            exercise(holder)
        } finally {
            composeRule.runOnIdle {
                mounted.value = false
                holder.release()
            }
            composeRule.waitForIdle()
        }
    }

    /** Uses the same private account setter as mounted settings isolation tests. */
    private fun replaceUnreadAccount(
        appState: WhiteNoiseAppState,
        account: String,
    ) {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setActiveAccountRef", String::class.java)
            .apply { isAccessible = true }
            .invoke(appState, account)
    }

    /** Measures the production lazy items, including animated placement and clipping. */
    private fun rowBounds(rows: List<ChatListRowFfi>): Map<String, Rect> =
        rows.associate { row ->
            val node =
                composeRule.onNodeWithTag("chat.row.${row.groupIdHex}", useUnmergedTree = true).fetchSemanticsNode()
            row.groupIdHex to node.layoutInfo.coordinates.boundsInRoot()
        }

    /** Polls only for observable asynchronous shell/controller settlement. */
    private fun awaitCondition(condition: () -> Boolean) {
        composeRule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
        composeRule.waitForIdle()
    }
}

/** Local authoritative rows and a real conversation controller; no keys, relay, or native persistence. */
internal class ReturnFixture(
    avatarAssetForRow: (ChatListRowFfi) -> AvatarAssetFfi? = { null },
    private val readAvatars: suspend (List<String>) -> List<AvatarBytesFfi> = { emptyList() },
    private val unreadTarget: Boolean = false,
) {
    val unreadSnapshotReads = AtomicInteger()
    val avatarReads = AtomicInteger()
    val avatarRequests = AtomicInteger()
    val rows =
        (0..5).map { index ->
            notificationChatListRow().copy(
                groupIdHex =
                    if (index == 5) ConversationTimelineTestIds.GROUP_ID else "%02x".format(index + 10).repeat(16),
                title = "Return chat $index",
                groupName = "Return chat $index",
                activitySortAt = (10 - index).toULong(),
                unreadCount = if (unreadTarget && index == 5) 1uL else 0uL,
                hasUnread = unreadTarget && index == 5,
            )
        }
    val target = rows.last()
    val activeWindow = ReturnWindow(ChatListViewFfi.CHATS, rows, avatarAssetForRow)
    private val timelines =
        ScriptedConversationLiveSubscriptions(
            listOf(ScriptedConversationTimelineSubscription(emptyTimelinePage())),
            conversationTimelineTestGroup(),
        )
    val appState: WhiteNoiseAppState =
        conversationTimelineTestAppState(timelines.subscriptions).also { state ->
            WhiteNoiseAppState::class.java.getDeclaredField("marmotRuntime").apply { isAccessible = true }.set(
                state,
                AppMarmotRuntime(rootPath = "test", marmot = runtime()),
            )
            state.liveSubscriptionOverrides.chatList =
                ChatListLiveSubscriptions(
                    openChatListWindow = { _, view ->
                        if (view == ChatListViewFfi.CHATS) activeWindow else ReturnWindow(view, emptyList())
                    },
                    openChats = { _, _ -> ReturnGroups(rows.map(::emptyGroupRecord)) },
                    presentedChatList =
                        if (unreadTarget) {
                            { _, _ ->
                                unreadSnapshotReads.incrementAndGet()
                                MarmotWindowTestFakes.windowSnapshot(ChatListViewFfi.CHATS, rows).rows
                            }
                        } else {
                            null
                        },
                )
        }

    /** Unexpected native enrichment reads fail explicitly; the shell's ordinary degraded paths handle them. */
    private fun runtime(): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, args ->
            when (method.name.substringBefore('-')) {
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                "onboardingRecoveryRequired" -> false
                "onboardingSnapshot" -> null
                "subscribeAccountAttention" -> MarmotWindowTestFakes.accountAttention()
                "subscribeBlockedUsers" -> MarmotWindowTestFakes.blockList()
                "groupRoster" -> conversationTimelineGroupRoster()
                "groupMembers" ->
                    listOf(
                        AppGroupMemberRecordFfi(
                            ConversationTimelineTestIds.ACCOUNT_ID,
                            ConversationTimelineTestIds.ACCOUNT_REF,
                            true,
                        ),
                    )
                "groupDetails" -> groupDetails()
                "chatListRow" -> rows.first { it.groupIdHex == args?.getOrNull(1) }
                "readAvatarAssets" -> {
                    avatarReads.incrementAndGet()
                    @Suppress("UNCHECKED_CAST")
                    val references = args!![1] as List<String>
                    val operation: suspend () -> List<AvatarBytesFfi> = { readAvatars(references) }
                    @Suppress("UNCHECKED_CAST")
                    operation.startCoroutine(args.last() as Continuation<List<AvatarBytesFfi>>)
                    COROUTINE_SUSPENDED
                }
                "requestAvatarAssets" -> avatarRequests.incrementAndGet().let { emptyList<AvatarAssetFfi>() }
                else -> objectMethod(proxy, method.name, args)
            }
        } as MarmotInterface

    /** Implements object identity without disguising unexpected native enrichment calls. */
    private fun objectMethod(
        proxy: Any,
        name: String,
        args: Array<out Any?>?,
    ): Any =
        when (name) {
            "toString" -> "ChatListReturnRuntime"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected return-fixture call: $name")
        }

    /** Keeps the mounted destination on its consented, authoritative empty timeline. */
    private fun groupDetails() =
        GroupDetailsFfi(
            group = conversationTimelineTestGroup(),
            members = emptyList(),
            mlsState =
                AppGroupMlsStateFfi(
                    groupIdHex = ConversationTimelineTestIds.GROUP_ID,
                    protocolProfile = AppProtocolProfileFfi.CURRENT,
                    lifecycleState = GroupLifecycleStateFfi.STABLE,
                    epoch = 0uL,
                    memberCount = 1u,
                    unrecoverable = false,
                    requiredAppComponents = emptyList(),
                    disbandingEnabled = false,
                    disbanding = false,
                    disbandingBlockers = emptyList(),
                    disbandRequest = null,
                ),
        )
}

/** A complete retained list whose viewport commands do not introduce extra source changes. */
internal class ReturnWindow(
    view: ChatListViewFfi,
    rows: List<ChatListRowFfi>,
    private val avatarAssetForRow: (ChatListRowFfi) -> AvatarAssetFfi? = { null },
) : ChatListWindowHandle {
    @Volatile private var frame = window(view, rows)
    private val updates = Channel<ChatListWindowSnapshotFfi>(Channel.UNLIMITED)

    /** Gives selected stored avatars to the real row mapper without substituting a presentation renderer. */
    private fun window(
        view: ChatListViewFfi,
        rows: List<ChatListRowFfi>,
        sequence: ULong = 0uL,
    ): ChatListWindowSnapshotFfi {
        val snapshot = MarmotWindowTestFakes.windowSnapshot(view, rows, sequence)
        return snapshot.copy(
            rows =
                snapshot.rows.map { presented ->
                    val asset = avatarAssetForRow(presented.row) ?: return@map presented
                    presented.copy(
                        avatarAsset = asset,
                        presentation =
                            presented.presentation.copy(
                                avatar =
                                    SelectedAvatarFfi.RemoteImage(
                                        "https://example.invalid/avatar",
                                        presented.row.title,
                                    ),
                            ),
                    )
                },
        )
    }

    /** Advances the authoritative source together with its stream, so viewport commands cannot undo a promotion. */
    fun replaceRows(rows: List<ChatListRowFfi>) {
        frame = window(frame.view, rows, frame.sequence + 1uL)
        check(updates.trySend(frame).isSuccess)
    }

    /** Supplies the authoritative initial rows. */
    override fun snapshot(): ChatListWindowSnapshotFfi = frame

    /** Holds the live stream until its owner cancels it. */
    override suspend fun next(): ChatListWindowSnapshotFfi? = updates.receiveCatching().getOrNull()

    /** This six-row account has no next or previous page. */
    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ): ChatListWindowSnapshotFfi = frame

    /** The synthetic viewport retains the same complete window. */
    override suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ): ChatListWindowSnapshotFfi = frame

    /** The fixture has no shifted native window. */
    override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi = frame

    /** The controller cancels the only pending receive. */
    override fun close() {
        updates.close()
    }
}

/** Group metadata paired with the same initial chat-list snapshot. */
private class ReturnGroups(
    private val groups: List<AppGroupRecordFfi>,
) : ChatsSubscriptionHandle {
    /** Supplies the native group projection used by the list. */
    override fun snapshot(): List<AppGroupRecordFfi> = groups

    /** Retains stable metadata throughout the navigation under test. */
    override suspend fun next(): AppGroupRecordFfi? = awaitCancellation()

    /** Receives are owned and cancelled by the production controller. */
    override fun close() = Unit
}
