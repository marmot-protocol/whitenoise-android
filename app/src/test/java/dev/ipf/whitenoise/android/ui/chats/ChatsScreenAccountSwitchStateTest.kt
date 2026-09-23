package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AccountSwitchLocalSnapshot
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.updateQuickAccountSwitching
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The quick account switcher can keep the chat-list frame composed while the account underneath it
 * changes, so the viewport has to be owned by the account rather than by the composition. Rows are
 * keyed by group id, and two local members of one group share that key, so these keep the
 * composition alive across the flip rather than letting a fresh subtree hide the leak.
 *
 * Scope boundary: this drives the retained frame at the screen the viewport belongs to, which is
 * where the ownership lives. It does not stand in for the shell's own transition timing — whether
 * the decorative cue is animated or skipped for reduced motion changes nothing about these remember
 * keys — and it cannot observe a real device's fling settling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatsScreenAccountSwitchStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A shared group id must not let account B inherit where account A was reading. */
    @Test
    fun switchingAccountStartsTheNewListAtTheTopWithAnOverlappingGroupId() {
        val fixture = renderScrolledAccountA(overlapping = true)

        fixture.switchToAccountB()

        composeRule.onNodeWithTag(rowTag(fixture.firstRowIdOfB)).assertExists()
    }

    /** Disjoint datasets must reset too: correctness cannot rest on a key coincidence. */
    @Test
    fun switchingAccountStartsTheNewListAtTheTopWithDisjointGroupIds() {
        val fixture = renderScrolledAccountA(overlapping = false)

        fixture.switchToAccountB()

        composeRule.onNodeWithTag(rowTag(fixture.firstRowIdOfB)).assertExists()
    }

    /** The previous account's depth must not leave its jump-to-top affordance over a list at the top. */
    @Test
    fun switchingAccountClearsTheJumpToTopAffordance() {
        val fixture = renderScrolledAccountA(overlapping = true)
        composeRule.onNodeWithContentDescription(context.getString(R.string.scroll_to_top)).assertExists()

        fixture.switchToAccountB()

        composeRule.onNodeWithContentDescription(context.getString(R.string.scroll_to_top)).assertDoesNotExist()
    }

    /** Going back to A after B is another activation, so it starts at the top as well. */
    @Test
    fun returningToTheFirstAccountAlsoStartsAtTheTop() {
        val fixture = renderScrolledAccountA(overlapping = true)
        fixture.switchToAccountB()
        fixture.scrollTo(DEEP_INDEX)

        fixture.switchToAccountA()

        composeRule.onNodeWithTag(rowTag(fixture.firstRowIdOfA)).assertExists()
    }

    /** Recomposing without an account change is ordinary navigation and keeps the reader's place. */
    @Test
    fun recomposingTheSameAccountKeepsTheViewport() {
        val fixture = renderScrolledAccountA(overlapping = true)

        composeRule.runOnUiThread { fixture.repaint.value += 1 }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(rowTag(fixture.firstRowIdOfA)).assertDoesNotExist()
    }

    /** Viewport ownership separates accounts, runtimes and the active/archived lists from each other. */
    @Test
    fun viewportOwnershipSeparatesAccountRuntimeAndListVariant() {
        val active = ChatListViewportOwner(accountRef = ACCOUNT_A, runtimeGeneration = 0, showArchived = false)

        assertEquals(active, ChatListViewportOwner(ACCOUNT_A, 0, false))
        assertNotEquals(active, ChatListViewportOwner(ACCOUNT_B, 0, false))
        assertNotEquals(active, ChatListViewportOwner(ACCOUNT_A, 1, false))
        assertNotEquals(active, ChatListViewportOwner(ACCOUNT_A, 0, true))
    }

    /** Renders account A's list inside a retained frame and scrolls it away from the top. */
    private fun renderScrolledAccountA(overlapping: Boolean): Fixture {
        val appState = testAppState()
        val controllerA = controller(appState, ACCOUNT_A, rowsForAccountA())
        val controllerB = controller(appState, ACCOUNT_B, rowsForAccountB(overlapping))
        appState.attachChatsController(controllerA)
        val current = mutableStateOf(controllerA)
        val repaint = mutableStateOf(0)

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    // Read so an unrelated recomposition is indistinguishable from a real one.
                    repaint.value
                    ChatsScreen(
                        appState = appState,
                        controller = current.value,
                        onOpenSettings = {},
                        onOpenGroup = { _, _, _, _ -> },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val fixture =
            Fixture(
                appState = appState,
                controllerA = controllerA,
                controllerB = controllerB,
                current = current,
                repaint = repaint,
                firstRowIdOfA = rowsForAccountA().first().groupIdHex,
                firstRowIdOfB = rowsForAccountB(overlapping).first().groupIdHex,
            )
        fixture.scrollTo(DEEP_INDEX)
        return fixture
    }

    /** Holds the live composition's account-switch levers. */
    private inner class Fixture(
        val appState: WhiteNoiseAppState,
        val controllerA: ChatsController,
        val controllerB: ChatsController,
        val current: MutableState<ChatsController>,
        val repaint: MutableState<Int>,
        val firstRowIdOfA: String,
        val firstRowIdOfB: String,
    ) {
        /** Drives the list to the given row without leaving the composition. */
        fun scrollTo(index: Int) {
            composeRule
                .onNode(hasScrollToIndexAction() and hasAnyDescendant(hasTestTag(rowTag(firstRowIdOfCurrent()))))
                .performScrollToIndex(index)
            composeRule.waitForIdle()
        }

        /** Flips the retained frame to account B the way the quick switcher does. */
        fun switchToAccountB() = activate(ACCOUNT_B, controllerB)

        /** Flips the retained frame back to account A. */
        fun switchToAccountA() = activate(ACCOUNT_A, controllerA)

        /** The row id currently at the top of the composed dataset. */
        private fun rowIdOfCurrentTop(): String = if (current.value === controllerA) firstRowIdOfA else firstRowIdOfB

        /** Alias kept readable at the call site. */
        fun firstRowIdOfCurrent(): String = rowIdOfCurrentTop()

        /** Rebinds the active account and its controller inside the same composition. */
        private fun activate(
            accountRef: String,
            controller: ChatsController,
        ) {
            composeRule.runOnUiThread {
                setActiveAccountRefForTest(appState, accountRef)
                appState.attachChatsController(controller)
                current.value = controller
            }
            composeRule.waitForIdle()
        }
    }

    /** Activates an account without the surrounding runtime, whose setter is production-private. */
    private fun setActiveAccountRefForTest(
        appState: WhiteNoiseAppState,
        accountRef: String,
    ) {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setActiveAccountRef", String::class.java)
            .apply { isAccessible = true }
            .invoke(appState, accountRef)
    }

    /** Test tag the chat list puts on each row. */
    private fun rowTag(rowId: String) = "chat.row.$rowId"

    /** Twenty rows owned by account A, one of which may be shared with B. */
    private fun rowsForAccountA(): List<ChatListRowFfi> =
        List(ROW_COUNT) { index ->
            val id = if (index == SHARED_INDEX_IN_A) SHARED_GROUP_ID else groupId("a", index)
            row(groupId = id, title = "A chat $index", sortAt = (ROW_COUNT - index).toULong())
        }

    /** Twenty rows owned by account B; the shared id leads the list when the datasets overlap. */
    private fun rowsForAccountB(overlapping: Boolean): List<ChatListRowFfi> =
        List(ROW_COUNT) { index ->
            val id = if (overlapping && index == 0) SHARED_GROUP_ID else groupId("b", index)
            row(groupId = id, title = "B chat $index", sortAt = (ROW_COUNT - index).toULong())
        }

    /** Builds a locally ready controller for one account's rows. */
    private fun controller(
        appState: WhiteNoiseAppState,
        accountRef: String,
        rows: List<ChatListRowFfi>,
    ) = ChatsController(
        appState = appState,
        initialAccountRef = accountRef,
        memberSnapshotLoader = { _, _ -> emptyList() },
        initialLocalSnapshot =
            AccountSwitchLocalSnapshot(
                accountRef = accountRef,
                activeAccountIdHex = accountIdHex(accountRef),
                rows = rows,
                groups = emptyList(),
                memberIds = emptyList(),
                profiles = emptyList(),
            ),
    )

    /** Two signed-in accounts with quick switching on, which is the retained-frame path. */
    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { accountIdHex(ACCOUNT_A) },
            accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
            activeAccountRef = ACCOUNT_A,
        ).also { it.updateQuickAccountSwitching(true) }

    /** One signed-in account summary. */
    private fun account(accountRef: String) =
        AccountSummaryFfi(
            label = accountRef,
            accountIdHex = accountIdHex(accountRef),
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    /** A stable hex identity per account label. */
    private fun accountIdHex(accountRef: String) = if (accountRef == ACCOUNT_A) "a".repeat(64) else "b".repeat(64)

    /** A stable per-account group id. */
    private fun groupId(
        prefix: String,
        index: Int,
    ) = (prefix + index.toString().padStart(2, '0')).padEnd(64, '0')

    /** A minimal stable chat-list row. */
    private fun row(
        groupId: String,
        title: String,
        sortAt: ULong,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = title,
        groupName = title,
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 1uL,
        activitySortAt = sortAt,
        updatedAt = sortAt,
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

    private class InMemoryDraftPersistence : DraftPersistence {
        /** No persisted drafts participate in viewport ownership. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards writes; drafts are irrelevant to this suite. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val ROW_COUNT = 20
        const val SHARED_INDEX_IN_A = 10
        const val DEEP_INDEX = 12
        val SHARED_GROUP_ID = "5".repeat(64)
    }
}
