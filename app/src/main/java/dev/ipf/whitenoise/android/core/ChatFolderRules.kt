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
 *   - [ChatFolderRule.unreadOnly] constrains matches to chats with unread
 *     messages (the same [ChatListItem.hasUnread] the Unread system folder
 *     filters by); with no member criterion it stands alone as a pure
 *     unread rule,
 *   - [ChatFolderRule.includeMuted] false (the default) drops muted chats
 *     from rule matches; manual members are the user's explicit choice and
 *     are never filtered here.
 */
internal fun chatFolderChatIds(
    items: List<ChatListItem>,
    manualChatIds: Set<String>,
    rule: ChatFolderRule?,
    isMuted: (groupIdHex: String) -> Boolean,
): Set<String> {
    if (rule == null) return manualChatIds
    val wantedMemberHexes = rule.includeMemberPubkeys.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    return manualChatIds +
        items
            .filter { chatFolderRuleMatches(rule, wantedMemberHexes, it, isMuted) }
            .map { it.group.groupIdHex.lowercase(Locale.ROOT) }
}

private fun chatFolderRuleMatches(
    rule: ChatFolderRule,
    wantedMemberHexes: Set<String>,
    item: ChatListItem,
    isMuted: (groupIdHex: String) -> Boolean,
): Boolean {
    // With no member criterion the rule matches every chat only when
    // unreadOnly narrows it — an empty rule must not swallow the whole list.
    val base =
        if (wantedMemberHexes.isEmpty()) {
            rule.unreadOnly
        } else {
            chatHasAnyMember(item, wantedMemberHexes)
        }
    return base &&
        (!rule.unreadOnly || item.hasUnread) &&
        (rule.includeMuted || !isMuted(item.group.groupIdHex))
}

// Matches against the roster snapshot the chat-list row already carries. A
// DM's roster often holds only the active account (the counterpart is not an
// enumerable member), so the resolved counterpart is matched too; a row whose
// snapshot ChatsController hasn't loaded yet matches by counterpart only
// until the roster cache lands and the list recomputes.
private fun chatHasAnyMember(
    item: ChatListItem,
    wantedMemberHexes: Set<String>,
): Boolean {
    val counterpart = item.otherMemberAccount?.lowercase(Locale.ROOT)
    val roster = item.memberSnapshot?.members.orEmpty()
    return (counterpart != null && counterpart in wantedMemberHexes) ||
        roster.any { it.memberIdHex.lowercase(Locale.ROOT) in wantedMemberHexes }
}
