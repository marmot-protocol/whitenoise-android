package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Controller-scoped proof of acceptance; optimistic and roster-only copies never establish it. */
internal class InviteConfirmationAuthority(
    initialGroup: AppGroupRecordFfi,
) {
    private var generation = inviteGeneration(initialGroup)
    private var accepted: InviteAcceptanceGeneration? = null
    private var optimistic: InviteAcceptanceGeneration? = null
    private var rollbackGroup: AppGroupRecordFfi? = null

    /** Changes when an intervening observation invalidates the invitation being accepted. */
    var revision: Long = 0L
        private set

    /** Retires in-flight results even if a later replay returns to the old Welcome. */
    fun invalidate() {
        revision += 1L
        accepted = null
        finish()
    }

    /** Keeps the rendered Join optimistic while retaining the latest unconfirmed record for failure. */
    fun begin(group: AppGroupRecordFfi) {
        optimistic = inviteGeneration(group)
        rollbackGroup = group
    }

    /** Releases only the in-flight presentation; native acceptance evidence remains until invalidated. */
    fun finish() {
        optimistic = null
        rollbackGroup = null
    }

    /** Restores the latest unconfirmed metadata unless native acceptance or a newer invitation won. */
    fun rollback(current: AppGroupRecordFfi): AppGroupRecordFfi {
        val restored =
            if (optimistic?.matches(current) == true && !isConfirmed(current)) {
                rollbackGroup ?: current
            } else {
                current
            }
        finish()
        return restored
    }

    /** Applies canonical confirmation without allowing an older same-Welcome pending copy to win. */
    fun reconcile(
        update: AppGroupRecordFfi,
        establishesAcceptance: Boolean = true,
    ): AppGroupRecordFfi {
        val current = inviteGeneration(update)
        val acceptsConfirmation = update.acceptsInviteResults() && !current.welcomeMessageIdHex.isNullOrBlank()
        if (current != generation || !acceptsConfirmation) {
            invalidate()
        }
        generation = current
        if (establishesAcceptance && !update.pendingConfirmation && acceptsConfirmation) {
            accepted = current
        }
        if (optimistic?.matches(update) == true) {
            rollbackGroup =
                if (establishesAcceptance) {
                    update
                } else {
                    update.copy(pendingConfirmation = rollbackGroup?.pendingConfirmation ?: true)
                }
        }
        return if (isConfirmed(update) || optimistic?.matches(update) == true) {
            update.copy(pendingConfirmation = false)
        } else {
            update
        }
    }

    /** Checks positive native evidence, never merely the currently rendered optimistic flag. */
    fun isConfirmed(group: AppGroupRecordFfi): Boolean =
        accepted?.matches(group) == true &&
            group.acceptsInviteResults()
}

/** Terminal or frozen observations must invalidate a late native Join result. */
internal fun AppGroupRecordFfi.acceptsInviteResults(): Boolean =
    selfMembership == SelfMembershipFfi.MEMBER &&
        !unrecoverable &&
        !disbanding &&
        !disbanded

/** Resolves an ambiguous row from MDK and always releases the short-lived subscription. */
internal suspend fun ConversationLiveSubscriptions.readInviteConfirmation(
    account: String,
    groupIdHex: String,
): AppGroupRecordFfi {
    val subscription = openGroupState(account, groupIdHex)
    return try {
        withContext(Dispatchers.IO) { checkNotNull(subscription.snapshot()) }
            .also { check(it.groupIdHex == groupIdHex) }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { subscription.close() }
    }
}
