package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatFolderSortOrder
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.sortFolderChatItems
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** Sorting follows the selected folder only; live titles recompute it and All retains its source order. */
    @Test
    fun folderSortReactsToTitleUpdatesAndLeavesAllUnchanged() {
        val state = testAppState()
        val controller = ChatsController(state)
        state.attachChatsController(controller)
        val z = namedChat("z", "Zulu")
        val a = namedChat("a", "Alpha")
        setControllerItems(controller, listOf(z, a))
        val folder = state.chatFolderPreferences.createFolder(ACCOUNT_REF, "Work")!!
        state.chatFolderPreferences.commitFolderDraft(
            ACCOUNT_REF,
            folder.id,
            null,
            "",
            setOf("a", "z"),
            null,
            sortOrder = ChatFolderSortOrder.NAME,
        )
        var selected by mutableStateOf<String?>(folder.id)
        var search by mutableStateOf(GlobalSearchState())
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatsScreen(
                    state,
                    controller,
                    onOpenSettings = {},
                    onOpenGroup = { _, _, _, _ -> },
                    globalSearchState = search,
                    selectedFolderId = selected,
                    onSelectFolder = { selected = it },
                )
            }
        }
        assertRowBefore("a", "z")
        composeRule.runOnIdle { setControllerItems(controller, listOf(namedChat("z", "Aardvark"), a)) }
        assertRowBefore("z", "a")
        composeRule.runOnIdle {
            selected = null
            setControllerItems(controller, listOf(z, a))
        }
        assertRowBefore("z", "a")
        composeRule.runOnIdle {
            selected = folder.id
            search = GlobalSearchState(isOpen = true, query = "l")
        }
        assertRowBefore("z", "a")
        controller.onCleared()
    }

    /** Read-state changes update unread-first ordering even inside the seeded archived folder. */
    @Test
    fun archivedFolderSortingFollowsLiveUnreadChanges() {
        val state = testAppState()
        val controller = ChatsController(state)
        state.attachChatsController(controller)
        val z = namedChat("z", "Zulu")
        val a = namedChat("a", "Alpha")

        fun unread(item: ChatListItem) = item.copy(projection = item.projection!!.copy(hasUnread = true))
        setControllerItems(controller, listOf(z, unread(a)), archived = true)
        val id = dev.ipf.whitenoise.android.state.ChatFolderPreferences.SYSTEM_FOLDER_ARCHIVED_ID
        state.chatFolderPreferences.foldersFor(ACCOUNT_REF)
        state.chatFolderPreferences.commitFolderDraft(
            ACCOUNT_REF,
            id,
            null,
            "",
            emptySet(),
            ChatFolderRule(archivedOnly = true, includeMuted = true),
            sortOrder = ChatFolderSortOrder.UNREAD,
        )
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatsScreen(
                    state,
                    controller,
                    onOpenSettings = {},
                    onOpenGroup = { _, _, _, _ -> },
                    selectedFolderId = id,
                )
            }
        }
        assertRowBefore("a", "z")
        composeRule.runOnIdle { setControllerItems(controller, listOf(unread(z), a), archived = true) }
        assertRowBefore("z", "a")
        controller.onCleared()
    }

    /** Every mode keeps pending/manual pin precedence; unread sorting retains draft-aware source order. */
    @Test
    fun folderModesPreservePinnedPendingAndDefaultOrder() {
        val pending = namedChat("pending", "Z").let { it.copy(group = it.group.copy(pendingConfirmation = true)) }
        val pin = namedChat("pin", "Y").let { it.copy(projection = it.projection!!.copy(pinned = true)) }
        val read = namedChat("read", "Alpha")
        val unread = namedChat("unread", "Zulu").let { it.copy(projection = it.projection!!.copy(hasUnread = true)) }
        val source = listOf(pending, pin, read, unread)

        fun ordered(order: ChatFolderSortOrder): List<String> {
            val rows = sortFolderChatItems(source, order, ACCOUNT_HEX) { it.projection!!.title }
            return rows.map { it.id }
        }
        assertEquals(listOf("pending", "pin", "read", "unread"), ordered(ChatFolderSortOrder.RECENT))
        assertEquals(listOf("pending", "pin", "read", "unread"), ordered(ChatFolderSortOrder.NAME))
        assertEquals(listOf("pending", "pin", "unread", "read"), ordered(ChatFolderSortOrder.UNREAD))
        val nowRead = unread.copy(projection = unread.projection!!.copy(hasUnread = false))
        assertEquals(
            listOf("read", "unread"),
            sortFolderChatItems(listOf(read, nowRead), ChatFolderSortOrder.UNREAD, ACCOUNT_HEX) { "" }.map { it.id },
        )
        val tied = listOf(namedChat("b", "Same"), namedChat("a", "same"))
        assertEquals(
            listOf("a", "b"),
            sortFolderChatItems(tied, ChatFolderSortOrder.NAME, ACCOUNT_HEX) { it.projection!!.title }.map { it.id },
        )
    }

    /** Uses real row bounds so the test observes the shipping list order. */
    private fun assertRowBefore(
        first: String,
        second: String,
    ) {
        val firstTop =
            composeRule
                .onNodeWithTag("chat.row.$first")
                .fetchSemanticsNode()
                .boundsInRoot.top
        val secondTop =
            composeRule
                .onNodeWithTag("chat.row.$second")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("$first should precede $second", firstTop < secondTop)
    }

    /** A named group whose rendered title matches the provided profile projection. */
    private fun namedChat(
        id: String,
        title: String,
    ): ChatListItem =
        chatItem(id).let {
            it.copy(
                group = it.group.copy(name = title, profilePresent = true),
                projection = it.projection!!.copy(title = title, groupName = title),
            )
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

    /** A deferred consent slot is reachable on Chats and removed while New Chat owns the screen. */
    @Test
    fun consentWaitsForChatsWhenNewMessageFlowIsOpen() {
        val appState = testAppState()
        val controller = ChatsController(appState)
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatsScreen(
                    appState = appState,
                    controller = controller,
                    onOpenSettings = {},
                    onOpenGroup = { _, _, _, _ -> },
                    diagnosticsPrompt = { Text("Pending consent") },
                )
            }
        }
        composeRule.onNodeWithText("Pending consent").assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.new_message)).performClick()
        composeRule.onNodeWithText("Pending consent").assertDoesNotExist()
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
        archived: Boolean = false,
    ) {
        val source =
            if (archived) {
                items.map { item ->
                    item.copy(
                        group = item.group.copy(archived = true),
                        projection = item.projection?.copy(archived = true),
                    )
                }
            } else {
                items
            }
        ChatsController::class.java
            .getDeclaredMethod(if (archived) "setArchivedItems" else "setItems", List::class.java)
            .apply { isAccessible = true }
            .invoke(controller, source)
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
