package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.marmotkit.GroupCreationResultFfi
import dev.ipf.whitenoise.android.diagnostics.CreationOperation
import dev.ipf.whitenoise.android.diagnostics.DmCreationInteraction
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.missingGroupMembersDetail
import dev.ipf.whitenoise.android.state.requireCreatedGroupId
import dev.ipf.whitenoise.android.state.skippedGroupMembersDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Fences process-owned mutations from a dismissed or replaced group setup screen. */
internal class GroupCreationSession(
    private val currentOwner: () -> Boolean,
) {
    private var active = true

    /** Remembers the partial outcome until the newly created group can open. */
    private var skippedMembers: AppText? = null

    /** Whether [skippedMembers] came from MDK's member report rather than being unknown until the roster is read. */
    private var memberReportCaptured = false
    val diagnostics = DmCreationInteraction(operation = CreationOperation.GROUP_CREATE)

    /** Records MDK's skipped-member report for this screen, then returns the committed group ID. */
    fun acceptCreatedGroup(
        result: GroupCreationResultFfi,
        displayName: (String) -> String,
    ): String {
        val groupId = result.requireCreatedGroupId()
        ensureCurrent()
        skippedMembers = result.memberOutcomes.skippedGroupMembersDetail(displayName)
        memberReportCaptured = true
        return groupId
    }

    /**
     * Returns the not-added notice for the group being opened and clears it. MDK's member report wins when this screen
     * received one. An unavailable projection or member report discards that report, so the [submitted] people missing
     * from the created roster's [memberIds] are then the only evidence of who was not added.
     */
    fun takeSkippedMembers(
        submitted: List<String>,
        memberIds: List<String>,
        displayName: (String) -> String,
    ): AppText? {
        val skipped =
            if (memberReportCaptured) {
                skippedMembers
            } else {
                submitted
                    .filterNot { ref -> memberIds.any { it.equals(ref, ignoreCase = true) } }
                    .missingGroupMembersDetail(displayName)
            }
        skippedMembers = null
        memberReportCaptured = false
        return skipped
    }

    /** Reads the live account, including changes before Compose disposes the old screen. */
    fun isCurrent(): Boolean = active && currentOwner()

    /** Invalidates callbacks without cancelling an already accepted native mutation. */
    fun dispose() {
        active = false
    }

    /** Stops the next stage before it can resolve a different account's native owner. */
    fun ensureCurrent() {
        if (!isCurrent()) throw CancellationException("Group creation screen was replaced")
    }

    /** Rejects non-cooperative late values as well as operations that have not started yet. */
    suspend fun <T> currentValue(load: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        val value = load()
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        return value
    }
}
