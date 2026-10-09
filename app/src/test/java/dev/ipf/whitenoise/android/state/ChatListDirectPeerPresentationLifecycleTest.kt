package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatListItemTitle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #2149: MDK's stored direct-peer presentation is display-only continuity. It names an unnamed direct row
 * on the first frame, stays out of every membership field, follows each newer authoritative frame, and is
 * dropped on peer change, Direct to Group reclassification and unbind (account switch, deletion, reset).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatListDirectPeerPresentationLifecycleTest {
    private val controllers = mutableListOf<ChatsController>()

    /** Releases every controller a test created. */
    @After
    fun tearDown() {
        controllers.forEach(ChatsController::onCleared)
    }

    /** The stored peer names the first frame while every membership field stays unresolved. */
    @Test
    fun storedPeerPresentationNamesTheFirstFrameWithoutMembershipAuthority() {
        val item = firstFrame(presented(directRow(), peer(PEER_A, PEER_A_NAME))).items.single()

        assertEquals(PEER_A_NAME, title(item))
        assertEquals(PEER_A, item.presentationOtherMemberAccount)
        assertNull(item.memberSnapshot)
        assertNull(item.otherMemberAccount)
        assertEquals(0, item.memberCount)
        assertFalse("display-only presentation cannot establish removal", item.removed)
    }

    /** A newer frame for another peer replaces, never merges with, the previous peer's presentation. */
    @Test
    fun peerChangeReplacesThePreviousPeersPresentation() {
        val controller = firstFrame(presented(directRow(), peer(PEER_A, PEER_A_NAME)))

        controller.replacePresented(presented(directRow(updatedAt = 3uL), peer(PEER_B, PEER_B_NAME)))

        val item = controller.items.single()
        assertEquals(PEER_B_NAME, title(item))
        assertEquals(PEER_B, item.presentationOtherMemberAccount)
        assertNull(item.otherMemberAccount)
    }

    /** Reclassifying the row as a group drops the direct peer's title and identity. */
    @Test
    fun directToGroupTransitionDropsTheDirectPeer() {
        val controller = firstFrame(presented(directRow(), peer(PEER_A, PEER_A_NAME)))

        controller.replacePresented(
            presented(
                directRow(updatedAt = 3uL).copy(conversationKind = ChatConversationKindFfi.GROUP),
                unnamedGroup(),
            ),
        )

        val item = controller.items.single()
        assertEquals(COPY.unnamedGroupTitle, title(item))
        assertNull(item.presentationOtherMemberAccount)
        assertEquals(GROUP_MEMBERS, item.presentationMemberCount)
    }

    /** A presentation-only update renames the row without moving it in activity order. */
    @Test
    fun authoritativeUpdateRenamesInPlaceWithoutReordering() {
        val newer = directRow(group = GROUP_B, activity = 20uL)
        val controller =
            firstFrame(
                presented(directRow(activity = 30uL), peer(PEER_A, PEER_A_NAME)),
                presented(newer, peer(PEER_B, PEER_B_NAME)),
            )
        assertEquals(listOf(GROUP_A, GROUP_B), controller.items.map(ChatListItem::id))

        controller.replacePresented(
            presented(directRow(activity = 30uL, updatedAt = 3uL), peer(PEER_A, RENAMED)),
            presented(newer, peer(PEER_B, PEER_B_NAME)),
        )

        assertEquals(listOf(GROUP_A, GROUP_B), controller.items.map(ChatListItem::id))
        assertEquals(listOf(RENAMED, PEER_B_NAME), controller.items.map(::title))
    }

    /** Unbinding, the path account switch, account deletion and reset all take, clears stored presentation. */
    @Test
    fun unbindClearsStoredPresentation() {
        val controller = firstFrame(presented(directRow(), peer(PEER_A, PEER_A_NAME)))

        runBlocking { controller.bind(null) }

        assertTrue(controller.items.isEmpty())
        assertTrue(controller.selectedPresentations().isEmpty())
    }

    /** Builds a controller whose first frame is the given MDK snapshot, with roster hydration never resolving. */
    private fun firstFrame(vararg rows: PresentedChatRowFfi): ChatsController =
        ChatsController(
            appState = appState(),
            initialAccountRef = ACCOUNT,
            memberSnapshotLoader = { _, _ -> awaitCancellation() },
            initialLocalSnapshot =
                AccountSwitchLocalSnapshot(
                    accountRef = ACCOUNT,
                    activeAccountIdHex = ACCOUNT_HEX,
                    rows = rows.map(PresentedChatRowFfi::row),
                    groups = emptyList(),
                    memberIds = emptyList(),
                    profiles = emptyList(),
                    presentedRows = rows.toList(),
                ),
        ).also(controllers::add)

    /** Installs a newer coherent window frame the way a window replacement does, then republishes. */
    private fun ChatsController.replacePresented(vararg rows: PresentedChatRowFfi) {
        ChatsController::class.java
            .getDeclaredMethod("replacePresentedChatRows", List::class.java)
            .apply { isAccessible = true }
            .invoke(this, rows.toList())
        ChatsController::class.java
            .getDeclaredMethod("recompute", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(this, false)
    }

    /** Reads the controller's private per-row presentation map. */
    @Suppress("UNCHECKED_CAST")
    private fun ChatsController.selectedPresentations(): Map<String, ConversationPresentationFfi> =
        ChatsController::class.java
            .getDeclaredField("selectedPresentationsByGroup")
            .apply { isAccessible = true }
            .get(this) as Map<String, ConversationPresentationFfi>

    /** The visible row title, resolved without any profile or roster lookup. */
    private fun title(item: ChatListItem): String = chatListItemTitle(item, { null }, { UNKNOWN }, COPY)

    /** Pairs a row with its MDK presentation and no stored avatar. */
    private fun presented(
        row: ChatListRowFfi,
        presentation: ConversationPresentationFfi,
    ) = PresentedChatRowFfi(
        draftVersion = null,
        preview = emptyChatRowPreview(),
        actions = noChatRowActions(),
        row = row,
        presentation = presentation,
        avatarAsset = null,
    )

    /** An unnamed direct row as MDK stores it. */
    private fun directRow(
        group: String = GROUP_A,
        activity: ULong = 2uL,
        updatedAt: ULong = 2uL,
    ): ChatListRowFfi =
        notificationChatListRow().copy(
            groupIdHex = group,
            title = "",
            groupName = "",
            conversationKind = ChatConversationKindFfi.DIRECT,
            activitySortAt = activity,
            updatedAt = updatedAt,
            lastMessage = null,
        )

    /** MDK's cached peer presentation for a direct row. */
    private fun peer(
        id: String,
        name: String,
    ) = ConversationPresentationFfi(
        title = PresentationTextFfi.Literal(name),
        avatar = SelectedAvatarFfi.Placeholder(id, PresentationSourceFfi.PEER_FALLBACK),
        titleSource = PresentationSourceFfi.PEER_PROFILE,
        avatarSource = PresentationSourceFfi.PEER_FALLBACK,
        peerId = id,
        resolution = PresentationResolutionFfi.CACHED,
    )

    /** MDK's presentation once the row is an unnamed multi-member group. */
    private fun unnamedGroup() =
        ConversationPresentationFfi(
            title = PresentationTextFfi.UnnamedGroup(GROUP_MEMBERS.toULong()),
            avatar = SelectedAvatarFfi.Placeholder(GROUP_A, PresentationSourceFfi.GROUP_FALLBACK),
            titleSource = PresentationSourceFfi.GROUP_FALLBACK,
            avatarSource = PresentationSourceFfi.GROUP_FALLBACK,
            peerId = null,
            resolution = PresentationResolutionFfi.FALLBACK,
        )

    /** A signed-in app state without a native runtime. */
    private fun appState() =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(NoDrafts),
            accountIdHexResolver = { ACCOUNT_HEX },
            accounts = listOf(AccountSummaryFfi(ACCOUNT, ACCOUNT_HEX, true, false, false, true)),
            activeAccountRef = ACCOUNT,
        )

    /** Drafts are irrelevant to row presentation. */
    private object NoDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT = "owner"
        val ACCOUNT_HEX = "00".repeat(32)
        val GROUP_A = "a1".repeat(16)
        val GROUP_B = "b2".repeat(16)
        val PEER_A = "aa".repeat(32)
        val PEER_B = "bb".repeat(32)
        const val PEER_A_NAME = "Peer A"
        const val PEER_B_NAME = "Peer B"
        const val RENAMED = "Peer A Renamed"
        const val UNKNOWN = "Unknown"
        const val GROUP_MEMBERS = 3
        val COPY =
            GroupTitleCopy(
                inviteFromFormat = "Invite from %1\$s",
                groupOfPeopleFormat = "Group of %1\$d people",
                unknownTitle = UNKNOWN,
            )
    }
}
