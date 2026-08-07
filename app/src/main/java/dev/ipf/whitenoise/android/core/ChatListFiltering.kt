package dev.ipf.whitenoise.android.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.ChatListIdentifierSearch
import dev.ipf.whitenoise.android.core.ChatListMessageSearch
import dev.ipf.whitenoise.android.core.ClipboardPasteAffordance
import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.core.ENCRYPTED_BACKUP_MIN_PASSPHRASE_LENGTH
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.EncryptedBackupPassphraseStrength
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupSystemCopy
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.IdentityEntryInput
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.LeaveAction
import dev.ipf.whitenoise.android.core.Lud16Resolver
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageBodyMatch
import dev.ipf.whitenoise.android.core.MessageDebugCategory
import dev.ipf.whitenoise.android.core.MessageDebugClassifier
import dev.ipf.whitenoise.android.core.MessageDebugStyle
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageSearch
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.Nip05Resolver
import dev.ipf.whitenoise.android.core.ProfileFieldValidation
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.core.ProfilePseudonymGenerator
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.QrCodeEncoder
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.core.RecentEmojiList
import dev.ipf.whitenoise.android.core.RecipientReference
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.core.ReplySwipe
import dev.ipf.whitenoise.android.core.SafeHttpsGet
import dev.ipf.whitenoise.android.core.SnippetHighlight
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.core.TimelineRowKind
import dev.ipf.whitenoise.android.core.encryptedBackupPassphraseInputsValid
import dev.ipf.whitenoise.android.core.encryptedBackupPassphraseStrength
import dev.ipf.whitenoise.android.core.groupedEncryptedBackup
import dev.ipf.whitenoise.android.core.replyMediaKindFromMime
import dev.ipf.whitenoise.android.core.timelineRowKind
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.flow.filter
import java.util.Locale

internal fun localeInvariantFold(value: String): String = value.lowercase(Locale.ROOT)

/**
 * Deliberately narrower than `ChatListItem.removedFromGroup`, which answers
 * "is the row inert" for badge suppression. Only the engine's authoritative
 * `selfMembership` counts here, so a voluntary exit, an in-flight leave, a
 * disband, or a roster that hasn't loaded self yet never reads as an eviction —
 * an eviction is the one membership change the user gets no other signal for.
 */
internal fun chatListItemEvicted(item: ChatListItem): Boolean =
    item.group.selfMembership == SelfMembershipFfi.REMOVED ||
        item.projection?.selfMembership == SelfMembershipFfi.REMOVED

// At least as long as the truncated id prefix shown in group details, so a
// copied prefix matches while short hex-looking words ("cafe") stay plain text.
internal const val GROUP_ID_SEARCH_MIN_LENGTH = 8

internal fun looksLikeGroupIdNeedle(ciNeedle: String): Boolean =
    ciNeedle.length >= GROUP_ID_SEARCH_MIN_LENGTH && ciNeedle.all { it in '0'..'9' || it in 'a'..'f' }

internal fun applyChatListSearchAndFilter(
    source: List<ChatListItem>,
    rawQuery: String,
    appState: WhiteNoiseAppState,
    titleCopy: GroupTitleCopy,
    bodyMatchGroupIds: Set<String> = emptySet(),
    // Non-null when a folder chip is selected: the effective chat ids
    // (lowercased, manual plus rule matches) that folder contains. Every
    // folder — seeded defaults included — filters through this one path.
    folderChatIds: Set<String>? = null,
): List<ChatListItem> {
    val byFolder =
        if (folderChatIds == null) {
            source
        } else {
            source.filter { it.group.groupIdHex.lowercase() in folderChatIds }
        }
    val needle = rawQuery.trim()
    if (needle.isEmpty()) return byFolder
    val ciNeedle = localeInvariantFold(needle)
    return byFolder.filter { item ->
        // Match against the SAME title the user sees in the row, not the
        // raw group.name. For DMs and other unnamed chats, group.name is
        // blank and the visible title is projected from the other
        // member's profile — without this projection the search misses
        // direct messages by their displayed name.
        val title = localeInvariantFold(chatListItemDisplayTitle(item, appState, titleCopy))
        if (title.contains(ciNeedle)) return@filter true
        val preview = localeInvariantFold(item.projectedPreviewText())
        if (preview.contains(ciNeedle)) return@filter true
        // Group description matches (issue #388): descriptions hold the
        // context users put there to find a group later ("research workgroup",
        // "family planning"), so they should surface the row even when the
        // title and preview don't mention the needle. Same lowercase +
        // substring containment as title/preview.
        val description = localeInvariantFold(item.group.description)
        if (description.isNotEmpty() && description.contains(ciNeedle)) return@filter true
        // Group-id matches (issue #1509): both ids are shown as copyable
        // values on the group details screen, so pasting one back into search
        // must surface the group. Only a hex-shaped needle of plausible
        // length is compared against the ids — ordinary words must not
        // surface unrelated groups through their 64-char hex ids.
        if (looksLikeGroupIdNeedle(ciNeedle) &&
            (
                localeInvariantFold(item.group.groupIdHex).contains(ciNeedle) ||
                    localeInvariantFold(item.group.nostrGroupIdHex).contains(ciNeedle)
            )
        ) {
            return@filter true
        }
        // Message-body matches (issue #290): the async per-chat search
        // (ChatsController.searchMessageBodies) found the needle inside this
        // conversation's local timeline even though it isn't in the title or
        // preview line. The matched message id + highlighted snippet ride a
        // separate map keyed by group id; here we only need to know the chat
        // qualifies so it joins the result set. Body matches still pass
        // through the folder filter above, so a selected folder narrows
        // them the same way it narrows title/preview hits.
        item.group.groupIdHex in bodyMatchGroupIds
    }
}

/**
 * Display title shown for a chat-list row. Shared between `ChatRow` (the
 * visible label) and `applyChatListSearchAndFilter` (the searchable
 * label) so a typed query always matches what the user sees on screen.
 *
 * For NAMED groups (`group.name` non-blank) we honour whatever the
 * projection's title field carries — it's a localized rendering of the
 * group name and may differ from the raw `group.name`. Either way the
 * value is peer-supplied, so it renders via
 * [ChatListItem.sanitizedNamedTitle] (ProfileSanitizer.displayName:
 * strip bidi/zero-width spoofing chars, NFKC-fold, cap length — #980),
 * never the raw string; a name that sanitization strips entirely falls
 * through to the unnamed projection below.
 *
 * For UNNAMED groups we deliberately ignore `projectedTitle`: the
 * upstream projection emits the group id hex there when no name is set,
 * and using it would leak hex into the UI. Instead we route through
 * `GroupProjector.displayTitle`, which falls back to (in order)
 * inviter-welcomer copy for pending invites, the other member's title
 * for two-member groups, the "Group of N people" copy for ≥3-member
 * groups, and finally a short hex if no member data has resolved yet.
 * The local fallback then live-updates once `ChatsController` populates
 * the member cache from the `groupMembers` FFI.
 */
internal fun chatListItemDisplayTitle(
    item: ChatListItem,
    appState: WhiteNoiseAppState,
    copy: GroupTitleCopy,
): String {
    item.sanitizedNamedTitle?.let { return it }
    return GroupProjector.displayTitle(
        group = item.group,
        otherMemberAccount = item.presentationOtherMemberAccount,
        memberCount = item.presentationMemberCount,
        memberTitle = { appState.chatMemberTitle(it) },
        copy = copy,
        conversationKind = item.projection?.conversationKind,
        soleSelfMember = item.presentationActiveAccountIsSoleMember,
    )
}
