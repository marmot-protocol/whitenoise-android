package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentedRow
import dev.ipf.whitenoise.android.ui.common.captureClickCallbackForReplay
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatsScreenFolderSelectionRecompositionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearFolderPreferences() {
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    /** Predictive cancellation preserves scope; committed Back unwinds the folder before leaving Chats. */
    @Test fun folderBackUnwindsOnlyOnCommitAndRootCanExit() {
        val appState = testAppState()
        val controller = ChatsController(appState)
        setControllerItems(controller, listOf(chatItem("g1")))
        val folder = appState.chatFolderPreferences.createFolder(ACCOUNT_REF, "Work")!!
        appState.chatFolderPreferences.setChatInFolder(ACCOUNT_REF, folder.id, "g1", true)
        var selected by mutableStateOf<String?>(folder.id)
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatsScreen(
                    appState,
                    controller,
                    {},
                    { _, _, _, _ -> },
                    selectedFolderId = selected,
                    onSelectFolder = { selected = it },
                )
            }
        }
        composeRule.runOnIdle {
            val dispatcher = composeRule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(40f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
            assertEquals(folder.id, selected)
            dispatcher.onBackPressed()
            assertEquals(null, selected)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertFalse(composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
        controller.onCleared()
    }

    /** Exclude acts after menu dismissal; an old folder's captured command cannot affect a new selection. */
    @Test fun exclusionUsesTheOpeningFolderAndRejectsStaleCapturedActions() {
        val appState = testAppState()
        val controller = ChatsController(appState)
        setControllerItems(controller, listOf(chatItem("g1")))
        val store = appState.chatFolderPreferences
        val first = store.createFolder(ACCOUNT_REF, "First")!!
        val second = store.createFolder(ACCOUNT_REF, "Second")!!
        listOf(first, second).forEach { store.setChatInFolder(ACCOUNT_REF, it.id, "g1", true) }
        var selected by mutableStateOf<String?>(first.id)
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatsScreen(
                    appState,
                    controller,
                    {},
                    { _, _, _, _ -> },
                    selectedFolderId = selected,
                    onSelectFolder = { selected = it },
                )
            }
        }
        composeRule.onNodeWithTag("chat.row.g1").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        val old = composeRule.onNodeWithTag("chat.action.ExcludeFolder").captureClickCallbackForReplay()
        composeRule.runOnIdle { selected = second.id }
        composeRule.runOnIdle { old() }
        assertEquals(emptySet<String>(), store.excludedChats(ACCOUNT_REF, first.id))
        assertEquals(emptySet<String>(), store.excludedChats(ACCOUNT_REF, second.id))
        composeRule.onNodeWithTag("chat.row.g1").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        composeRule.onNodeWithTag("chat.action.ExcludeFolder").performClick()
        assertEquals(setOf("g1"), store.excludedChats(ACCOUNT_REF, second.id))
        composeRule.onNodeWithTag("chat.row.g1").assertDoesNotExist()
        controller.onCleared()
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

    @Test fun editingFolderRuleReloadsUnreadBeforeClaimingCompleteness() {
        val appState = testAppState()
        val reread = CompletableDeferred<List<dev.ipf.marmotkit.PresentedChatRowFfi>>()
        var reads = 0
        val native = presentedRow("d1".repeat(32)).let { it.copy(row = it.row.copy(manuallyMarkedUnread = true)) }
        appState.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("not used") },
                openChats = { _, _ -> error("not used") },
                presentedChatList = { _, _ ->
                    reads++
                    if (reads == 1) listOf(native) else reread.await()
                },
            )
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        val folder = appState.chatFolderPreferences.createFolder(ACCOUNT_REF, "Work")!!
        appState.chatFolderPreferences.setFolderRule(
            ACCOUNT_REF,
            folder.id,
            ChatFolderRule(includeAll = true, includeMuted = true),
        )
        setControllerItems(controller, listOf(chatItem(native.row.groupIdHex)))
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatsScreen(appState, controller, {}, { _, _, _, _ -> }, selectedFolderId = folder.id)
            }
        }
        composeRule.onNodeWithTag("chats.unread_filter").performClick()
        composeRule.waitUntil { reads == 1 }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            appState.chatFolderPreferences.setFolderRule(
                ACCOUNT_REF,
                folder.id,
                ChatFolderRule(includeMemberPubkeys = setOf("a1".repeat(32)), includeMuted = true),
            )
        }
        composeRule.waitUntil { reads == 2 }
        composeRule.onNodeWithTag(dev.ipf.whitenoise.android.ui.common.FULL_SCREEN_LOADING_TEST_TAG).assertExists()
        composeRule.runOnIdle { reread.complete(emptyList()) }
    }

    @Test fun unreadRefreshKeepsUsefulRowsMountedUntilReplacementIsReady() {
        val appState = testAppState()
        val replacement = CompletableDeferred<List<dev.ipf.marmotkit.PresentedChatRowFfi>>()
        val id = "d1".repeat(32)
        val native = presentedRow(id).let { it.copy(row = it.row.copy(manuallyMarkedUnread = true)) }
        var reads = 0
        appState.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("not used") },
                openChats = { _, _ -> error("not used") },
                presentedChatList = { _, _ ->
                    reads++
                    if (reads == 1) listOf(native) else replacement.await()
                },
            )
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        try {
            val retained =
                chatItem(id).let {
                    it.copy(projection = checkNotNull(it.projection).copy(manuallyMarkedUnread = true))
                }
            setControllerItems(controller, listOf(retained))
            composeRule.setContent {
                WhiteNoiseTheme { ChatsScreen(appState, controller, {}, { _, _, _, _ -> }) }
            }
            composeRule.onNodeWithTag("chats.unread_filter").performClick()
            composeRule.waitUntil { reads == 1 }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chat.row.$id").assertExists()
            val revisionBeforeUpdate =
                composeRule.runOnIdle {
                    assertEquals(ACCOUNT_REF, controller.accountRef)
                    val revision = controller.forwardTargetsRevision
                    controller.applyChatListRow(native.row.copy(activitySortAt = 10uL))
                    assertTrue(controller.containsGroup(id))
                    revision
                }
            // The real row-update API coalesces recompute on Android Main after 16ms; Compose's
            // frame clock does not advance Robolectric's delayed Android-looper work.
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            composeRule.runOnIdle { assertTrue(controller.forwardTargetsRevision > revisionBeforeUpdate) }
            composeRule.waitUntil { reads >= 2 }
            composeRule.onNodeWithTag("chat.row.$id").assertExists()
            composeRule.onNodeWithTag("chats.unread_refresh").assertExists()
            composeRule
                .onNodeWithTag(dev.ipf.whitenoise.android.ui.common.FULL_SCREEN_LOADING_TEST_TAG)
                .assertDoesNotExist()
            composeRule.runOnIdle { replacement.complete(listOf(native)) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chats.unread_refresh").assertDoesNotExist()
        } finally {
            composeRule.runOnIdle {
                replacement.complete(emptyList())
                controller.onCleared()
            }
        }
    }

    @Test fun localRemovalNeverReappearsDuringDelayedOrFailedUnreadRefresh() {
        val appState = testAppState()
        val replacement = CompletableDeferred<List<dev.ipf.marmotkit.PresentedChatRowFfi>>()
        val deleted = "d1".repeat(32)
        val surviving = "d2".repeat(32)
        val native =
            listOf(deleted, surviving).map { id ->
                presentedRow(id).let { it.copy(row = it.row.copy(manuallyMarkedUnread = true)) }
            }
        var reads = 0
        appState.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("not used") },
                openChats = { _, _ -> error("not used") },
                presentedChatList = { _, _ ->
                    reads++
                    if (reads == 1) native else replacement.await()
                },
            )
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        try {
            native.forEach { controller.applyChatListRow(it.row) }
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            composeRule.setContent {
                WhiteNoiseTheme { ChatsScreen(appState, controller, {}, { _, _, _, _ -> }) }
            }
            composeRule.onNodeWithTag("chats.unread_filter").performClick()
            composeRule.waitUntil { reads == 1 }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chat.row.$deleted").assertExists()
            composeRule.onNodeWithTag("chat.row.$surviving").assertExists()
            composeRule.runOnIdle {
                controller.applyChatListSubscriptionUpdate(
                    ACCOUNT_REF,
                    ChatListSubscriptionUpdateFfi.RemoveRow(ChatListUpdateTriggerFfi.REMOVED, deleted),
                )
                assertFalse(controller.containsGroup(deleted))
            }
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            composeRule.waitUntil { reads >= 2 }
            composeRule.onNodeWithTag("chat.row.$deleted").assertDoesNotExist()
            composeRule.onNodeWithTag("chat.row.$surviving").assertExists()
            composeRule.onNodeWithTag("chats.unread_refresh").assertExists()
            composeRule.runOnIdle { replacement.completeExceptionally(IllegalStateException("read failed")) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chat.row.$deleted").assertDoesNotExist()
            composeRule.onNodeWithTag("chat.row.$surviving").assertExists()
            composeRule.onNodeWithText(context.getString(R.string.pull_unread_retry)).assertExists()
        } finally {
            composeRule.runOnIdle {
                replacement.complete(emptyList())
                controller.onCleared()
            }
        }
    }

    @Test fun openingSearchRestoresAllChatsInTheSelectedFolder() {
        val appState = testAppState()
        val unread = "d1".repeat(32)
        val read = "d2".repeat(32)
        val outside = "d3".repeat(32)
        val native =
            listOf(unread, read, outside).map { id ->
                presentedRow(id).let { it.copy(row = it.row.copy(manuallyMarkedUnread = id == unread)) }
            }
        appState.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("not used") },
                openChats = { _, _ -> error("not used") },
                presentedChatList = { _, _ -> native },
            )
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        try {
            native.forEach { controller.applyChatListRow(it.row) }
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            val folder = appState.chatFolderPreferences.createFolder(ACCOUNT_REF, "Work")!!
            listOf(unread, read).forEach {
                appState.chatFolderPreferences.setChatInFolder(ACCOUNT_REF, folder.id, it, true)
            }
            var search by mutableStateOf(GlobalSearchState())
            composeRule.setContent {
                WhiteNoiseTheme {
                    ChatsScreen(
                        appState,
                        controller,
                        {},
                        { _, _, _, _ -> },
                        selectedFolderId = folder.id,
                        globalSearchState = search,
                        onGlobalSearchStateChange = { transform -> search = transform(search) },
                    )
                }
            }
            composeRule.onNodeWithTag("chats.unread_filter").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chat.row.$unread").assertExists()
            composeRule.onNodeWithTag("chat.row.$read").assertDoesNotExist()
            composeRule.onNodeWithContentDescription(context.getString(R.string.chat_list_search_open)).performClick()
            composeRule.onNodeWithTag("chats.searchField").assertExists()
            composeRule.onNodeWithTag("chat.row.$read").assertExists()
            composeRule.onNodeWithTag("chat.row.$outside").assertDoesNotExist()
            composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithTag("chats.searchField").assertDoesNotExist()
            composeRule.onNodeWithTag("chat.row.$unread").assertExists()
            composeRule.onNodeWithTag("chat.row.$read").assertExists()
            composeRule.onNodeWithTag("chat.row.$outside").assertDoesNotExist()
            composeRule.onNodeWithText(context.getString(R.string.pull_unread_show_all)).assertDoesNotExist()
        } finally {
            composeRule.runOnIdle { controller.onCleared() }
        }
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
