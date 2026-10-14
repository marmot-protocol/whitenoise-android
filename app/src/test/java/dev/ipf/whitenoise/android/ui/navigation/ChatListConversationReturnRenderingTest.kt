package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.ProductRecordResultFfi
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
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Exercises the real list-origin open/Back route with a row promoted while list rendering is paused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatListConversationReturnRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The revealed destination must already place every retained row at its final location. */
    @Test
    fun returningAfterHiddenPromotionNeverDrawsThePreConversationOrder() {
        val fixture = ReturnFixture()
        val mounted = mutableStateOf(true)
        val holder = MainShellStateHolder(fixture.appState, SavedStateHandle())
        val controller = holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, fixture.appState.runtimeGeneration)
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
            composeRule.onNodeWithTag("chat.row.${fixture.target.groupIdHex}").assertIsDisplayed().performClick()
            awaitCondition { holder.selectedChat.value?.id == fixture.target.groupIdHex }
            // Wait for the outgoing list slot to leave composition before mutating the hidden projection.
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.waitForIdle()
            val promoted = fixture.target.copy(activitySortAt = 100uL, updatedAt = 100uL)
            composeRule.runOnIdle { controller.applyChatListRow(promoted) }
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
            val settled = returnFrames.last()
            returnFrames.forEachIndexed { frame, bounds ->
                settled.forEach { (id, finalBounds) ->
                    assertEquals("return frame $frame moved retained row $id after reveal", finalBounds.top, bounds.getValue(id).top, 0.5f)
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

    /** Measures the production lazy items, including animated placement and clipping. */
    private fun rowBounds(rows: List<ChatListRowFfi>): Map<String, Rect> =
        rows.associate { row ->
            val node = composeRule.onNodeWithTag("chat.row.${row.groupIdHex}", useUnmergedTree = true).fetchSemanticsNode()
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
private class ReturnFixture {
    val rows =
        (0..5).map { index ->
            notificationChatListRow().copy(
                groupIdHex = if (index == 5) ConversationTimelineTestIds.GROUP_ID else "%02x".format(index + 10).repeat(16),
                title = "Return chat $index",
                groupName = "Return chat $index",
                activitySortAt = (10 - index).toULong(),
                unreadCount = 0uL,
                hasUnread = false,
            )
        }
    val target = rows.last()
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
                    openChatListWindow = { _, view -> ReturnWindow(view, if (view == ChatListViewFfi.CHATS) rows else emptyList()) },
                    openChats = { _, _ -> ReturnGroups(rows.map(::emptyGroupRecord)) },
                )
        }

    /** Unexpected native enrichment reads fail explicitly; the shell's ordinary degraded paths handle them. */
    private fun runtime(): MarmotInterface =
        Proxy.newProxyInstance(MarmotInterface::class.java.classLoader, arrayOf(MarmotInterface::class.java)) { proxy, method, args ->
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
                "toString" -> "ChatListReturnRuntime"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected return-fixture call: ${method.name}")
            }
        } as MarmotInterface

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
private class ReturnWindow(
    view: ChatListViewFfi,
    rows: List<ChatListRowFfi>,
) : ChatListWindowHandle {
    private val frame = MarmotWindowTestFakes.windowSnapshot(view, rows)

    /** Supplies the authoritative initial rows. */
    override fun snapshot(): ChatListWindowSnapshotFfi = frame

    /** Holds the live stream until its owner cancels it. */
    override suspend fun next(): ChatListWindowSnapshotFfi? = awaitCancellation()

    /** This six-row account has no next or previous page. */
    override suspend fun page(sequence: ULong, direction: ChatListPageDirectionFfi, count: UInt): ChatListWindowSnapshotFfi = frame

    /** The synthetic viewport retains the same complete window. */
    override suspend fun setVisibleAnchor(sequence: ULong, groupIdHex: String): ChatListWindowSnapshotFfi = frame

    /** The fixture has no shifted native window. */
    override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi = frame

    /** The controller cancels the only pending receive. */
    override fun close() = Unit
}

/** Group metadata paired with the same initial chat-list snapshot. */
private class ReturnGroups(private val groups: List<AppGroupRecordFfi>) : ChatsSubscriptionHandle {
    /** Supplies the native group projection used by the list. */
    override fun snapshot(): List<AppGroupRecordFfi> = groups

    /** Retains stable metadata throughout the navigation under test. */
    override suspend fun next(): AppGroupRecordFfi? = awaitCancellation()

    /** Receives are owned and cancelled by the production controller. */
    override fun close() = Unit
}
