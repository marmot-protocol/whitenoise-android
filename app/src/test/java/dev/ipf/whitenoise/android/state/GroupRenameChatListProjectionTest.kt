package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.applyChatListSearchAndFilter
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A committed group rename reaches every chat-list projection immediately, without waiting for the
 * group-state subscription and without letting a stale snapshot undo it (#2696).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class GroupRenameChatListProjectionTest {
    /** The conversation and every chat-list projection show the new name before any subscription event. */
    @Test
    fun successfulRenameUpdatesEveryProjectionImmediately() =
        runBlocking {
            val appState = testAppState()
            val chats = seededChats(appState)
            val conversation = conversationController(appState)

            assertTrue(conversation.updateGroupProfile(NEW_NAME, "described"))

            assertEquals(NEW_NAME, conversation.group.name)
            assertEquals(NEW_NAME, conversation.title())
            flushChatListRecompute()
            val item = chats.items.single()
            assertEquals(NEW_NAME, chatListItemDisplayTitle(item, appState, GroupTitleCopy.Default))
            assertEquals(NEW_NAME, item.sanitizedNamedTitle)
            assertEquals(listOf(item.id), sortChatListItems(chats.items).map { it.id })
            assertEquals(
                listOf(item.id),
                applyChatListSearchAndFilter(chats.items, "Saturday", appState, GroupTitleCopy.Default).map { it.id },
            )
            assertTrue(
                applyChatListSearchAndFilter(chats.items, OLD_NAME, appState, GroupTitleCopy.Default).isEmpty(),
            )
            chats.onCleared()
        }

    /** A folder scoped to this chat, and the archived list, both carry the new name. */
    @Test
    fun renameReachesFolderAndArchivedProjections() =
        runBlocking {
            val appState = testAppState()
            val chats = seededChats(appState)
            val conversation = conversationController(appState)
            assertTrue(conversation.updateGroupProfile(NEW_NAME, ""))
            flushChatListRecompute()

            val folderScoped =
                applyChatListSearchAndFilter(
                    source = chats.items,
                    rawQuery = "",
                    appState = appState,
                    titleCopy = GroupTitleCopy.Default,
                    folderChatIds = setOf(GROUP_ID.lowercase()),
                )
            assertEquals(NEW_NAME, chatListItemDisplayTitle(folderScoped.single(), appState, GroupTitleCopy.Default))

            chats.applyChatListRow(row(groupName = NEW_NAME).copy(archived = true))
            flushChatListRecompute()
            assertEquals(
                NEW_NAME,
                chatListItemDisplayTitle(chats.archivedItems.single(), appState, GroupTitleCopy.Default),
            )
            chats.onCleared()
        }

    /** A pre-rename snapshot still in flight cannot restore the old name in either surface. */
    @Test
    fun staleSubscriptionSnapshotCannotUndoTheRename() =
        runBlocking {
            val appState = testAppState()
            val chats = seededChats(appState)
            val conversation = conversationController(appState)
            assertTrue(conversation.updateGroupProfile(NEW_NAME, ""))

            chats.applyChatListRow(row(groupName = OLD_NAME))
            conversation.applyGroupStateForTest(group(name = OLD_NAME))
            flushChatListRecompute()

            assertEquals(NEW_NAME, conversation.group.name)
            assertEquals(
                NEW_NAME,
                chatListItemDisplayTitle(chats.items.single(), appState, GroupTitleCopy.Default),
            )
            chats.onCleared()
        }

    /** A genuinely newer authoritative name still replaces the local one. */
    @Test
    fun newerAuthoritativeRenameStillWins() =
        runBlocking {
            val appState = testAppState()
            val chats = seededChats(appState)
            val conversation = conversationController(appState)
            assertTrue(conversation.updateGroupProfile(NEW_NAME, ""))

            chats.applyChatListRow(row(groupName = REMOTE_NAME))
            conversation.applyGroupStateForTest(group(name = REMOTE_NAME))
            flushChatListRecompute()

            assertEquals(REMOTE_NAME, conversation.group.name)
            assertEquals(
                REMOTE_NAME,
                chatListItemDisplayTitle(chats.items.single(), appState, GroupTitleCopy.Default),
            )
            chats.onCleared()
        }

    /** A rejected commit keeps the old name everywhere and emits no local chat-list update. */
    @Test
    fun failedRenameLeavesThePriorName() =
        runBlocking {
            val appState = testAppState()
            val chats = seededChats(appState)
            val conversation = conversationController(appState) { _, _, _, _ -> error("rejected") }

            assertFalse(conversation.updateGroupProfile(NEW_NAME, ""))
            flushChatListRecompute()

            assertEquals(OLD_NAME, conversation.group.name)
            assertEquals(
                OLD_NAME,
                chatListItemDisplayTitle(chats.items.single(), appState, GroupTitleCopy.Default),
            )
            chats.onCleared()
        }

    /** The immediate patch is scoped to the account that renamed the group. */
    @Test
    fun renameDoesNotReachAnotherAccountsChatList() =
        runBlocking {
            val appState = testAppState()
            val otherAccountChats = seededChats(appState, accountRef = OTHER_ACCOUNT_REF)
            val conversation = conversationController(appState)

            assertTrue(conversation.updateGroupProfile(NEW_NAME, ""))
            flushChatListRecompute()

            assertEquals(
                OLD_NAME,
                chatListItemDisplayTitle(otherAccountChats.items.single(), appState, GroupTitleCopy.Default),
            )
            otherAccountChats.onCleared()
        }

    /** After a restart the durable name restores normally and no stale row is resurrected. */
    @Test
    fun coldStartRestoresTheCommittedName() =
        runBlocking {
            val appState = testAppState()
            val chats = seededChats(appState)
            val conversation = conversationController(appState)
            assertTrue(conversation.updateGroupProfile(NEW_NAME, ""))
            chats.onCleared()

            val restarted = seededChats(appState, groupName = NEW_NAME)
            assertEquals(
                NEW_NAME,
                chatListItemDisplayTitle(restarted.items.single(), appState, GroupTitleCopy.Default),
            )
            restarted.onCleared()
        }

    /** Runs the chat list's one-frame recompute debounce so its projection is current. */
    private fun flushChatListRecompute() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RECOMPUTE_FLUSH_MS))
    }

    /** A chat list bound to [accountRef], already showing this group under its old name. */
    private fun seededChats(
        appState: WhiteNoiseAppState,
        accountRef: String = ACCOUNT_REF,
        groupName: String = OLD_NAME,
    ): ChatsController {
        val controller =
            ChatsController(
                appState = appState,
                initialAccountRef = accountRef,
                memberSnapshotLoader = { _, _ -> emptyList() },
            )
        appState.attachChatsController(controller)
        controller.setChatListVisible(false)
        controller.applyChatListRow(row(groupName = groupName))
        controller.applyLocalGroupUpdate(group(name = groupName))
        controller.setChatListVisible(true)
        return controller
    }

    /** A conversation on this group whose profile commit is answered by [updater]. */
    private fun conversationController(
        appState: WhiteNoiseAppState,
        updater: suspend (String, String, String?, String?) -> Unit = { _, _, _, _ -> },
    ) = ConversationController(
        appState = appState,
        initialGroup = group(name = OLD_NAME),
        groupProfileUpdater = updater,
    )

    /** One signed-in account plus a second label the rename must not reach. */
    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    account(ACCOUNT_REF, ACCOUNT_ID),
                    account(OTHER_ACCOUNT_REF, OTHER_ACCOUNT_ID),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    /** A signed-in account summary. */
    private fun account(
        label: String,
        accountIdHex: String,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = accountIdHex,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    /** An authoritative chat-list row whose prepared title matches its group name. */
    private fun row(groupName: String) =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = GROUP_ID,
            archived = false,
            pendingConfirmation = false,
            title = groupName,
            groupName = groupName,
            avatarUrl = null,
            avatar = null,
            lastMessage = null,
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 1uL,
            activitySortAt = 1uL,
            updatedAt = 1uL,
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

    /** The group record behind the row. */
    private fun group(name: String) =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = true,
            endpoint = "wss://relay.example",
            name = name,
            description = "",
            admins = listOf(ACCOUNT_ID),
            relays = emptyList(),
            nostrGroupIdHex = "03".repeat(32),
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
                                baseUrl = "https://blossom.example",
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

    /** Drafts play no part here, so persistence stays in memory. */
    private class InMemoryDraftPersistence : DraftPersistence {
        private val values = mutableMapOf<String, String>()

        override fun read(): Map<String, String> = values.toMap()

        override fun write(
            key: String,
            value: String?,
        ) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private companion object {
        const val RECOMPUTE_FLUSH_MS = 64L
        const val ACCOUNT_REF = "personal"
        const val OTHER_ACCOUNT_REF = "work"
        const val OLD_NAME = "Weekend plans"
        const val NEW_NAME = "Saturday hike"
        const val REMOTE_NAME = "Renamed elsewhere"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val OTHER_ACCOUNT_ID = "05" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
    }
}
