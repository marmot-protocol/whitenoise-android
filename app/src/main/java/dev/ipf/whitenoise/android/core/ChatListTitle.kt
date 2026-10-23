package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/**
 * Display title shown for a chat-list row. Shared between `ChatRow` (the
 * visible label) and `applyChatListSearchAndFilter` (the searchable
 * label) so a typed query always matches what the user sees on screen.
 *
 * A direct message with a resolved peer prefers the active account's
 * private nickname, matching the conversation header. Without one, MDK's
 * selected title remains the first choice. Other conversations never read
 * a member's nickname as their group title.
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
 * groups, and finally localized Unknown copy if no member data has resolved yet.
 * The local fallback then live-updates once `ChatsController` populates
 * the member cache from the `groupMembers` FFI.
 */
internal fun chatListItemDisplayTitle(
    item: ChatListItem,
    appState: WhiteNoiseAppState,
    copy: GroupTitleCopy,
): String = chatListItemTitle(item, appState::contactNickname, appState::chatMemberTitle, copy)

/** Resolves a DM's account-scoped nickname before MDK's public title, as the header does. */
internal fun chatListItemTitle(
    item: ChatListItem,
    contactNickname: (String) -> String?,
    memberTitle: (String) -> String,
    copy: GroupTitleCopy,
): String =
    conversationHeaderTitle(
        dmPeerAccountIdHex = item.presentationOtherMemberAccount.takeIf { item.isDm() },
        contactNickname = contactNickname,
        preparedTitle = selectedChatPresentationTitle(item.selectedPresentation, copy) ?: item.sanitizedNamedTitle,
    ) {
        GroupProjector.displayTitle(
            group = item.group,
            otherMemberAccount = item.presentationOtherMemberAccount,
            memberCount = item.presentationMemberCount,
            memberTitle = memberTitle,
            copy = copy,
            conversationKind = item.projection?.conversationKind,
            soleSelfMember = item.presentationActiveAccountIsSoleMember,
        )
    }

/** Localizes and sanitizes MDK's typed selected title without re-resolving identity. */
internal fun selectedChatPresentationTitle(
    presentation: ConversationPresentationFfi?,
    copy: GroupTitleCopy,
): String? =
    when (val selected = presentation?.title) {
        is PresentationTextFfi.Literal -> ProfileSanitizer.displayName(selected.text)
        is PresentationTextFfi.UnnamedGroup -> copy.unnamedGroupTitle
        PresentationTextFfi.UnavailableConversation -> copy.unavailableConversationTitle
        null -> null
    }
