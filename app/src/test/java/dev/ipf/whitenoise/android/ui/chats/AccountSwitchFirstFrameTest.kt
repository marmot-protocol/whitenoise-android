package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Screen-level frame sequence coverage for issue #2094. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class AccountSwitchFirstFrameTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun targetSnapshotIsTheFirstFrameAndLiveConvergenceUpdatesItInPlace() {
        val appState = testAppState()
        val controller =
            ChatsController(
                appState = appState,
                initialAccountRef = TARGET_ACCOUNT,
                memberSnapshotLoader = { _, _ -> emptyList() },
                initialLocalSnapshot = snapshot(row(title = CACHED_TITLE)),
            )
        appState.attachChatsController(controller)

        assertFalse("the target controller must be locally ready before composition", controller.isLoading)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ChatsScreen(
                        appState = appState,
                        controller = controller,
                        onOpenSettings = {},
                        onOpenGroup = { _, _, _, _ -> },
                    )
                }
            }
        }

        composeRule.onNodeWithText(CACHED_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.no_chats_yet)).assertDoesNotExist()
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)

        composeRule.runOnIdle {
            controller.applyChatListRow(row(title = CONVERGED_TITLE, updatedAt = 2uL))
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(CONVERGED_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(CONVERGED_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(CACHED_TITLE).assertDoesNotExist()
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)

        controller.onCleared()
    }

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = listOf(activeAccount()),
            activeAccountRef = TARGET_ACCOUNT,
        )

    private fun activeAccount() =
        AccountSummaryFfi(
            label = TARGET_ACCOUNT,
            accountIdHex = TARGET_ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private fun snapshot(row: ChatListRowFfi) =
        AccountSwitchLocalSnapshot(
            accountRef = TARGET_ACCOUNT,
            activeAccountIdHex = TARGET_ACCOUNT_HEX,
            rows = listOf(row),
            groups = emptyList(),
            memberIds = emptyList(),
            profiles = emptyList(),
        )

    private fun row(
        title: String,
        updatedAt: ULong = 1uL,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = GROUP_ID,
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
        activitySortAt = updatedAt,
        updatedAt = updatedAt,
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
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val TARGET_ACCOUNT = "account-b"
        val TARGET_ACCOUNT_HEX = "b".repeat(64)
        val GROUP_ID = "1".repeat(64)
        const val CACHED_TITLE = "Target cached chat"
        const val CONVERGED_TITLE = "Target converged chat"
    }
}
