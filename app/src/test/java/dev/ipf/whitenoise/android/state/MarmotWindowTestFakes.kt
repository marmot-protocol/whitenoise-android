package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountAttentionSnapshotFfi
import dev.ipf.marmotkit.AccountAttentionSubscription
import dev.ipf.marmotkit.BlockListSnapshotFfi
import dev.ipf.marmotkit.BlockListSubscription
import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.ChatListWindowSubscription
import dev.ipf.marmotkit.ConversationAnchorKindFfi
import dev.ipf.marmotkit.ConversationAnchorOutcomeFfi
import dev.ipf.marmotkit.ConversationCapabilitiesFfi
import dev.ipf.marmotkit.ConversationHeaderFfi
import dev.ipf.marmotkit.ConversationOpenReadStateFfi
import dev.ipf.marmotkit.ConversationParticipationFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.ConversationWindowRevisionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.NoPointer
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi

/**
 * Native-handle stubs for the MarmotKit 0.10.0 subscriptions that runtime fixtures must answer: the
 * per-view chat-list windows, the account attention summary and the block list. They are allocated
 * without the UniFFI constructor so JVM tests never touch the native library.
 */
internal object MarmotWindowTestFakes {
    /** A chat-list window whose initial replacement holds [rows] and whose stream ends after [next] runs dry. */
    fun chatListWindow(
        view: ChatListViewFfi,
        rows: List<ChatListRowFfi>,
        onSnapshot: () -> Unit = {},
        next: suspend () -> ChatListWindowSnapshotFfi? = { null },
    ): ChatListWindowSubscription =
        nativeStub(ScriptedChatListWindow::class.java).apply {
            this.view = view
            this.rows = rows
            this.onSnapshot = onSnapshot
            this.nextReplacement = next
        }

    /** Wraps rows the way MDK's presented projection does, with literal titles and placeholder avatars. */
    fun windowSnapshot(
        view: ChatListViewFfi,
        rows: List<ChatListRowFfi>,
        sequence: ULong = 0uL,
        generation: String = "test-window",
    ): ChatListWindowSnapshotFfi =
        ChatListWindowSnapshotFfi(
            subscriptionGeneration = generation,
            sequence = sequence,
            view = view,
            rows = rows.map(::presentedRow),
            hasMoreBefore = false,
            hasMoreAfter = false,
            anchor = ChatListAnchorOutcomeFfi.Top,
        )

    /** An attention summary with no accounts that ends immediately. */
    fun accountAttention(): AccountAttentionSubscription = nativeStub(EmptyAccountAttention::class.java)

    /** A block list with nobody blocked that ends immediately. */
    fun blockList(): BlockListSubscription = nativeStub(EmptyBlockList::class.java)

    /** A conversation window sidecar whose prepared header carries [title]; everything else is empty and stable. */
    fun conversationFrame(title: String): ConversationWindowFrame =
        ConversationWindowFrame(
            revision = ConversationWindowRevisionFfi("test", 1uL),
            header =
                ConversationHeaderFfi(
                    selected =
                        ConversationPresentationFfi(
                            title = PresentationTextFfi.Literal(title),
                            avatar = SelectedAvatarFfi.Placeholder("group", PresentationSourceFfi.GROUP_FALLBACK),
                            titleSource = PresentationSourceFfi.GROUP_FALLBACK,
                            avatarSource = PresentationSourceFfi.GROUP_FALLBACK,
                            peerId = null,
                            resolution = PresentationResolutionFfi.FALLBACK,
                        ),
                    memberCount = 2uL,
                    archived = false,
                    epoch = 1uL,
                    lifecycle = GroupLifecycleStateFfi.STABLE,
                    disbanding = false,
                    unrecoverable = false,
                    capabilities =
                        ConversationCapabilitiesFfi(
                            participation = ConversationParticipationFfi.ACTIVE,
                            isSelfAdmin = false,
                            isLastAdmin = false,
                            canSend = true,
                            canInvite = false,
                            canEditGroup = false,
                            canLeave = true,
                            requiresSelfDemoteBeforeLeave = false,
                            canEnableDisbanding = false,
                            canDisband = false,
                        ),
                ),
            identities = emptyMap(),
            readState =
                ConversationOpenReadStateFfi(
                    initialized = true,
                    lastReadMessageIdHex = null,
                    lastReadTimelineAt = null,
                    manuallyMarkedUnread = false,
                    unreadCount = 0uL,
                    unreadMentionCount = 0uL,
                    firstUnreadMessageIdHex = null,
                ),
            draft = SelectedMessageDraftFfi(nativeStub(MessageDraftRevisionFfi::class.java), null),
            anchor = ConversationAnchorOutcomeFfi(ConversationAnchorKindFfi.LATEST, 0u),
            references = emptyMap(),
        )

    /** The chat-list rows an account's fixture serves: only the active view carries them. */
    fun rowsForView(
        view: ChatListViewFfi,
        rows: List<ChatListRowFfi>,
    ): List<ChatListRowFfi> = if (view == ChatListViewFfi.CHATS) rows else emptyList()

    private fun presentedRow(row: ChatListRowFfi) =
        PresentedChatRowFfi(
            row = row,
            presentation =
                ConversationPresentationFfi(
                    title = PresentationTextFfi.Literal(row.title.ifBlank { "Chat" }),
                    avatar = SelectedAvatarFfi.Placeholder(row.groupIdHex, PresentationSourceFfi.GROUP_FALLBACK),
                    titleSource = PresentationSourceFfi.GROUP_FALLBACK,
                    avatarSource = PresentationSourceFfi.GROUP_FALLBACK,
                    peerId = null,
                    resolution = PresentationResolutionFfi.FALLBACK,
                ),
        )

    private fun <T> nativeStub(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        @Suppress("UNCHECKED_CAST")
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private class ScriptedChatListWindow : ChatListWindowSubscription(NoPointer) {
        lateinit var view: ChatListViewFfi
        lateinit var rows: List<ChatListRowFfi>
        lateinit var onSnapshot: () -> Unit
        lateinit var nextReplacement: suspend () -> ChatListWindowSnapshotFfi?

        override fun snapshot(): ChatListWindowSnapshotFfi {
            onSnapshot()
            return windowSnapshot(view, rows)
        }

        override suspend fun next(): ChatListWindowSnapshotFfi? = nextReplacement()

        override suspend fun page(
            sequence: ULong,
            direction: ChatListPageDirectionFfi,
            count: UInt,
        ): ChatListWindowSnapshotFfi = windowSnapshot(view, rows, sequence)

        override suspend fun setVisibleAnchor(
            sequence: ULong,
            groupIdHex: String,
        ): ChatListWindowSnapshotFfi = windowSnapshot(view, rows, sequence)

        override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi {
            val snapshot = windowSnapshot(view, rows, sequence)
            return snapshot
        }

        override fun close() = Unit
    }

    private class EmptyAccountAttention : AccountAttentionSubscription(NoPointer) {
        override fun snapshot(): AccountAttentionSnapshotFfi? = null

        override suspend fun next(): AccountAttentionSnapshotFfi? = null

        override fun close() = Unit
    }

    private class EmptyBlockList : BlockListSubscription(NoPointer) {
        override fun snapshot(): BlockListSnapshotFfi? = null

        override suspend fun next(): BlockListSnapshotFfi? = null

        override fun close() = Unit
    }
}
