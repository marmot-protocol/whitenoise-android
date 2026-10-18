package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Conversation-surface archive/restore must acknowledge the accepted action
 * before the engine commit starts or waits behind group commit I/O, settle
 * success into the returned authoritative group, and clear only the matching
 * presentation intent on failure or cancellation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationArchiveOptimisticAcceptanceTest {
    @Test
    fun archivePresentsOptimisticallyBeforeCommitIoThenSettlesToTheReturnedGroup() =
        runBlocking {
            val releaseCommit = CompletableDeferred<Unit>()
            val controller =
                conversationController { _, groupIdHex, archived ->
                    releaseCommit.await()
                    group().copy(groupIdHex = groupIdHex, archived = archived)
                }

            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setArchived(true)
                }

            assertTrue("the accepted action must present before commit I/O returns", controller.presentedArchived)
            assertFalse("the authoritative group stays untouched while pending", controller.group.archived)

            releaseCommit.complete(Unit)

            assertTrue(result.await())
            assertTrue(controller.presentedArchived)
            assertTrue("success settles into the returned authoritative group", controller.group.archived)
        }

    @Test
    fun restorePresentsOptimisticallyAndSettles() =
        runBlocking {
            val releaseCommit = CompletableDeferred<Unit>()
            val controller =
                conversationController(initialGroup = group(archived = true)) { _, groupIdHex, archived ->
                    releaseCommit.await()
                    group().copy(groupIdHex = groupIdHex, archived = archived)
                }

            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setArchived(false)
                }

            assertFalse("the accepted restore must present before commit I/O returns", controller.presentedArchived)
            assertTrue(controller.group.archived)

            releaseCommit.complete(Unit)

            assertTrue(result.await())
            assertFalse(controller.presentedArchived)
            assertFalse(controller.group.archived)
        }

    @Test
    fun failureClearsOnlyTheIntentAndRevealsTheAuthoritativeStateWithFeedback() =
        runBlocking {
            val releaseCommit = CompletableDeferred<Unit>()
            val controller =
                conversationController { _, _, _ ->
                    releaseCommit.await()
                    throw MarmotKitException.Publish("relay rejected event")
                }

            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setArchived(true)
                }
            assertTrue(controller.presentedArchived)

            releaseCommit.complete(Unit)

            assertFalse(result.await())
            assertFalse(
                "failure reveals the authoritative state instead of a captured snapshot",
                controller.presentedArchived,
            )
            assertFalse(controller.group.archived)
            assertNotNull("cause-accurate feedback must be recorded", controller.lastMutationError)
        }

    @Test
    fun aNewerAuthoritativeSubscriptionUpdateWinsOverALateSuccess() =
        runBlocking {
            val releaseCommit = CompletableDeferred<Unit>()
            val controller =
                conversationController { _, groupIdHex, archived ->
                    releaseCommit.await()
                    // The late completion returns a pre-rename snapshot.
                    group().copy(groupIdHex = groupIdHex, archived = archived, name = "stale name")
                }

            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setArchived(true)
                }
            assertTrue(controller.presentedArchived)

            // A newer authoritative subscription update lands while the commit
            // is still suspended in engine I/O.
            controller.applyGroupStateForTest(group().copy(name = "renamed while committing"))

            releaseCommit.complete(Unit)
            assertTrue(result.await())

            assertEquals(
                "the newer authoritative record must survive the late success",
                "renamed while committing",
                controller.group.name,
            )
            assertFalse(
                "the late completion's snapshot must not replace newer authority",
                controller.group.archived,
            )
        }

    @Test
    fun cancellationClearsTheIntentWithoutRecordingAFailure() =
        runBlocking {
            val releaseCommit = CompletableDeferred<Unit>()
            val controller =
                conversationController { _, _, _ ->
                    releaseCommit.await()
                    group()
                }

            val action =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setArchived(true)
                }
            assertTrue(controller.presentedArchived)

            action.cancel()
            action.join()

            assertFalse("a cancelled action must not leave its presentation behind", controller.presentedArchived)
            assertNull(controller.lastMutationError)
        }

    @Test
    fun repeatedTapsAreBoundedToOneLogicalMutation() =
        runBlocking {
            val releaseCommit = CompletableDeferred<Unit>()
            var commits = 0
            val controller =
                conversationController { _, groupIdHex, archived ->
                    commits += 1
                    releaseCommit.await()
                    group().copy(groupIdHex = groupIdHex, archived = archived)
                }

            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setArchived(true)
                }
            // A second tap while the first is in flight toggles off the
            // presented state, so it targets restore — and must be dropped.
            val second = controller.setArchived(!controller.presentedArchived)

            releaseCommit.complete(Unit)

            assertTrue(first.await())
            assertFalse("in-flight taps are dropped, not queued as duplicates", second)
            assertEquals(1, commits)
        }

    @Test
    fun archiveThenRestoreSettleSequentiallyWithoutConflictingOverlays() =
        runBlocking {
            val controller =
                conversationController { _, groupIdHex, archived ->
                    group().copy(groupIdHex = groupIdHex, archived = archived)
                }

            assertTrue(controller.setArchived(true))
            assertTrue(controller.presentedArchived)
            assertTrue(controller.group.archived)

            assertTrue(controller.setArchived(false))
            assertFalse(controller.presentedArchived)
            assertFalse(controller.group.archived)
        }

    @Test
    fun boundChatListRowMovesWithTheConversationActionAndFailureRevealsTheNewestRow() =
        runBlocking {
            val appState = testAppState()
            val releaseCommit = CompletableDeferred<Unit>()
            val chats =
                ChatsController(
                    appState = appState,
                    initialAccountRef = ACCOUNT_REF,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                )
            appState.attachChatsController(chats)
            seed(chats)
            val controller =
                conversationController(appState = appState) { _, _, _ ->
                    releaseCommit.await()
                    throw MarmotKitException.Publish("relay rejected event")
                }

            val action =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setArchived(true)
                }

            assertEquals(
                "the chat-list row must move in the same accepted frame",
                listOf(GROUP_ID),
                chats.archivedItems.map { it.id },
            )
            assertTrue(chats.items.isEmpty())

            // A newer authoritative projection lands while the commit is
            // pending; the failed action must reveal it, not a snapshot.
            chats.applyChatListRow(row().copy(hasUnread = true, unreadCount = 3uL, updatedAt = 2uL))
            releaseCommit.complete(Unit)

            assertFalse(action.await())
            assertEquals(GROUP_ID, chats.items.single().id)
            assertTrue(chats.items.single().hasUnread)
            assertEquals(3uL, chats.items.single().unreadCount)
            chats.onCleared()
        }

    /** Disposal fences only the old group field; accepted archive results still settle the list and feedback. */
    @Test
    fun disposedArchiveSettlesTheCapturedAccountListAndKeepsCommandAcceptance() =
        runBlocking {
            val state = testAppState()
            val chats =
                ChatsController(
                    appState = state,
                    initialAccountRef = ACCOUNT_REF,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                )
            state.attachChatsController(chats)
            seed(chats)
            val releaseCommit = CompletableDeferred<Unit>()
            var commits = 0
            val controller =
                conversationController(appState = state) { _, _, archived ->
                    commits += 1
                    releaseCommit.await()
                    group(archived).copy(name = "completed archive")
                }
            val action = async(start = CoroutineStart.UNDISPATCHED) { controller.setArchived(true) }
            try {
                assertTrue(controller.presentedArchived)
                controller.onCleared()
                releaseCommit.complete(Unit)
                assertTrue("native commitment still succeeds", action.await())
                assertEquals(1, commits)
                assertFalse("the retired controller's field stays fenced", controller.group.archived)
                assertEquals("Archive group", controller.group.name)
                assertTrue("finishing the overlay must not resurrect the main-list row", chats.items.isEmpty())
                assertEquals(GROUP_ID, chats.archivedItems.single().id)
                assertEquals(AppText.Resource(R.string.toast_chat_archived), state.transientNotice?.title)
                assertTrue(state.transientNotice!!.isForConversation(ACCOUNT_REF, GROUP_ID))
                assertTrue("screen disposal must not add a new command rejection", controller.setArchived(false))
                assertEquals(2, commits)
                assertEquals(GROUP_ID, chats.items.single().id)
                assertTrue(chats.archivedItems.isEmpty())
                assertEquals(AppText.Resource(R.string.toast_chat_restored), state.transientNotice?.title)
            } finally {
                releaseCommit.complete(Unit)
                controller.onCleared()
                chats.onCleared()
            }
        }

    /** An archive refusal still reports the accepted command's failure after its screen leaves. */
    @Test
    fun disposedArchiveFailureKeepsItsExistingRecoveryFeedback() =
        runBlocking {
            val state = testAppState()
            val releaseCommit = CompletableDeferred<Unit>()
            val controller =
                conversationController(appState = state) { _, _, _ ->
                    releaseCommit.await()
                    throw MarmotKitException.Publish("refused archive")
                }
            val action = async(start = CoroutineStart.UNDISPATCHED) { controller.setArchived(true) }
            try {
                controller.onCleared()
                releaseCommit.complete(Unit)
                assertFalse(action.await())
                assertNotNull(controller.lastMutationError)
                assertEquals(AppText.Resource(R.string.toast_couldnt_update_chat), state.toast?.title)
                assertTrue(state.toast?.diagnosticReport?.contains("GROUP_ARCHIVE_UPDATE") == true)
                assertFalse(controller.mutationInFlight)
                assertFalse(controller.presentedArchived)
            } finally {
                releaseCommit.complete(Unit)
                controller.onCleared()
            }
        }

    /** A notification-pinned controller cannot publish an archive record into another account's attached list. */
    @Test
    fun archiveCompletionRoutesItsLocalRecordOnlyToThePinnedAccount() =
        runBlocking {
            val state = testAppState()
            val chats =
                ChatsController(
                    appState = state,
                    initialAccountRef = "other-profile",
                    memberSnapshotLoader = { _, _ -> emptyList() },
                )
            state.attachChatsController(chats)
            seed(chats)
            val controller =
                conversationController(appState = state) { _, _, archived -> group(archived) }
            try {
                assertEquals(listOf(GROUP_ID), chats.items.map { it.id })
                assertTrue(controller.setArchived(true))
                assertTrue(controller.group.archived)
                assertEquals(listOf(GROUP_ID), chats.items.map { it.id })
                assertTrue(chats.archivedItems.isEmpty())
            } finally {
                controller.onCleared()
                chats.onCleared()
            }
        }

    private fun conversationController(
        appState: WhiteNoiseAppState = testAppState(),
        initialGroup: AppGroupRecordFfi = group(),
        groupArchivedUpdater: suspend (String, String, Boolean) -> AppGroupRecordFfi,
    ) = ConversationController(
        appState = appState,
        initialGroup = initialGroup,
        initialMemberSnapshot = memberSnapshot(),
        groupArchivedUpdater = groupArchivedUpdater,
    )

    private fun seed(controller: ChatsController) {
        controller.setChatListVisible(false)
        controller.applyChatListRow(row())
        controller.applyLocalGroupUpdate(group())
        controller.setChatListVisible(true)
    }

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(archiveTestDraftPersistence()),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun memberSnapshot() =
        GroupMemberSnapshot(
            listOf(
                AppGroupMemberRecordFfi(
                    memberIdHex = ACCOUNT_ID,
                    account = ACCOUNT_REF,
                    local = true,
                ),
            ),
        )

    private fun group(archived: Boolean = false) =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Archive group",
            description = "",
            admins = listOf(ACCOUNT_ID),
            relays = listOf("wss://relay.example"),
            nostrGroupIdHex = "04".repeat(32),
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
            disappearingMessageSecs = 0uL,
            archived = archived,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    /** Returns an active row with explicit capabilities for optimistic archive assertions. */
    private fun row() =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = GROUP_ID,
            archived = false,
            pendingConfirmation = false,
            title = "Archive group",
            groupName = "Archive group",
            avatarUrl = null,
            avatar = null,
            lastMessage =
                ChatListMessagePreviewFfi(
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    messageIdHex = "d4".repeat(32),
                    sender = ACCOUNT_ID,
                    senderDisplayName = null,
                    plaintext = "before archive",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    timelineAt = 10uL,
                    deleted = false,
                    deletionSource = DeletionSourceFfi.UNKNOWN,
                    attachmentKind = null,
                    attachmentCount = 0u,
                    groupSystem = null,
                    deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                ),
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 0uL,
            activitySortAt = 10uL,
            updatedAt = 10uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            manuallyMarkedUnread = false,
            conversationKind = ChatConversationKindFfi.UNKNOWN,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "a1".repeat(32)
        val GROUP_ID = "b2".repeat(32)
    }
}

private fun archiveTestDraftPersistence(): DraftPersistence =
    object : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
