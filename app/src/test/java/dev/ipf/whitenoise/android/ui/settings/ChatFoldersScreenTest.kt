package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
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
class ChatFoldersScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearFolderPreferences() {
        app
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun customFolderChatCountUpdatesWhenMembershipChangesWhileComposed() {
        val accountRef = ACCOUNT_REF
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore(InMemoryDraftPersistence()),
                accountIdHexResolver = { null },
                accounts = listOf(activeAccount()),
                activeAccountRef = accountRef,
            )
        val chatsController = ChatsController(appState)
        appState.attachChatsController(chatsController)
        setChatsControllerItems(chatsController, listOf(chatItem("g1")))

        val folder = appState.chatFolderPreferences.createFolder(accountRef, "Work")!!

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ChatFoldersScreen(appState = appState, onBack = {})
                }
            }
        }
        composeRule.waitForIdle()

        val zeroChats = app.resources.getQuantityString(R.plurals.chat_folder_chat_count, 0, 0)
        composeRule
            .onNode(hasText("Work") and hasText(zeroChats))
            .assertIsDisplayed()

        composeRule.runOnUiThread {
            appState.chatFolderPreferences.setChatInFolder(accountRef, folder.id, "g1", included = true)
        }
        composeRule.waitForIdle()

        val oneChat = app.resources.getQuantityString(R.plurals.chat_folder_chat_count, 1, 1)
        composeRule
            .onNode(hasText("Work") and hasText(oneChat))
            .assertIsDisplayed()
    }

    private fun setChatsControllerItems(
        controller: ChatsController,
        items: List<ChatListItem>,
    ) {
        val setter =
            ChatsController::class.java.getDeclaredMethod("setItems", List::class.java).apply {
                isAccessible = true
            }
        setter.invoke(controller, items)
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
            encryptedMedia = encryptedMedia(),
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
        )

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net"),
                ),
        )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "acct-a"
        val ACCOUNT_HEX = "a".repeat(64)
    }
}
