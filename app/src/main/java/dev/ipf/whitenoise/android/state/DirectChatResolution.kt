package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageDirectChatResolution
import dev.ipf.whitenoise.android.ui.chats.newchat.existingDirectChatFromProvenance
import dev.ipf.whitenoise.android.ui.chats.newchat.rankedDirectChatCandidates
import dev.ipf.whitenoise.android.ui.chats.newchat.resolveExistingDirectChatCandidates

/**
 * Resolves the DM to reopen for [targetReference] before a new one is created (#825, #1701). Candidates
 * are the rows the bounded chat-list windows retain, completed by MDK's account-wide
 * `existingDirectConversation` read, so a DM that fell outside the retained rows is reopened instead of
 * duplicated.
 */
internal suspend fun ChatsController.resolveExistingDirectChat(
    targetReference: String,
    excludingGroupIdHex: String? = null,
): NewMessageDirectChatResolution {
    val unavailable = NewMessageDirectChatResolution(item = null, createRequired = false)
    val account = accountRef ?: return unavailable
    val epoch = bindEpoch
    val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
    val retained =
        chatRows
            .asSequence()
            .filterNot { it.pendingConfirmation }
            .map { projectChatRow(it) }
            .toList()
    val accountWide = accountWideDirectChat(account, targetReference, retained)?.let { projectChatRow(it.row) }
    val candidateGroupIds =
        rankedDirectChatCandidates(retained + listOfNotNull(accountWide), excludingGroupIdHex).map(ChatListItem::id)
    return resolveExistingDirectChatCandidates(candidateGroupIds) { groupIdHex ->
        resolveDirectChatGroup(
            account = account,
            epoch = epoch,
            activeAccountIdHex = activeAccountIdHex,
            groupIdHex = groupIdHex,
            targetReference = targetReference,
            chatItemForGroup = { id ->
                chatItemForGroup(id) ?: accountWide?.takeIf { it.id.equals(id, ignoreCase = true) }
            },
        )
    }
}

/**
 * MDK's account-wide DM with [targetReference] when the windows do not retain it; null when it is already
 * among [retained], unknown to MDK, not reusable, or when the read fails (the retained rows still decide).
 */
private suspend fun ChatsController.accountWideDirectChat(
    account: String,
    targetReference: String,
    retained: List<ChatListItem>,
): PresentedChatRowFfi? =
    appState
        .accountIdHexForMention(targetReference)
        ?.let { peer ->
            runCatchingCancellable { appState.marmotIo { existingDirectConversation(account, peer) } }.getOrNull()
        }?.takeIf { existing ->
            existing.reusable && retained.none { it.id.equals(existing.groupIdHex, ignoreCase = true) }
        }?.let { existing ->
            runCatchingCancellable { appState.marmotIo { presentedChatListRow(account, existing.groupIdHex) } }
                .getOrNull()
        }

/** Revalidates one candidate against its current row and an authoritative local group-details read. */
internal suspend fun ChatsController.resolveDirectChatGroup(
    account: String,
    epoch: Long,
    activeAccountIdHex: String?,
    groupIdHex: String?,
    targetReference: String,
    chatItemForGroup: (String) -> ChatListItem?,
): NewMessageDirectChatResolution {
    val normalizedTarget = targetReference.trim()
    return existingDirectChatFromProvenance(
        provenanceGroupIdHex = groupIdHex,
        targetReference = targetReference,
        activeAccountIdHex = activeAccountIdHex,
        equivalentTarget = { other -> appState.npub(other).equals(normalizedTarget, ignoreCase = true) },
        chatItemForGroup = chatItemForGroup,
        authoritativeGroupDetails = { currentGroupIdHex ->
            runCatchingCancellable { appState.marmotIo { groupDetails(account, currentGroupIdHex) } }
                .getOrNull()
                ?.let(::applyAuthoritativeGroupDetails)
        },
        accountStillBound = { accountRef == account && isActiveBindEpoch(epoch) },
    )
}
