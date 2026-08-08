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
        override fun read(): Map<String, String> = emptyMap()

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
