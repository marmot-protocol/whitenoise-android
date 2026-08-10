package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatsScreenFolderSelectionRecompositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearFolderPreferences() {
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun selectingAndClearingEmptyFolderRecomputesChipVisibility() {
        val appState = testAppState()
        val controller = ChatsController(appState)
        appState.attachChatsController(controller)
        setControllerItems(controller, listOf(chatItem("g1")))
        val folder = appState.chatFolderPreferences.createFolder(ACCOUNT_REF, "Work")!!
        val folderTag = chatListFilterChipTag(folder.id)
        var selectedFolderId by mutableStateOf<String?>(null)

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ChatsScreen(
                        appState = appState,
                        controller = controller,
                        onOpenSettings = {},
                        onOpenGroup = { _, _, _, _ -> },
                        selectedFolderId = selectedFolderId,
                        onSelectFolder = { selectedFolderId = it },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(folderTag).assertDoesNotExist()

        composeRule.runOnIdle { selectedFolderId = folder.id }
        composeRule.onNodeWithTag(folderTag).assertExists()

        composeRule.runOnIdle { selectedFolderId = null }
        composeRule.onNodeWithTag(folderTag).assertDoesNotExist()

        controller.onCleared()
    }

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun setControllerItems(
        controller: ChatsController,
        items: List<ChatListItem>,
    ) {
        ChatsController::class.java
            .getDeclaredMethod("setItems", List::class.java)
            .apply { isAccessible = true }
            .invoke(controller, items)
    }

    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private fun chatItem(groupIdHex: String): ChatListItem =
        ChatListItem(
            group = group(groupIdHex),
            latest = null,
            otherMemberAccount = null,
            memberCount = 2,
            memberSnapshot = null,
            projection =
                ChatListRowFfi(
                    selfMembership = SelfMembershipFfi.MEMBER,
                    unreadMentionCount = 0uL,
                    unreadMention = false,
                    groupIdHex = groupIdHex,
                    archived = false,
                    pendingConfirmation = false,
                    title = "Group $groupIdHex",
                    groupName = "",
                    avatarUrl = null,
                    avatar = null,
                    lastMessage = null,
                    unreadCount = 0uL,
                    hasUnread = false,
                    firstUnreadMessageIdHex = null,
                    lastReadMessageIdHex = null,
                    lastReadTimelineAt = null,
                    conversationCreatedAt = 0uL,
                    activitySortAt = 0uL,
                    updatedAt = 1uL,
                    leaveRequestPending = false,
                    leaveRequestedAtMs = null,
                    manuallyMarkedUnread = false,
                    conversationKind = ChatConversationKindFfi.UNKNOWN,
                    muted = false,
                    mutedUntilMs = null,
                    pinned = false,
                    pinnedPosition = null,
                    lifecycleState = dev.ipf.marmotkit.GroupLifecycleStateFfi.STABLE,
                    disbanding = false,
                    disbandRequest = null,
                ),
        )

    private fun group(id: String) =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = id,
            protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint-$id",
            name = "",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-$id",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints =
                        listOf(
                            AppBlobEndpointFfi(
                                locatorKind = "blossom-v1",
                                baseUrl = "https://blossom.primal.net",
                            ),
                        ),
                ),
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbanded = false,
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
        const val ACCOUNT_REF = "account"
        const val ACCOUNT_HEX = "a"
    }
}
