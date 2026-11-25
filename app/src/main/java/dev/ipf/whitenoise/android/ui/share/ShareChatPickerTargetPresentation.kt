package dev.ipf.whitenoise.android.ui.share

import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.localeInvariantFold
import dev.ipf.whitenoise.android.state.ChatListItem

/** One share target with its resolved title and the folded values search matches against. */
internal data class ShareChatPickerTargetPresentation(
    val item: ChatListItem,
    val title: String,
    val foldedHumanSearchValues: List<String>,
    val foldedIdentitySearchValues: List<String>,
)

/** Resolves a target's visible title and its human and identity search values from the owner's aliases. */
internal fun shareTargetPresentation(
    item: ChatListItem,
    accountAliases: Map<String, ShareAccountAliases>,
    groupTitleCopy: GroupTitleCopy,
): ShareChatPickerTargetPresentation {
    val projectedTitle =
        item.sanitizedNamedTitle ?: GroupProjector.displayTitle(
            group = item.group,
            otherMemberAccount = item.presentationOtherMemberAccount,
            memberCount = item.presentationMemberCount,
            memberTitle = { accountIdHex ->
                accountAliases[accountIdHex]?.displayName ?: groupTitleCopy.unknownTitle
            },
            copy = groupTitleCopy,
            conversationKind = item.projection?.conversationKind,
            soleSelfMember = item.presentationActiveAccountIsSoleMember,
            selfAccountIdHex = item.ownerAccountIdHex,
        )
    val title =
        when {
            item.sanitizedNamedTitle != null || projectedTitle != groupTitleCopy.unknownTitle -> projectedTitle
            accountAliases.isNotEmpty() -> accountAliases.values.first().displayName
            else -> "${groupTitleCopy.unknownTitle} · ${IdentityFormatter.short(item.group.groupIdHex)}"
        }
    val searchableTitle =
        title.takeIf {
            item.sanitizedNamedTitle != null ||
                title == groupTitleCopy.groupOfPeople(item.presentationMemberCount) ||
                accountAliases.values.any { aliases -> title in aliases.human }
        }
    val humanSearchValues =
        (listOfNotNull(searchableTitle) + accountAliases.values.flatMap { it.human })
            .mapNotNull(ProfileSanitizer::displayName)
            .distinct()
    return ShareChatPickerTargetPresentation(
        item = item,
        title = title,
        foldedHumanSearchValues = humanSearchValues.map(::localeInvariantFold),
        foldedIdentitySearchValues =
            (listOf(item.group.groupIdHex) + accountAliases.values.flatMap { it.identity })
                .map(::localeInvariantFold)
                .distinct(),
    )
}

/** The other people a direct target names, excluding the sending account, so their profiles can load. */
internal fun shareTargetAccountIds(
    item: ChatListItem,
    selectedAccountIdHex: String?,
): List<String> =
    buildList {
        item.presentationOtherMemberAccount?.let(::add)
        val directTarget =
            item.presentationOtherMemberAccount != null ||
                item.projection?.conversationKind == ChatConversationKindFfi.DIRECT
        if (directTarget) {
            item.memberSnapshot
                ?.members
                .orEmpty()
                .forEach { add(it.memberIdHex) }
            item.group.welcomerAccountIdHex?.let(::add)
            item.latest?.sender?.let(::add)
        }
    }.filter { it.isNotBlank() && !it.equals(selectedAccountIdHex, ignoreCase = true) }.distinct()
