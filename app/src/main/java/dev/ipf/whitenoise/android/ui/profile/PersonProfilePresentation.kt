package dev.ipf.whitenoise.android.ui.profile

import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CancellationException

/** Presentation callbacks expire on account/runtime replacement or disposal; accepted native work keeps its owner. */
internal class PersonProfileOwner(
    private val available: () -> Boolean,
) {
    private var alive = true

    /** Checks the current account/runtime and local view lifetime at invocation. */
    fun canAct(): Boolean = alive && available()

    /** Stops a stale callback before it can enter the next native stage. */
    fun requireCurrent() {
        if (!canAct()) throw CancellationException("Profile presentation owner changed")
    }

    /** Claims permanent navigation synchronously, before a captured callback can admit more work. */
    fun leave(action: () -> Unit) {
        if (!canAct()) return
        dispose()
        action()
    }

    /** Permanently revokes this view without cancelling accepted process-owned native work. */
    fun dispose() {
        alive = false
    }
}

/** Only current, non-DM roster evidence contributes shared groups; unavailable rosters stay explicitly unresolved. */
internal data class PersonSharedGroups(
    val groups: List<ChatListItem>,
    val unresolvedGroupIds: Set<String>,
)

/** Reads current immutable row snapshots without inferring membership from cached titles, counts or group names. */
internal fun personSharedGroups(
    items: List<ChatListItem>,
    activeAccountHex: String?,
    targetHex: String?,
): PersonSharedGroups {
    if (activeAccountHex.isNullOrBlank() || targetHex.isNullOrBlank() || activeAccountHex.equals(targetHex, true)) {
        return PersonSharedGroups(emptyList(), emptySet())
    }
    val candidates =
        items
            .filter {
                !it.isDm() && !it.group.pendingConfirmation && !it.removedFromGroup(activeAccountHex)
            }.distinctBy { it.group.groupIdHex.lowercase() }
    return PersonSharedGroups(
        groups =
            candidates.filter { item ->
                item.memberSnapshot?.let {
                    it.containsAccount(activeAccountHex) && it.containsAccount(targetHex)
                } == true
            },
        unresolvedGroupIds =
            candidates.filter { it.memberSnapshot == null }.mapTo(linkedSetOf()) {
                it.group.groupIdHex
            },
    )
}

/** Public fields and local display name are separate; private notes never enter this share/copy presentation. */
internal data class PersonProfilePresentation(
    val title: String,
    val publishedName: String,
    val seed: String,
    val pictureUrl: String?,
    val bannerUrl: String?,
    val avatarClickable: Boolean,
    val about: String?,
    val publicKey: String,
    val nip05: String?,
    val nip05Verified: Boolean,
    val lightningAddress: String?,
    val hasTarget: Boolean,
    val self: Boolean,
    val roleLabel: String? = null,
)

/** Reuses the existing live archived-inclusive projection, never the frozen foreground chat-list snapshot. */
internal fun WhiteNoiseAppState.personSharedGroupsForProfile(targetHex: String?): PersonSharedGroups =
    personSharedGroups(forwardTargets(), activeAccount?.accountIdHex, targetHex)
