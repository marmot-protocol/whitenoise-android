package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Regression for issue #1697: a freshly composed [ChatsController] must not
 * present an authoritative empty chat list before [ChatsController.bind] has
 * completed the first local snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatsControllerInitialLoadStateTest {
    @Test
    fun newlyConstructedController_isNotAuthoritativelyEmptyBeforeBind() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val accountRef = ACCOUNT_REF
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore(InMemoryDraftPersistence()),
                accountIdHexResolver = { null },
                accounts = listOf(activeAccount(accountRef)),
                activeAccountRef = accountRef,
            )
        val controller = ChatsController(appState)

        assertTrue(
            "A fresh controller must report loading until the first local snapshot",
            controller.isLoading,
        )
        assertFalse(
            "An empty, non-loading controller paints EmptyChats before bind runs",
            controller.items.isEmpty() &&
                controller.archivedItems.isEmpty() &&
                !controller.isLoading &&
                controller.error == null,
        )
    }

    /** Construction publishes the one-shot MDK seed without awaiting bind or background enrichment. */
    @Test
    fun seededLocalFirstFrameIsPublishedBeforeBindWithoutStartingMemberLoads() {
        var memberLoads = 0
        val row = seededRow()
        val presentation = seededPresentation()
        val controller = seededController(row, presentation) { memberLoads += 1 }
        try {
            assertTrue(controller.hasLoadedLocalSnapshot)
            assertFalse(controller.isLoading)
            assertNull(controller.error)
            assertEquals(ACCOUNT_REF, controller.boundAccountRef)
            assertEquals(row, controller.items.single().projection)
            assertEquals(presentation, controller.items.single().selectedPresentation)
            assertEquals(0, memberLoads)
            assertEquals(controller.items.single(), controller.chatItemForGroup(row.groupIdHex))
        } finally {
            controller.onCleared()
        }
    }

    /** A queued old-binding publication must not run after the reset has published its empty frame. */
    @Test
    fun unbindRetiresQueuedSeedUpdatesBeforeTheyCanPublishAgain() {
        val row = seededRow()
        val controller = seededController(row, seededPresentation()) {}
        try {
            controller.applyChatListRow(row.copy(hasUnread = true, unreadCount = 3uL, updatedAt = row.updatedAt + 1uL))
            assertEquals(3uL, controller.chatItemForGroup(row.groupIdHex)?.projection?.unreadCount)
            runBlocking { controller.bind(null) }
            assertTrue(controller.items.isEmpty())
            assertTrue(controller.archivedItems.isEmpty())
            assertNull(controller.accountRef)
            assertNull(controller.boundAccountRef)
            assertNull(controller.chatItemForGroup(row.groupIdHex))
            val resetPublication = controller.forwardTargetsRevision
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals(resetPublication, controller.forwardTargetsRevision)
            assertTrue(controller.items.isEmpty())
            assertFalse(controller.isLoading)
        } finally {
            controller.onCleared()
        }
    }

    private fun seededController(
        row: ChatListRowFfi,
        presentation: ConversationPresentationFfi,
        onMemberLoad: () -> Unit,
    ): ChatsController =
        ChatsController(
            appState =
                WhiteNoiseAppState(
                    context = ApplicationProvider.getApplicationContext(),
                    draftStore = DraftStore(InMemoryDraftPersistence()),
                    accountIdHexResolver = { null },
                    accounts = listOf(activeAccount(ACCOUNT_REF)),
                    activeAccountRef = ACCOUNT_REF,
                ),
            initialAccountRef = ACCOUNT_REF,
            initialLocalSnapshot =
                AccountSwitchLocalSnapshot(
                    accountRef = ACCOUNT_REF,
                    activeAccountIdHex = ACCOUNT_HEX,
                    rows = listOf(row),
                    groups = emptyList(),
                    memberIds = emptyList(),
                    profiles = emptyList(),
                    presentedRows =
                        listOf(
                            PresentedChatRowFfi(
                                draftVersion = null,
                                preview = emptyChatRowPreview(),
                                actions = noChatRowActions(),
                                row = row,
                                presentation = presentation,
                                avatarAsset = null,
                            ),
                        ),
                ),
            memberSnapshotLoader = { _, _ ->
                onMemberLoad()
                emptyList()
            },
        )

    private fun seededRow(): ChatListRowFfi =
        notificationChatListRow().copy(
            title = "Seeded conversation",
            groupName = "Seeded conversation",
            lastMessage = null,
            hasUnread = false,
            unreadCount = 0uL,
        )

    private fun seededPresentation() =
        ConversationPresentationFfi(
            title = PresentationTextFfi.Literal("Seeded conversation"),
            avatar = SelectedAvatarFfi.Placeholder("seeded-group", PresentationSourceFfi.GROUP_FALLBACK),
            titleSource = PresentationSourceFfi.GROUP_FALLBACK,
            avatarSource = PresentationSourceFfi.GROUP_FALLBACK,
            peerId = null,
            resolution = PresentationResolutionFfi.FALLBACK,
        )

    private fun activeAccount(label: String) =
        AccountSummaryFfi(
            label = label,
            accountIdHex = ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
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
