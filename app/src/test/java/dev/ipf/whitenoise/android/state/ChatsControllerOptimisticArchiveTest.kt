package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatsControllerOptimisticArchiveTest {
    /** A partial batch reveals the latest failed row without undoing a successful native commit. */
    @Test
    fun bulkArchiveMovesEveryRowBeforeIoAndRestoresOnlyTheFailedRow() =
        runBlocking {
            val releaseFirstCommit = CompletableDeferred<Unit>()
            val controller =
                ChatsController(
                    appState = testAppState(),
                    initialAccountRef = ACCOUNT_REF,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                    groupArchivedUpdater = { _, groupIdHex, archived ->
                        if (groupIdHex == GROUP_A) releaseFirstCommit.await()
                        if (groupIdHex == GROUP_B) error("rejected")
                        group(groupIdHex).copy(archived = archived)
                    },
                )
            seed(controller, GROUP_A, GROUP_B)

            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setArchived(listOf(GROUP_A, GROUP_B), archived = true, notify = false)
                }

            assertTrue("all selected rows should leave the active list before IO returns", controller.items.isEmpty())
            assertEquals(listOf(GROUP_A, GROUP_B), controller.archivedItems.map { it.id }.sorted())

            // A newer projection update arrives while the archive intent is
            // pending. B's failure must reveal this row rather than restoring
            // the stale pre-mutation snapshot.
            controller.applyChatListRow(row(GROUP_B).copy(hasUnread = true, unreadCount = 4uL, updatedAt = 2uL))
            releaseFirstCommit.complete(Unit)

            assertEquals(1, result.await())
            assertEquals(listOf(GROUP_A), controller.archivedItems.map { it.id })
            assertEquals(GROUP_B, controller.items.single().id)
            assertTrue(controller.items.single().hasUnread)
            assertEquals(4uL, controller.items.single().unreadCount)
            controller.onCleared()
        }

    /** A completion from the previous binding cannot retire the replacement account's overlay. */
    @Test
    @Suppress("LongMethod") // One lifecycle race must retain both suspended mutations in a single scenario.
    fun staleArchiveCompletionCannotCrossABindResetOrClearTheNewIntent() =
        runBlocking {
            val releaseOldCommit = CompletableDeferred<Unit>()
            val releaseNewCommit = CompletableDeferred<Unit>()
            val updateAccountRefs = mutableListOf<String>()
            var updateCount = 0
            val controller =
                ChatsController(
                    appState = testAppState(),
                    initialAccountRef = ACCOUNT_REF,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                    groupArchivedUpdater = { accountRef, groupIdHex, archived ->
                        updateAccountRefs += accountRef
                        when (++updateCount) {
                            1 -> {
                                releaseOldCommit.await()
                                group(groupIdHex, name = "old account result").copy(archived = archived)
                            }
                            else -> {
                                releaseNewCommit.await()
                                group(groupIdHex, name = "new account").copy(archived = archived)
                            }
                        }
                    },
                )
            seed(controller, GROUP_A)

            val oldArchive =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setArchived(listOf(GROUP_A), archived = true, notify = false)
                }
            assertEquals(GROUP_A, controller.archivedItems.single().id)

            // A real bind reset clears every projection and advances bindEpoch.
            // Restore only the injected test account reference so this isolated
            // controller can seed the next account snapshot without opening
            // Marmot's live subscriptions.
            controller.bind(null)
            restoreTestAccountReference(controller, NEW_ACCOUNT_REF)
            seed(controller, GROUP_A)
            controller.applyLocalGroupUpdate(group(GROUP_A, name = "new account"))

            val newArchive =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setArchived(listOf(GROUP_A), archived = true, notify = false)
                }
            assertEquals(listOf(ACCOUNT_REF, NEW_ACCOUNT_REF), updateAccountRefs)
            assertEquals(
                "new account",
                controller.archivedItems
                    .single()
                    .group.name,
            )

            releaseOldCommit.complete(Unit)
            assertEquals(0, oldArchive.await())
            assertEquals(GROUP_A, controller.archivedItems.single().id)
            assertEquals(
                "new account",
                controller.archivedItems
                    .single()
                    .group.name,
            )

            releaseNewCommit.complete(Unit)
            assertEquals(1, newArchive.await())
            assertEquals(listOf(ACCOUNT_REF, NEW_ACCOUNT_REF), updateAccountRefs)
            assertEquals(
                "new account",
                controller.archivedItems
                    .single()
                    .group.name,
            )
            controller.onCleared()
        }

    /** Cancelling a suspended batch restores uncommitted rows and never starts the next mutation. */
    @Test
    fun cancellationReleasesAllOverlaysAndKeepsNewerRowContent() =
        runBlocking {
            val startedGroups = mutableListOf<String>()
            val releaseCommit = CompletableDeferred<Unit>()
            val controller =
                ChatsController(
                    appState = testAppState(),
                    initialAccountRef = ACCOUNT_REF,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                    groupArchivedUpdater = { _, groupIdHex, archived ->
                        startedGroups += groupIdHex
                        releaseCommit.await()
                        group(groupIdHex).copy(archived = archived)
                    },
                )
            seed(controller, GROUP_A, GROUP_B)
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setArchived(listOf(GROUP_A, GROUP_B), archived = true, notify = false)
                }
            assertEquals(2, controller.archivedItems.size)
            controller.applyChatListRow(row(GROUP_B).copy(hasUnread = true, unreadCount = 3uL, updatedAt = 2uL))

            pending.cancelAndJoin()

            assertEquals(listOf(GROUP_A), startedGroups)
            assertTrue(controller.archivedItems.isEmpty())
            assertEquals(setOf(GROUP_A, GROUP_B), controller.items.map { it.id }.toSet())
            assertEquals(3uL, controller.items.single { it.id == GROUP_B }.unreadCount)
            controller.onCleared()
        }

    /** Opposite conversation actions share the batch owner's identity fence within the same binding. */
    @Test
    fun olderConversationCompletionDoesNotRetireNewerRestoreIntent() {
        val controller =
            ChatsController(
                appState = testAppState(),
                initialAccountRef = ACCOUNT_REF,
                memberSnapshotLoader = { _, _ -> emptyList() },
            )
        seed(controller, GROUP_A)
        val archive = requireNotNull(controller.beginConversationArchiveIntent(GROUP_A, archived = true))
        val restore = requireNotNull(controller.beginConversationArchiveIntent(GROUP_A, archived = false))

        controller.applyLocalGroupUpdate(group(GROUP_A).copy(archived = true))
        controller.finishConversationArchiveIntent(GROUP_A, archive)
        assertEquals(GROUP_A, controller.items.single().id)
        assertTrue(controller.archivedItems.isEmpty())

        // A failed restore reveals the committed archive, not the old pre-archive row.
        controller.finishConversationArchiveIntent(GROUP_A, restore)
        assertTrue(controller.items.isEmpty())
        assertEquals(GROUP_A, controller.archivedItems.single().id)
        controller.onCleared()
    }

    /** A cleared controller ignores an already-started native completion and remains empty. */
    @Test
    fun nativeCompletionAfterClearCannotRepopulateTheList() =
        runBlocking {
            val releaseCommit = CompletableDeferred<Unit>()
            val controller =
                ChatsController(
                    appState = testAppState(),
                    initialAccountRef = ACCOUNT_REF,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                    groupArchivedUpdater = { _, groupIdHex, archived ->
                        releaseCommit.await()
                        group(groupIdHex).copy(archived = archived)
                    },
                )
            seed(controller, GROUP_A)
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setArchived(GROUP_A, archived = true, notify = false)
                }
            controller.onCleared()
            releaseCommit.complete(Unit)

            assertEquals(false, pending.await())
            assertTrue(controller.items.isEmpty())
            assertTrue(controller.archivedItems.isEmpty())
        }

    /** Seeds the real controller projection without starting profile enrichment for each row. */
    private fun seed(
        controller: ChatsController,
        vararg groupIds: String,
    ) {
        controller.setChatListVisible(false)
        groupIds.forEach { groupId ->
            controller.applyChatListRow(row(groupId))
            controller.applyLocalGroupUpdate(group(groupId))
        }
        controller.setChatListVisible(true)
    }

    /** Supplies an in-memory account shell while archive I/O stays injected by each scenario. */
    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = "a".repeat(64),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    /** Builds a stable native row whose unread fields can advance while an archive is suspended. */
    private fun row(groupIdHex: String) =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = groupIdHex,
            archived = false,
            pendingConfirmation = false,
            title = groupIdHex,
            groupName = "",
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
            conversationKind = ChatConversationKindFfi.UNKNOWN,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )

    /** Builds the authoritative group returned by the injected archive adapter. */
    private fun group(
        groupIdHex: String,
        name: String = "",
    ) = AppGroupRecordFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        groupIdHex = groupIdHex,
        protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
        endpoint = "endpoint-$groupIdHex",
        name = name,
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "nostr-$groupIdHex",
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

    /** Rebinds the isolated fixture after exercising the controller's real reset path. */
    private fun restoreTestAccountReference(
        controller: ChatsController,
        accountRef: String,
    ) {
        ChatsController::class.java.getDeclaredField("accountRef").apply {
            isAccessible = true
            set(controller, accountRef)
        }
    }

    private class InMemoryDraftPersistence : DraftPersistence {
        /** Keeps archive scenarios independent of stored composer drafts. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards writes because no archive scenario changes a composer draft. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "account"
        const val NEW_ACCOUNT_REF = "new-account"
        const val GROUP_A = "group-a"
        const val GROUP_B = "group-b"
    }
}
