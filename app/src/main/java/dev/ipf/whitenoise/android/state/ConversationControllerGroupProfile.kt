package dev.ipf.whitenoise.android.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.GroupImageMutationFailure
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.media.classifyGroupImageMutationFailure
import dev.ipf.whitenoise.android.media.shouldCommitPrimaryGroupImageMutation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

/** Commits edited profile fields with the existing group-change admission flow. */
internal suspend fun WhiteNoiseAppState.commitGroupProfile(
    account: String,
    groupIdHex: String,
    name: String?,
    description: String?,
) {
    runGroupChange(account, groupIdHex, actionLabel = R.string.edit_group_info_title) {
        marmotIo(MarmotTraceSection.UPDATE_GROUP_PROFILE) {
            updateGroupProfile(account, groupIdHex, name, description)
        }
    }
}

/**
 * Shared shape of the two local removals: one mutation slot, a success notice chosen by the removal itself. Both
 * close this conversation, so the notice is app-wide instead of scoped to the closed route (#3310), and like a
 * failure it is not shown on a replacement account or runtime.
 */
internal suspend fun ConversationController.runLocalGroupRemoval(
    failureToast: Int,
    operationCode: String,
    removal: suspend (account: String) -> Int,
): Boolean =
    withMutationLockResult(false) {
        lastMutationError = null
        val account = conversationAccountRef ?: return@withMutationLockResult false
        runCatchingCancellable {
            val success = removal(account)
            val sameRuntime = appState.runtimeGeneration == conversationRuntimeGeneration
            if (sameRuntime && appState.activeAccountRef == account) appState.presentTransient(success)
            true
        }.onFailure { recordMutationFailure(failureToast, operationCode, it) }.getOrDefault(false)
    }

/**
 * Installs a just-committed group profile locally and hands it to the chat list (#2696).
 *
 * [LocalGroupFieldAuthority] then retains the new name and description against a pre-edit
 * snapshot that was already in flight, while a genuinely newer authoritative commit still wins.
 */
internal fun ConversationController.applyLocalGroupProfile(
    before: AppGroupRecordFfi,
    updatedName: String?,
    updatedDescription: String?,
    account: String,
) {
    // [before] is the record the edit was made against: the subscription may already have applied
    // the committed profile, and the fences still need the values it replaced. Only the fields
    // this save changed are fenced; the other one keeps whatever the subscription last applied.
    val newName = updatedName?.takeIf { it != before.name }
    val newDescription = updatedDescription?.takeIf { it != before.description }
    if (newName == null && newDescription == null) return
    newName?.let { localGroupNames.record(group.groupIdHex, before.name, it) }
    newDescription?.let { localGroupDescriptions.record(group.groupIdHex, before.description, it) }
    val clearedName = before.name.takeIf { newName?.isBlank() == true && it.isNotBlank() }
    group =
        group.copy(
            name = updatedName ?: group.name,
            description = updatedDescription ?: group.description,
        )
    appState.applyLocalGroupUpdate(group, account, clearedName = clearedName)
}

/**
 * Best-effort clean-up of the encrypted image a public avatar supersedes. The user never asked for it, so when MDK
 * holds it back nobody is asked: the refusal, like any failure, leaves the encrypted image in place, and the
 * public avatar still wins for display.
 */
internal suspend fun ConversationController.clearSupersededGroupImage(account: String): Boolean =
    runCatchingCancellable {
        appState.runGroupChange(account, group.groupIdHex, askUser = false, actionLabel = R.string.remove_photo) {
            appState.marmotIo { clearGroupImage(account, group.groupIdHex) }
        }
    }.onFailure {
        if (BuildConfig.DEBUG) Log.w("DMConversation", "encrypted avatar cleanup failed", it)
    }.isSuccess

/** Serializes permission, primary image publication and legacy cleanup, retaining ambiguous cleanup recovery. */
internal suspend fun ConversationController.commitGroupImageMutation(
    account: String,
    capturedGroupId: String,
    change: ScopedGroupImageMutation<ImageUploadDraft?>,
    requestedMutationKey: String,
    onLegacyClearAttempt: () -> Unit,
): Boolean =
    appState.withGroupCommitLock(account, capturedGroupId) {
        val admission = admitViewerGroupImageMutation(appState, change, account, capturedGroupId)
        if (!admission.allowed) return@withGroupCommitLock false
        val needsPrimary =
            if (change.viewerPermissionCheck) {
                !admission.alreadyCommitted
            } else {
                shouldCommitPrimaryGroupImageMutation(
                    requestedMutationKey = requestedMutationKey,
                    pendingLegacyClearMutationKey = pendingLegacyAvatarClearAfterImageMutationKey,
                    hasProjectedEncryptedImage = group.imageHashHex != null,
                )
            }
        if (needsPrimary) {
            commitGroupImageBytes(account, capturedGroupId, change.value, change)
        }

        // URL avatars win; clear the legacy URL only after the encrypted
        // mutation succeeds so a partial failure never removes both images.
        if (admission.legacyAvatarPresent ||
            !group.avatarUrl.isNullOrBlank() ||
            pendingLegacyAvatarClearAfterImageMutationKey != null
        ) {
            pendingLegacyAvatarClearAfterImageMutationKey = requestedMutationKey
            onLegacyClearAttempt()
            try {
                appState.runGroupChange(
                    account,
                    capturedGroupId,
                    stillWanted = { change.stillOwned() },
                    actionLabel = R.string.change_photo,
                ) {
                    val admitted = isAdmittedGroupChange()
                    appState.marmotIo {
                        if (!admitted && !change.stillOwned()) throw CancellationException("Viewer owner replaced")
                        updateGroupAvatarUrl(account, capturedGroupId, null, null, null)
                    }
                }
            } catch (declined: GroupChangeDeclinedException) {
                // Waiting after the new image landed must say so, not end quietly behind the old URL avatar.
                if (!change.stillOwned()) throw declined
                throw declined.refusal
            }
            pendingLegacyAvatarClearAfterImageMutationKey = null
        }
        true
    }

/** Publish the current native group image/roster through the normal conversation and chat-list state path. */
internal suspend fun ConversationController.refreshViewerGroupImageDetails(
    account: String,
    groupId: String,
    change: ScopedGroupImageMutation<ImageUploadDraft?>,
): Boolean {
    if (!change.isActive()) return false
    val details = appState.marmotIo { groupDetails(account, groupId) }
    val applicable = change.isActive() && details.group.groupIdHex.equals(groupId, ignoreCase = true)
    if (applicable) {
        check(applyMutationDetails(account, details)) { "Authoritative group image projection unavailable" }
    }
    return applicable
}

/**
 * Native command entry rechecks viewer ownership after dispatcher/lock suspension, except on the retry of a change
 * the user continued past, which must reach MDK to spend its acknowledgement.
 */
private suspend fun ConversationController.commitGroupImageBytes(
    account: String,
    groupId: String,
    draft: ImageUploadDraft?,
    change: ScopedGroupImageMutation<ImageUploadDraft?>,
) {
    appState.runGroupChange(
        account,
        groupId,
        stillWanted = { change.stillOwned() },
        actionLabel =
            if (draft == null) {
                R.string.remove_photo
            } else {
                R.string.change_photo
            },
    ) {
        // A retry the user continued past must reach MDK, or its acknowledgement outlives it.
        val admitted = isAdmittedGroupChange()
        appState.marmotIo {
            if (!admitted && !change.stillOwned()) throw CancellationException("Viewer owner replaced")
            if (draft != null) {
                updateGroupImage(account, groupId, draft.plaintext, draft.mediaType)
            } else {
                clearGroupImage(account, groupId)
            }
        }
    }
}

internal fun ConversationController.presentGroupImageMutationFailure(
    throwable: Throwable,
    requestedMutationKey: String,
    attemptedLegacyClear: Boolean,
) {
    val failure =
        classifyGroupImageMutationFailure(
            requestedMutationKey = requestedMutationKey,
            pendingLegacyClearMutationKey = pendingLegacyAvatarClearAfterImageMutationKey,
            attemptedLegacyClear = attemptedLegacyClear,
        )
    val title =
        when (failure) {
            GroupImageMutationFailure.UploadCleanup -> R.string.toast_group_image_uploaded_cleanup_failed
            else -> R.string.toast_couldnt_update_group
        }
    recordMutationFailure(title, "GROUP_IMAGE_UPDATE", throwable)
}
