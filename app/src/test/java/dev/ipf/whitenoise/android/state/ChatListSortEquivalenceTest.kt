package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

/**
 * Pins the chat-list order against a reference implementation written the way
 * the comparator used to be: keys read per comparison, straight off the row.
 *
 * `ChatListSortingTest` covers the intended orderings case by case. This
 * covers the property those cases cannot — that precomputing the keys did not
 * change the answer for *any* combination of pinned state, pending
 * confirmation, draft timestamp, zero recency, and arrival sequence. Those
 * interact: a draft-supplied recency and a zero recency both drop arrival
 * order, and only rows that tie on everything above reach the title key.
 */
class ChatListSortEquivalenceTest {
    @Test
    fun precomputedKeysAgreeWithPerComparisonKeysAcrossRandomFixtures() {
        val random = Random(SEED)
        repeat(FIXTURES) { fixture ->
            val rows = randomRows(random, ROWS_PER_FIXTURE)
            val drafts = randomDrafts(random, rows)
            val draftedAt: (ChatListItem) -> ULong? = { drafts[it.id] }

            val actual = sortChatListItems(rows, draftedAt).map { it.id }
            val expected = referenceSort(rows, draftedAt).map { it.id }

            assertEquals("fixture $fixture disagreed", expected, actual)
        }
    }

    @Test
    fun theFixturesActuallyReachEveryTieBreak() {
        // Guards the test above: a fixture set that never produces a draft, a
        // zero recency, or a pinned row would pass vacuously.
        val random = Random(SEED)
        var drafted = 0
        var zeroRecency = 0
        var pinned = 0
        var pending = 0
        repeat(FIXTURES) {
            val rows = randomRows(random, ROWS_PER_FIXTURE)
            val drafts = randomDrafts(random, rows)
            drafted += drafts.size
            zeroRecency += rows.count { (it.latestAt ?: 0uL) == 0uL }
            pinned += rows.count { it.pinned() }
            pending += rows.count { it.group.pendingConfirmation }
        }
        assertEquals("no drafted rows in the corpus", true, drafted > 0)
        assertEquals("no zero-recency rows in the corpus", true, zeroRecency > 0)
        assertEquals("no pinned rows in the corpus", true, pinned > 0)
        assertEquals("no pending-confirmation rows in the corpus", true, pending > 0)
    }

    /** The comparator as it read before the keys were precomputed. */
    private fun referenceSort(
        items: List<ChatListItem>,
        draftedAtSeconds: (ChatListItem) -> ULong?,
    ): List<ChatListItem> {
        val draftedAtById = items.associate { it.id to draftedAtSeconds(it) }
        val tiesWithDraft =
            items
                .filter { referenceComesFromDraft(it, draftedAtById[it.id]) }
                .mapTo(mutableSetOf()) { referenceTie(it, draftedAtById[it.id]) }
        return items.sortedWith(
            compareByDescending<ChatListItem> { it.group.pendingConfirmation }
                .thenByDescending { it.pinned() }
                .thenBy { it.pinnedPosition()?.toLong() ?: Long.MAX_VALUE }
                .thenByDescending { chatListItemDraftSortAt(it.latestAt, draftedAtById[it.id]) }
                .thenByDescending { item ->
                    val tie = referenceTie(item, draftedAtById[item.id])
                    if (tie.recency == 0uL || tie in tiesWithDraft) 0uL else item.activitySequence
                }.thenBy { chatListItemSortKey(it) },
        )
    }

    private data class ReferenceTie(
        val pendingConfirmation: Boolean,
        val pinned: Boolean,
        val pinnedPosition: UInt?,
        val recency: ULong,
    )

    private fun referenceTie(
        item: ChatListItem,
        draftedAt: ULong?,
    ) = ReferenceTie(
        pendingConfirmation = item.group.pendingConfirmation,
        pinned = item.pinned(),
        pinnedPosition = item.pinnedPosition(),
        recency = chatListItemDraftSortAt(item.latestAt, draftedAt),
    )

    private fun referenceComesFromDraft(
        item: ChatListItem,
        draftedAt: ULong?,
    ): Boolean = draftedAt != null && draftedAt > (item.latestAt ?: 0uL)

    private fun randomRows(
        random: Random,
        count: Int,
    ): List<ChatListItem> =
        (0 until count).map { index ->
            // A narrow timestamp range on purpose: whole-second MDK timestamps
            // tie often, which is what pushes rows into the later keys.
            val at = if (random.nextInt(6) == 0) 0uL else (1_700_000_000L + random.nextInt(4)).toULong()
            val pinned = random.nextInt(4) == 0
            item(
                index = index,
                activityAt = at,
                pinned = pinned,
                pinnedPosition = if (pinned) random.nextInt(3).toUInt() else null,
                pendingConfirmation = random.nextInt(8) == 0,
                activitySequence = random.nextInt(3).toULong(),
            )
        }

    private fun randomDrafts(
        random: Random,
        rows: List<ChatListItem>,
    ): Map<String, ULong> =
        rows
            .filter { random.nextInt(3) == 0 }
            .associate { it.id to (1_700_000_000L + random.nextInt(6)).toULong() }

    private fun item(
        index: Int,
        activityAt: ULong,
        pinned: Boolean,
        pinnedPosition: UInt?,
        pendingConfirmation: Boolean,
        activitySequence: ULong,
    ): ChatListItem {
        val id = String.format("%064x", index)
        return ChatListItem(
            group = group(id, pendingConfirmation),
            latest = null,
            otherMemberAccount = null,
            memberCount = 2,
            memberSnapshot = null,
            projection = row(id, activityAt, pinned, pinnedPosition, pendingConfirmation),
            activitySequence = activitySequence,
        )
    }

    private fun row(
        groupIdHex: String,
        at: ULong,
        pinned: Boolean,
        pinnedPosition: UInt?,
        pendingConfirmation: Boolean,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupIdHex,
        archived = false,
        pendingConfirmation = pendingConfirmation,
        title = "Conversation $groupIdHex",
        groupName = "Conversation $groupIdHex",
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 0uL,
        activitySortAt = at,
        updatedAt = at,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = ChatConversationKindFfi.GROUP,
        muted = false,
        mutedUntilMs = null,
        pinned = pinned,
        pinnedPosition = pinnedPosition,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )

    private fun group(
        id: String,
        pendingConfirmation: Boolean,
    ) = AppGroupRecordFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        groupIdHex = id,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
        endpoint = "endpoint-$id",
        name = "Conversation $id",
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
        pendingConfirmation = pendingConfirmation,
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

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net")),
        )

    private companion object {
        const val SEED = 0x43_4841_5453_4f52L // "CHATSOR"
        const val FIXTURES = 300
        const val ROWS_PER_FIXTURE = 40
    }
}
