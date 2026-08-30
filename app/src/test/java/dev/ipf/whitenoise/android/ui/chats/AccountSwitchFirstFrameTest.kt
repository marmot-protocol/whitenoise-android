package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListAvatarFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AccountSwitchLocalSnapshot
import dev.ipf.whitenoise.android.state.AccountSwitchProfileSeed
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.emptyGroupRecord
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
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
        composeRule.onNodeWithText(context.getString(R.string.connectivity_connecting)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.connectivity_connected)).assertDoesNotExist()
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

    @Test
    fun completeTargetIdentitySnapshotOwnsTheFirstComposition() =
        runBlocking {
            val appState = identityAppState()
            val snapshot = completeIdentitySnapshot()
            // Production seed-before-handoff ordering and activation visibility are covered by
            // AccountSwitchLocalSnapshotOrderingTest and AccountSwitchPerformanceIntegrationTest.
            // This screen test isolates the resulting target snapshot's first composition.
            snapshot.profiles.forEach(appState::applyAccountSwitchProfileSeed)
            val controller =
                ChatsController(
                    appState = appState,
                    initialAccountRef = TARGET_ACCOUNT,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                    initialLocalSnapshot = snapshot,
                )
            appState.attachChatsController(controller)

            val firstItems = controller.items.associateBy { it.id }
            assertFalse("the complete target snapshot must publish synchronously", controller.isLoading)
            org.junit.Assert.assertEquals(TARGET_NAMED_AVATAR, firstItems.getValue(NAMED_GROUP_ID).group.avatarUrl)
            org.junit.Assert.assertEquals(
                TARGET_MEMBER_AVATAR_HASH,
                firstItems.getValue(MEMBER_GROUP_ID).group.imageHashHex,
            )
            org.junit.Assert.assertEquals(PEER_ID, chatListItemAvatarAccount(firstItems.getValue(DIRECT_GROUP_ID)))
            org.junit.Assert.assertEquals(PEER_AVATAR, appState.avatarUrl(PEER_ID))

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

            composeRule.onNodeWithText(TARGET_NAMED_TITLE).assertIsDisplayed()
            composeRule.onNodeWithText(PEER_NAME).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(ACCOUNT_A_NAME, substring = true).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(WORK_ACCOUNT_NAME, substring = true).assertIsDisplayed()
            composeRule.onNodeWithText(OLD_ACCOUNT_CHAT_TITLE).assertDoesNotExist()
            composeRule.onNodeWithText(context.getString(R.string.no_chats_yet)).assertDoesNotExist()

            controller.onCleared()
        }

    private fun completeIdentitySnapshot(): AccountSwitchLocalSnapshot {
        val named =
            row(title = TARGET_NAMED_TITLE, groupId = NAMED_GROUP_ID)
                .copy(avatarUrl = TARGET_NAMED_AVATAR)
        val direct =
            row(title = "", groupId = DIRECT_GROUP_ID)
                .copy(groupName = "", conversationKind = ChatConversationKindFfi.DIRECT)
        val memberDerived =
            row(title = "", groupId = MEMBER_GROUP_ID)
                .copy(
                    groupName = "",
                    avatar =
                        ChatListAvatarFfi(
                            imageHashHex = TARGET_MEMBER_AVATAR_HASH,
                            imageKeyHex = "redacted-test-key",
                            imageNonceHex = "redacted-test-nonce",
                            imageUploadKeyHex = "redacted-test-upload-key",
                            mediaType = "image/png",
                        ),
                )
        return AccountSwitchLocalSnapshot(
            accountRef = TARGET_ACCOUNT,
            activeAccountIdHex = TARGET_ACCOUNT_HEX,
            rows = listOf(named, direct, memberDerived),
            groups =
                listOf(
                    emptyGroupRecord(named).copy(
                        name = TARGET_NAMED_TITLE,
                        avatarUrl = TARGET_NAMED_AVATAR,
                    ),
                    emptyGroupRecord(direct),
                    emptyGroupRecord(memberDerived).copy(imageHashHex = TARGET_MEMBER_AVATAR_HASH),
                ),
            memberIds =
                listOf(
                    AppGroupMemberIdsFfi(
                        DIRECT_GROUP_ID,
                        listOf(TARGET_ACCOUNT_HEX, PEER_ID),
                        emptyList(),
                    ),
                    AppGroupMemberIdsFfi(
                        MEMBER_GROUP_ID,
                        listOf(TARGET_ACCOUNT_HEX, PEER_ID, MEMBER_OTHER_ID),
                        emptyList(),
                    ),
                ),
            profiles =
                listOf(
                    profileSeed(PEER_ID, PEER_NAME, PEER_AVATAR),
                    profileSeed(ACCOUNT_A_HEX, ACCOUNT_A_NAME, ACCOUNT_A_AVATAR),
                    profileSeed(WORK_ACCOUNT_HEX, WORK_ACCOUNT_NAME, WORK_ACCOUNT_AVATAR),
                ),
        )
    }

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = listOf(activeAccount()),
            activeAccountRef = TARGET_ACCOUNT,
        )

    private fun identityAppState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { TARGET_ACCOUNT_HEX },
            accounts =
                listOf(
                    activeAccount(),
                    account(ACCOUNT_A, ACCOUNT_A_HEX),
                    account(WORK_ACCOUNT, WORK_ACCOUNT_HEX),
                ),
            activeAccountRef = TARGET_ACCOUNT,
            profileRefreshRequest = {},
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

    private fun account(
        label: String,
        id: String,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = id,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    private fun profile(
        displayName: String,
        avatar: String,
    ) = UserProfileMetadataFfi(
        name = displayName.lowercase(),
        displayName = displayName,
        about = null,
        picture = avatar,
        nip05 = null,
        lud16 = null,
    )

    private fun profileSeed(
        id: String,
        displayName: String,
        avatar: String,
    ) = AccountSwitchProfileSeed(id, profile(displayName, avatar), displayName, avatar)

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
        groupId: String = GROUP_ID,
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
        const val ACCOUNT_A = "account-a"
        const val WORK_ACCOUNT = "account-work"
        val TARGET_ACCOUNT_HEX = "b".repeat(64)
        val ACCOUNT_A_HEX = "a".repeat(64)
        val WORK_ACCOUNT_HEX = "c".repeat(64)
        val PEER_ID = "d".repeat(64)
        val MEMBER_OTHER_ID = "e".repeat(64)
        val GROUP_ID = "1".repeat(64)
        val NAMED_GROUP_ID = "2".repeat(64)
        val DIRECT_GROUP_ID = "3".repeat(64)
        val MEMBER_GROUP_ID = "4".repeat(64)
        const val CACHED_TITLE = "Target cached chat"
        const val CONVERGED_TITLE = "Target converged chat"
        const val TARGET_NAMED_TITLE = "Target planning"
        const val TARGET_NAMED_AVATAR = "https://profiles.example/target-group.png"
        const val TARGET_MEMBER_AVATAR_HASH = "target-member-avatar-hash"
        const val PEER_NAME = "Target Alice"
        const val PEER_AVATAR = "https://profiles.example/target-alice.png"
        const val ACCOUNT_A_NAME = "Previous personal account"
        const val ACCOUNT_A_AVATAR = "https://profiles.example/account-a.png"
        const val WORK_ACCOUNT_NAME = "Target work switch"
        const val WORK_ACCOUNT_AVATAR = "https://profiles.example/account-work.png"
        const val OLD_ACCOUNT_CHAT_TITLE = "Old account private chat"
    }
}
