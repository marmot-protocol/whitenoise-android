package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.CreateGroupOptionsFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Navigation attempts' retry identities, never a second group store. Resolved attempts are discarded. */
internal class NoteToSelfOpening {
    private val mutex = Mutex()
    private val recovery = mutableMapOf<String, Recovery>()

    private class Recovery {
        var createdId: String? = null
        var uncertainCreate = false
    }

    /** Serializes entry points and retains canonical creation before checking a late screen owner. */
    suspend fun <T> open(
        ownerKey: String,
        ensureCurrent: () -> Unit,
        findExisting: suspend () -> String?,
        create: suspend (() -> Unit, (String) -> Unit) -> Pair<String, T>,
        load: suspend (String) -> T,
    ): T =
        mutex.withLock {
            ensureCurrent()
            val attempt = recovery.getOrPut(ownerKey) { Recovery() }
            val existing = attempt.createdId ?: findExisting()
            ensureCurrent()
            val result =
                if (existing != null) {
                    attempt.createdId = existing
                    load(existing)
                } else {
                    // After an unknown outcome, retry is reconciliation only. Never blindly replay a write.
                    check(!attempt.uncertainCreate) { "Note creation has an uncertain result" }
                    val (id, row) =
                        try {
                            create({ attempt.uncertainCreate = true }, { attempt.createdId = it })
                        } catch (error: MarmotKitException.CreatedGroupProjectionUnavailable) {
                            attempt.createdId = error.groupIdHex
                            throw error
                        }
                    attempt.createdId = id
                    row
                }
            ensureCurrent()
            recovery.remove(ownerKey)
            result
        }
}

/** Stable native group name; translated labels belong only to the Android presentation. */
internal const val NOTE_TO_SELF_GROUP_NAME = "Note to self"
private const val NOTE_ROSTER_PAGE_SIZE = 50

/** Uses native storage/rosters; no self invitation, KeyPackage preparation or persistent host mapping. */
internal suspend fun WhiteNoiseAppState.openNoteToSelf(
    accountRef: String,
    accountHex: String,
    generation: Int,
    isCurrent: () -> Boolean,
): ChatListItem {
    fun ensureCurrent() {
        val sameOwner = activeAccountRef == accountRef && runtimeGeneration == generation
        val accountUnavailable = signOutInProgress || wipeInProgress
        if (!isCurrent() || !sameOwner || accountUnavailable) {
            throw CancellationException("Note to self owner changed")
        }
    }
    return noteToSelfOpening.open(
        ownerKey = "$accountRef:$generation",
        ensureCurrent = ::ensureCurrent,
        findExisting = { findNativeNotes(accountRef, accountHex, ::ensureCurrent) },
        create = { markStarted, markCreated ->
            val retention = defaultDisappearingMessagesSeconds(accountRef).toULong()
            marmotIo(MarmotTraceSection.CREATE_GROUP) {
                ensureCurrent()
                markStarted()
                val created =
                    try {
                        createGroupWithOptionsDetailed(
                            accountRef,
                            NOTE_TO_SELF_GROUP_NAME,
                            emptyList(),
                            CreateGroupOptionsFfi(null, null, retention),
                        )
                    } catch (error: MarmotKitException.CreatedGroupProjectionUnavailable) {
                        // Preserve the failure's canonical ID before IO cancellation can discard it.
                        markCreated(error.groupIdHex)
                        throw error
                    }
                // Retain identity inside the IO boundary: cancellation can discard its returned value.
                markCreated(created.groupIdHex)
                created.groupIdHex to chatListItemFromProjection(created.chatListRow)
            }
        },
        load = { id -> loadNativeNotes(accountRef, accountHex, id, ::ensureCurrent) },
    )
}

/** The roster is supplied by MDK; this predicate only controls the private-notes entry point. */
internal fun isNoteToSelfRoster(
    memberIds: List<String>,
    accountHex: String,
): Boolean = memberIds.isNotEmpty() && memberIds.all { it.equals(accountHex, ignoreCase = true) }

private fun ChatListRowFfi.isNotesCandidate(): Boolean =
    groupName == NOTE_TO_SELF_GROUP_NAME &&
        selfMembership == SelfMembershipFfi.MEMBER &&
        !pendingConfirmation &&
        !disbanding &&
        lifecycleState != GroupLifecycleStateFfi.DISBANDED &&
        lifecycleState != GroupLifecycleStateFfi.UNRECOVERABLE

/** Read archived groups too; a failed/partial roster read must never lead to another create. */
private suspend fun WhiteNoiseAppState.findNativeNotes(
    accountRef: String,
    accountHex: String,
    ensureCurrent: () -> Unit,
): String? =
    marmotIo {
        ensureCurrent()
        val candidates = chatList(accountRef, true).filter { it.isNotesCandidate() }
        val rosters =
            candidates.chunked(NOTE_ROSTER_PAGE_SIZE).flatMap { page ->
                ensureCurrent()
                groupMemberIdsPage(accountRef, page.map { it.groupIdHex })
            }
        candidates
            .firstOrNull { row ->
                rosters
                    .firstOrNull { it.groupIdHex == row.groupIdHex }
                    ?.let { isNoteToSelfRoster(it.memberIdsHex, accountHex) } == true
            }?.groupIdHex
    }

/** Revalidate current native details before restoring/opening a retained group identity. */
private suspend fun WhiteNoiseAppState.loadNativeNotes(
    accountRef: String,
    accountHex: String,
    groupId: String,
    ensureCurrent: () -> Unit,
): ChatListItem =
    marmotIo {
        ensureCurrent()
        var details = groupDetails(accountRef, groupId)
        requirePrivateNotes(details, accountHex)
        if (details.group.archived) {
            ensureCurrent()
            setGroupArchived(accountRef, groupId, false)
            ensureCurrent()
            details = groupDetails(accountRef, groupId)
            requirePrivateNotes(details, accountHex)
        }
        chatListItemFromAuthoritativeGroupDetails(details, accountHex)
    }

private fun requirePrivateNotes(
    details: GroupDetailsFfi,
    accountHex: String,
) {
    check(details.group.name == NOTE_TO_SELF_GROUP_NAME && details.group.selfMembership == SelfMembershipFfi.MEMBER)
    check(!details.group.pendingConfirmation && isNoteToSelfRoster(details.members.map { it.memberIdHex }, accountHex))
}
