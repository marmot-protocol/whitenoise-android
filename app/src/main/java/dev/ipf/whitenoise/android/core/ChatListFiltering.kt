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

internal fun canonicalChatListGroupId(groupIdHex: String): String = localeInvariantFold(groupIdHex.trim())

internal data class ChatListSearchCandidate<T>(
    val value: T,
    val groupIdHex: String,
    val nostrGroupIdHex: String,
    val displayTitle: String,
    val previewText: String,
    val description: String,
)

internal data class ChatListSearchSections<T>(
    val groups: List<T>,
    val messages: List<T>,
) {
    fun orderedItems(): List<T> = groups + messages
}

private enum class ChatListSynchronousSearchMatch {
    TITLE,
    METADATA,
    MESSAGE,
    NONE,
}

private val ChatListSynchronousSearchMatch.priority: Int
    get() =
        when (this) {
            ChatListSynchronousSearchMatch.TITLE -> 3
            ChatListSynchronousSearchMatch.METADATA -> 2
            ChatListSynchronousSearchMatch.MESSAGE -> 1
            ChatListSynchronousSearchMatch.NONE -> 0
        }

@Suppress("ReturnCount") // Ordered guard clauses avoid computing lower-priority matches after a hit.
private fun <T> ChatListSearchCandidate<T>.synchronousMatch(ciNeedle: String): ChatListSynchronousSearchMatch {
    if (localeInvariantFold(displayTitle).contains(ciNeedle)) return ChatListSynchronousSearchMatch.TITLE
    if (localeInvariantFold(previewText).contains(ciNeedle)) return ChatListSynchronousSearchMatch.METADATA
    if (localeInvariantFold(description).contains(ciNeedle)) return ChatListSynchronousSearchMatch.METADATA
    if (
        looksLikeGroupIdNeedle(ciNeedle) &&
        (
            canonicalChatListGroupId(groupIdHex).contains(ciNeedle) ||
                canonicalChatListGroupId(nostrGroupIdHex).contains(ciNeedle)
        )
    ) {
        return ChatListSynchronousSearchMatch.METADATA
    }
    return ChatListSynchronousSearchMatch.NONE
}

internal fun <T> projectChatListSearchCandidates(
    candidates: List<ChatListSearchCandidate<T>>,
    rawQuery: String,
    bodyMatchGroupIds: Set<String> = emptySet(),
    folderChatIds: Set<String>? = null,
): ChatListSearchSections<T> {
    val ciNeedle = localeInvariantFold(rawQuery.trim())
    val canonicalFolderIds = folderChatIds?.mapTo(mutableSetOf(), ::canonicalChatListGroupId)
    val canonicalBodyIds = bodyMatchGroupIds.mapTo(mutableSetOf(), ::canonicalChatListGroupId)
    val classifiedById = linkedMapOf<String, Pair<T, ChatListSynchronousSearchMatch>>()

    candidates.forEach { candidate ->
        val canonicalId = canonicalChatListGroupId(candidate.groupIdHex)
        if (canonicalFolderIds != null && canonicalId !in canonicalFolderIds) return@forEach
        val synchronousMatch =
            if (ciNeedle.isEmpty()) ChatListSynchronousSearchMatch.METADATA else candidate.synchronousMatch(ciNeedle)
        val match =
            if (synchronousMatch == ChatListSynchronousSearchMatch.NONE && canonicalId in canonicalBodyIds) {
                ChatListSynchronousSearchMatch.MESSAGE
            } else {
                synchronousMatch
            }
        val previous = classifiedById[canonicalId]
        if (previous == null || match.priority > previous.second.priority) {
            classifiedById[canonicalId] = candidate.value to match
        }
    }
    return ChatListSearchSections(
        groups =
            classifiedById.values
                .filter { it.second == ChatListSynchronousSearchMatch.TITLE }
                .map(Pair<T, ChatListSynchronousSearchMatch>::first) +
                classifiedById.values
                    .filter { it.second == ChatListSynchronousSearchMatch.METADATA }
                    .map(Pair<T, ChatListSynchronousSearchMatch>::first),
        messages =
            classifiedById.values
                .filter { it.second == ChatListSynchronousSearchMatch.MESSAGE }
                .map(Pair<T, ChatListSynchronousSearchMatch>::first),
    )
}

internal fun projectChatListSearchSections(
    source: List<ChatListItem>,
    rawQuery: String,
    appState: WhiteNoiseAppState,
    titleCopy: GroupTitleCopy,
    bodyMatchGroupIds: Set<String> = emptySet(),
    folderChatIds: Set<String>? = null,
): ChatListSearchSections<ChatListItem> {
    if (rawQuery.trim().isEmpty()) {
        val canonicalFolderIds = folderChatIds?.mapTo(mutableSetOf(), ::canonicalChatListGroupId)
        val folderItems =
            if (canonicalFolderIds == null) {
                source
            } else {
                source.filter { canonicalChatListGroupId(it.group.groupIdHex) in canonicalFolderIds }
            }
        return ChatListSearchSections(groups = folderItems, messages = emptyList())
    }

    return projectChatListSearchCandidates(
        candidates =
            source.map { item ->
                ChatListSearchCandidate(
                    value = item,
                    groupIdHex = item.group.groupIdHex,
                    nostrGroupIdHex = item.group.nostrGroupIdHex,
                    displayTitle = chatListItemDisplayTitle(item, appState, titleCopy),
                    previewText = item.projectedPreviewText(),
                    description = item.group.description,
                )
            },
        rawQuery = rawQuery,
        bodyMatchGroupIds = bodyMatchGroupIds,
        folderChatIds = folderChatIds,
    )
}

internal fun canonicalChatListBodyMatches(matches: Map<String, MessageBodyMatch>): Map<String, MessageBodyMatch> =
    buildMap {
        matches.forEach { (groupId, match) ->
            putIfAbsent(canonicalChatListGroupId(groupId), match)
        }
    }

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
): List<ChatListItem> =
    projectChatListSearchSections(
        source = source,
        rawQuery = rawQuery,
        appState = appState,
        titleCopy = titleCopy,
        bodyMatchGroupIds = bodyMatchGroupIds,
        folderChatIds = folderChatIds,
    ).orderedItems()

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
