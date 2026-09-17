package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ExistingDirectConversationFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageDirectChatResolution
import dev.ipf.whitenoise.android.ui.chats.newchat.existingDirectChatFromProvenance
import dev.ipf.whitenoise.android.ui.chats.newchat.rankedDirectChatCandidates
import dev.ipf.whitenoise.android.ui.chats.newchat.resolveExistingDirectChatCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

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
    val lookup = accountWideDirectChat(account, targetReference, retained)
    val accountWide = (lookup as? DirectLookup.Row)?.let { projectChatRow(it.row.row) }
    val candidateGroupIds =
        rankedDirectChatCandidates(retained + listOfNotNull(accountWide), excludingGroupIdHex).map(ChatListItem::id)
    val resolution =
        resolveExistingDirectChatCandidates(candidateGroupIds) { groupIdHex ->
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
    // MDK could not say whether a DM exists (peer index still backfilling, or the read timed out): refuse
    // to create one rather than risk a duplicate. The retained rows still win when they already matched.
    return if (resolution.item == null && lookup is DirectLookup.Unavailable) unavailable else resolution
}

/** Outcome of MDK's account-wide DM lookup, kept distinct so "no DM" and "cannot tell" never blur. */
internal sealed class DirectLookup {
    /** MDK named a reusable DM the retained rows did not hold. */
    data class Row(
        val row: PresentedChatRowFfi,
    ) : DirectLookup()

    /** No reusable DM exists, the row is already retained, or MDK reported an unrelated error. */
    data object None : DirectLookup()

    /** MDK could not answer yet; the caller must not treat this as a miss. */
    data object Unavailable : DirectLookup()
}

/** Longest the new-chat flow waits on the account-wide lookup before treating it as unavailable. */
internal const val DIRECT_LOOKUP_TIMEOUT_MS = 5_000L

/** Classifies a failed account-wide lookup: a documented not-ready state is unavailable, anything else a miss. */
internal fun directLookupFailure(throwable: Throwable): DirectLookup =
    if (throwable is MarmotKitException.DirectConversationIndexNotReady) DirectLookup.Unavailable else DirectLookup.None

/**
 * MDK's account-wide DM with [targetReference] when the windows do not retain it. [DirectLookup.None] when
 * it is already among [retained], unknown to MDK or not reusable; [DirectLookup.Unavailable] when MDK's
 * peer index is still backfilling or the read exceeds [DIRECT_LOOKUP_TIMEOUT_MS].
 */
private suspend fun ChatsController.accountWideDirectChat(
    account: String,
    targetReference: String,
    retained: List<ChatListItem>,
): DirectLookup {
    val peer = appState.accountIdHexForMention(targetReference) ?: return DirectLookup.None
    return when (val read = appState.readExistingDirectConversation(account, peer)) {
        is ExistingDirectRead.Failed -> read.lookup
        is ExistingDirectRead.Completed -> reusableRow(account, read.existing, retained)
    }
}

/** MDK's answer within the deadline; a documented not-ready state or a timeout is unavailable, other errors a miss. */
private suspend fun WhiteNoiseAppState.readExistingDirectConversation(
    account: String,
    peer: String,
): ExistingDirectRead =
    try {
        withTimeoutOrNull(DIRECT_LOOKUP_TIMEOUT_MS) {
            ExistingDirectRead.Completed(marmotIo { existingDirectConversation(account, peer) })
        } ?: ExistingDirectRead.Failed(DirectLookup.Unavailable)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: MarmotKitException) {
        ExistingDirectRead.Failed(directLookupFailure(failure))
    }

/**
 * The presented row for a reusable DM the retained rows do not already hold. MDK confirmed the group
 * exists, so a row that cannot be read is unavailable rather than a miss: a miss would let the flow
 * create a second DM for the same peer.
 */
private suspend fun ChatsController.reusableRow(
    account: String,
    existing: ExistingDirectConversationFfi?,
    retained: List<ChatListItem>,
): DirectLookup {
    val reusable =
        existing?.takeIf { it.reusable && retained.none { row -> row.id.equals(it.groupIdHex, ignoreCase = true) } }
            ?: return DirectLookup.None
    return runCatchingCancellable { appState.marmotIo { presentedChatListRow(account, reusable.groupIdHex) } }
        .getOrNull()
        ?.let(DirectLookup::Row)
        ?: DirectLookup.Unavailable
}

/** Whether MDK's lookup completed (with or without a DM) or could not be trusted. */
private sealed class ExistingDirectRead {
    data class Completed(
        val existing: ExistingDirectConversationFfi?,
    ) : ExistingDirectRead()

    data class Failed(
        val lookup: DirectLookup,
    ) : ExistingDirectRead()
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
