package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.ChatListItem
import java.util.Locale

internal fun deriveRecipientCandidates(
    chatListItems: List<ChatListItem>,
    activeAccountIdHex: String?,
    displayName: (String) -> String,
    npub: (String) -> String,
): List<RecipientSearch.Candidate> {
    val accumulator = RecipientCandidateAccumulator(activeAccountIdHex)
    chatListItems
        .sortedByDescending { it.latestAt ?: 0uL }
        .forEach(accumulator::absorb)
    return accumulator.toCandidates(displayName, npub)
}

private class RecipientCandidateAccumulator(
    private val active: String?,
) {
    private val activeNormalized = active?.trim()?.lowercase(Locale.ROOT)
    private val order = LinkedHashSet<String>()
    private val inDm = HashSet<String>()
    private val inDmGroupIdByHex = HashMap<String, String>()
    private val groupIdsByHex = HashMap<String, MutableSet<String>>()

    fun absorb(item: ChatListItem) {
        val dm = item.isDm()
        val dmGroupIdHex = item.id.takeIf { dm }
        val groupId = item.id.takeUnless { dm }
        // Group rosters give the members. A DM's roster, though, often holds only
        // the active account — the counterpart isn't an enumerable member — so
        // also take the resolved DM counterpart and the latest message's sender
        // (the recent-sender source) to surface DM partners.
        item.memberSnapshot?.members?.forEach { member ->
            note(member.memberIdHex, dm = dm, dmGroupIdHex = dmGroupIdHex, groupId = groupId)
        }
        note(item.otherMemberAccount, dm = dm, dmGroupIdHex = dmGroupIdHex, groupId = groupId)
        note(item.latest?.sender, dm = dm, dmGroupIdHex = dmGroupIdHex, groupId = groupId)
        note(item.group.welcomerAccountIdHex, dm = dm, dmGroupIdHex = dmGroupIdHex, groupId = groupId)
    }

    fun toCandidates(
        displayName: (String) -> String,
        npub: (String) -> String,
    ): List<RecipientSearch.Candidate> =
        order.map { hex ->
            val source =
                when {
                    hex in inDm -> RecipientSearch.Source.InDm
                    else -> RecipientSearch.Source.InGroups(groupIdsByHex[hex]?.size ?: 0)
                }
            RecipientSearch.Candidate(
                accountIdHex = hex,
                displayName = displayName(hex),
                npub = npub(hex),
                source = source,
                existingDmGroupIdHex = inDmGroupIdByHex[hex]?.takeIf { hex in inDm },
            )
        }

    private fun note(
        rawHex: String?,
        dm: Boolean,
        dmGroupIdHex: String?,
        groupId: String?,
    ) {
        val hex = rawHex?.trim()?.lowercase(Locale.ROOT)
        if (hex.isNullOrEmpty() || hex == activeNormalized) return
        order.add(hex)
        if (dm) {
            inDm.add(hex)
            if (dmGroupIdHex != null) {
                inDmGroupIdByHex.putIfAbsent(hex, dmGroupIdHex)
            }
        }
        if (groupId != null) {
            groupIdsByHex.getOrPut(hex) { LinkedHashSet() }.add(groupId)
        }
    }
}
