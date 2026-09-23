package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import java.util.Locale

/** One conversation this device created and opened, pinned to the account and runtime that did it. */
internal data class FreshGroupCreation(
    val accountRef: String,
    val groupIdHex: String,
    val runtimeGeneration: Int,
)

/**
 * The group whose canonical creation was opened most recently, and nothing older.
 *
 * A group that was just created has no recovery story yet, but its advisory `group_recovery_status`
 * read can still fail against a projection that is settling. Remembering which conversation is in
 * that moment lets the presentation tell "we have no answer yet for a group we just made" apart
 * from "the engine could not answer for a group that has been around". One slot is the whole point:
 * creating another group, or an older creation completing late, cannot leave a second conversation
 * claiming freshness, and the account and runtime generation are part of the identity so a switch
 * or a restart retires it too.
 */
internal class FreshGroupCreationRegistry {
    private var current: FreshGroupCreation? = null

    /** Records a canonical creation that has just been opened, superseding any earlier one. */
    @Synchronized
    fun record(
        accountRef: String?,
        groupIdHex: String,
        runtimeGeneration: Int,
    ) {
        current =
            accountRef?.takeIf { groupIdHex.isNotBlank() }?.let {
                FreshGroupCreation(it, groupIdHex.normalizedGroupId(), runtimeGeneration)
            }
    }

    /** Whether this exact conversation, under this exact runtime, is the current fresh creation. */
    @Synchronized
    fun isFresh(
        accountRef: String?,
        groupIdHex: String,
        runtimeGeneration: Int,
    ): Boolean {
        val creation = current ?: return false
        return accountRef != null &&
            creation.accountRef == accountRef &&
            creation.groupIdHex == groupIdHex.normalizedGroupId() &&
            creation.runtimeGeneration == runtimeGeneration
    }

    /** Retires the context once the engine has answered for that conversation. */
    @Synchronized
    fun settle(
        accountRef: String?,
        groupIdHex: String,
        runtimeGeneration: Int,
    ) {
        if (isFresh(accountRef, groupIdHex, runtimeGeneration)) current = null
    }

    /** Drops any context, for a teardown that invalidates every conversation. */
    @Synchronized
    fun clear() {
        current = null
    }
}

/** Group ids arrive from several seams; compare them the one way. */
private fun String.normalizedGroupId(): String = trim().lowercase(Locale.ROOT)

/**
 * Whether a failed advisory recovery read is evidence the conversation should show.
 *
 * A read that fails while confirmed evidence is already on screen is always presentable: the user
 * keeps that evidence and gets a bounded retry beside it. A read that has never succeeded for a
 * group the account just created is not — it proves nothing about a group that was created and
 * opened successfully, and rendering "Couldn't check group recovery" there makes a healthy new
 * conversation look unsafe. Every other failure keeps the behaviour it has always had.
 */
internal fun groupRecoveryReadFailureIsPresentable(
    lastConfirmedStatus: GroupRecoveryStatusFfi?,
    freshlyCreated: Boolean,
): Boolean = lastConfirmedStatus != null || !freshlyCreated
