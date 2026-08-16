package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatListItem
import java.util.Locale

/**
 * Effective membership of one folder against the loaded chat list: the
 * manually added chat ids plus every chat the folder's rule currently
 * matches. Purely derived — nothing is persisted, so membership follows the
 * live list: a chat joins the folder the moment a rule starts matching it
 * and drops out the moment it stops.
 *
 * Rule semantics:
 *   - member criterion (non-empty [ChatFolderRule.includeMemberPubkeys]) —
 *     the chat contains at least one of the listed pubkeys,
 *   - keyword criterion ([ChatFolderRule.keyword]) — the chat's visible
 *     title or its group description contains the keyword, with the same
 *     case-insensitive fold + substring containment the chat-list search
 *     applies; [displayTitle] must supply the same title the row renders,
 *   - the two criteria are additive: matching either puts the chat in,
 *   - [ChatFolderRule.unreadOnly] constrains matches to chats with unread
 *     messages; [ChatFolderRule.groupsOnly] constrains matches to group
 *     (non-DM) chats. With no member or keyword criterion these stand alone
 *     as pure category rules — the Unread and Groups defaults are exactly
 *     that,
 *   - [ChatFolderRule.archivedOnly] selects which side of the archive split
 *     the rule matches: an archived-only folder matches only archived chats
 *     (the Archived default is a pure archived rule), every other folder
 *     only active ones. Callers still choose which source list to render,
 *     but the match itself follows the row's own archived flag,
 *   - [ChatFolderRule.includeMuted] false (the default) drops muted chats
 *     from rule matches; manual members are the user's explicit choice and
 *     are never filtered here.
 */
internal fun chatFolderChatIds(
    items: List<ChatListItem>,
    manualChatIds: Set<String>,
    rule: ChatFolderRule?,
    activeAccountIdHex: String?,
    isMuted: (groupIdHex: String) -> Boolean,
    displayTitle: (ChatListItem) -> String,
): Set<String> {
    if (rule == null) return manualChatIds
    val criteria =
        FolderRuleCriteria(
            rule = rule,
            memberHexes = rule.includeMemberPubkeys.mapTo(HashSet()) { it.lowercase(Locale.ROOT) },
            ciKeyword =
                rule.keyword
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::localeInvariantFold),
        )
    return manualChatIds +
        items
            .filter { chatFolderRuleMatches(criteria, it, activeAccountIdHex, isMuted, displayTitle) }
            .map { it.group.groupIdHex.lowercase(Locale.ROOT) }
}

// The rule with its match inputs pre-normalized once per evaluation pass.
private data class FolderRuleCriteria(
    val rule: ChatFolderRule,
    val memberHexes: Set<String>,
    val ciKeyword: String?,
)

private fun chatFolderRuleMatches(
    criteria: FolderRuleCriteria,
    item: ChatListItem,
    activeAccountIdHex: String?,
    isMuted: (groupIdHex: String) -> Boolean,
    displayTitle: (ChatListItem) -> String,
): Boolean {
    // With no member or keyword criterion the rule matches every chat only
    // when a category constraint narrows it (or archived-only already picked
    // the source list) — an empty rule must not swallow the list.
    val rule = criteria.rule
    val base =
        if (criteria.memberHexes.isEmpty() && criteria.ciKeyword == null) {
            rule.unreadOnly || rule.groupsOnly || rule.archivedOnly
        } else {
            chatHasAnyMember(item, criteria.memberHexes) ||
                chatMatchesKeyword(item, criteria.ciKeyword, displayTitle)
        }
    return base &&
        rule.archivedOnly == item.group.archived &&
        (!rule.unreadOnly || item.effectiveHasUnread(activeAccountIdHex)) &&
        (!rule.groupsOnly || !item.isDm()) &&
        (rule.includeMuted || !isMuted(item.group.groupIdHex))
}

// Matches against the roster snapshot the chat-list row already carries. A
// DM's roster often holds only the active account (the counterpart is not an
// enumerable member), so the resolved counterpart is matched too; a row whose
// snapshot ChatsController hasn't loaded yet matches by its presentation-only
// counterpart until the roster cache lands and the list recomputes. That
// fallback affects only this local organizational rule; it never populates an
// authoritative roster or enables membership-sensitive group actions.
private fun chatHasAnyMember(
    item: ChatListItem,
    wantedMemberHexes: Set<String>,
): Boolean {
    val counterpart =
        (item.otherMemberAccount ?: item.presentationOtherMemberAccount)
            ?.lowercase(Locale.ROOT)
    val roster = item.memberSnapshot?.members.orEmpty()
    return (counterpart != null && counterpart in wantedMemberHexes) ||
        roster.any { it.memberIdHex.lowercase(Locale.ROOT) in wantedMemberHexes }
}

// Same fold + substring containment the chat-list search applies to titles
// and descriptions, so a folder keyword matches exactly what search would.
private fun chatMatchesKeyword(
    item: ChatListItem,
    ciKeyword: String?,
    displayTitle: (ChatListItem) -> String,
): Boolean =
    ciKeyword != null &&
        (
            localeInvariantFold(displayTitle(item)).contains(ciKeyword) ||
                localeInvariantFold(item.group.description).contains(ciKeyword)
        )
