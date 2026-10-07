package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.media.REMOTE_PROFILE_IMAGE_MAX_BYTES

/** Group state, never the viewer's remembered menu, grants permission at the mutation boundary. */
internal fun canCommitViewerGroupImage(
    group: AppGroupRecordFfi,
    accountId: String?,
): Boolean =
    accountId != null &&
        !group.selfMembership.isNonMember() &&
        GroupProjector.isAdminRef(group, accountId) &&
        !group.pendingConfirmation &&
        !group.unrecoverable &&
        !group.leaveRequestPending &&
        !group.disbanding &&
        !group.disbanded

/** Checks original prepared pixels against the native encrypted image; transport hashes are not plaintext hashes. */
internal suspend fun viewerGroupImageAlreadyCommitted(
    draft: ImageUploadDraft,
    hasImage: Boolean,
    download: suspend () -> ByteArray,
): Boolean {
    if (!hasImage) return false
    val actual = download()
    check(actual.size <= REMOTE_PROFILE_IMAGE_MAX_BYTES)
    return actual.contentEquals(draft.plaintext)
}

/** Admission is per command; native permissions and publication remain authoritative. */
internal data class ViewerGroupImageAdmission(
    val allowed: Boolean,
    val alreadyCommitted: Boolean = false,
    val legacyAvatarPresent: Boolean = false,
)

/** Re-reads group permission and, on an explicit retry, the encrypted image before another mutation. */
internal suspend fun admitViewerGroupImageMutation(
    appState: WhiteNoiseAppState,
    change: ScopedGroupImageMutation<ImageUploadDraft?>,
    account: String,
    groupId: String,
): ViewerGroupImageAdmission {
    if (!change.viewerPermissionCheck) return ViewerGroupImageAdmission(allowed = true)
    if (!change.isActive()) return ViewerGroupImageAdmission(allowed = false)
    val details = appState.marmotIo { groupDetails(account, groupId) }
    val group = applyAuthoritativeGroupDetails(details).group
    if (!change.isActive() ||
        !group.groupIdHex.equals(groupId, ignoreCase = true) ||
        GroupProjector.isDm(details.members.size, group.name) ||
        !canCommitViewerGroupImage(group, appState.activeAccount?.accountIdHex)
    ) {
        return ViewerGroupImageAdmission(allowed = false)
    }
    val draft = change.value
    val committed =
        change.reconcilePrimary &&
            draft != null &&
            viewerGroupImageAlreadyCommitted(draft, group.imageHashHex != null) {
                appState.marmotIo { downloadGroupBlossomImage(account, groupId) }
            }
    if (!change.isActive()) return ViewerGroupImageAdmission(allowed = false)
    return ViewerGroupImageAdmission(true, committed, !group.avatarUrl.isNullOrBlank())
}
