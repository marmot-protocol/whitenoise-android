package dev.ipf.whitenoise.android.state

import androidx.annotation.StringRes
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MarmotKitException.MissingKeyPackage
import dev.ipf.marmotkit.MemberResolutionStatusFfi
import dev.ipf.marmotkit.MissingKeyPackageReasonFfi
import dev.ipf.marmotkit.OversizedArtifactFfi
import dev.ipf.whitenoise.android.R

/** Uses recipient-specific copy only when the native account can be resolved to a visible name. */
private fun recipientFailureDetail(
    account: String,
    displayName: (String) -> String,
    @StringRes genericResource: Int,
    @StringRes namedResource: Int,
): AppText {
    val name =
        account
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let(displayName)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    return if (name == null) {
        AppText.Resource(genericResource)
    } else {
        AppText.Resource(namedResource, listOf(name))
    }
}

/** Present only distinctions that the released binding proves; never parse native details. */
internal fun inviteFailureDetail(
    throwable: Throwable,
    displayName: (String) -> String,
    recipientName: String? = null,
    @StringRes fallbackResource: Int = R.string.error_try_again,
): AppText =
    when (throwable) {
        is StartProfileChatNoActiveAccountException -> AppText.Resource(R.string.toast_no_active_account)
        // Only obsolete KeyPackages means the recipient must update; none at all means they still need setup.
        is MissingKeyPackage ->
            if (throwable.reason == MissingKeyPackageReasonFfi.OBSOLETE_ONLY) {
                recipientFailureDetail(
                    throwable.account,
                    displayName,
                    R.string.error_obsolete_key_package,
                    R.string.error_obsolete_key_package_for,
                )
            } else {
                recipientFailureDetail(
                    throwable.account,
                    displayName,
                    R.string.error_missing_key_package,
                    R.string.error_missing_key_package_for,
                )
            }
        // The lookup ran out of time or relay coverage: nothing is known to be missing, so retry, never "set up".
        is MarmotKitException.DirectoryLookupIncomplete ->
            recipientFailureDetail(
                throwable.account,
                displayName,
                R.string.error_directory_lookup_incomplete,
                R.string.error_directory_lookup_incomplete_for,
            )
        is MarmotKitException.MissingMemberInboxRoute ->
            recipientFailureDetail(
                throwable.account,
                displayName,
                R.string.error_missing_member_inbox,
                R.string.error_missing_member_inbox_for,
            )
        is MarmotKitException.InvalidKeyPackageEvent ->
            recipientName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                AppText.Resource(R.string.error_invalid_key_package_for, listOf(it))
            } ?: AppText.Resource(R.string.error_invalid_key_package)
        is MarmotKitException.InvalidIdentity -> AppText.Resource(R.string.error_invalid_identity_reference)
        is MarmotKitException.GroupHydrationPending -> AppText.Resource(R.string.toast_chat_still_loading)
        // A full disk refuses the change before anything is written; freeing space makes Retry worthwhile.
        is MarmotKitException.StorageFull -> AppText.Resource(R.string.error_storage_full)
        is MarmotKitException.EventTooLarge -> oversizedInviteDetail(throwable, fallbackResource)
        else -> skippedInviteFailureDetail(throwable, displayName, fallbackResource)
    }

/**
 * Resolves all-skipped reports and uses the caller's fallback for errors without typed recovery. Several recipients
 * are named only when every one of them has a name, with the remedy their shared skip reason calls for. Display names
 * fall back to a short npub, so the unnamed general message is a defensive fallback rather than an expected state.
 */
private fun skippedInviteFailureDetail(
    failure: Throwable,
    displayName: (String) -> String,
    @StringRes fallbackResource: Int,
): AppText {
    if (failure !is NoGroupMembersSelectedException) return AppText.Resource(fallbackResource)
    val recipient = failure.outcomes.singleOrNull()
    val skipped = failure.outcomes.skippedGroupMemberNames(displayName)
    return when {
        recipient?.status == MemberResolutionStatusFfi.INCOMPATIBLE_KEY_PACKAGE ->
            recipientFailureDetail(
                recipient.accountIdHex,
                displayName,
                R.string.error_incompatible_key_package,
                R.string.error_incompatible_key_package_for,
            )
        skipped == null -> AppText.Resource(R.string.error_no_group_members_selected)
        else -> AppText.Resource(failure.outcomes.allSkippedGuidance(), listOf(skipped.joinToString()))
    }
}

/**
 * MDK refuses a create or invite up front when an invitee's Welcome fits none of their inbox relays, so Retry cannot
 * help. Any other oversized artifact keeps [fallbackResource].
 */
private fun oversizedInviteDetail(
    tooLarge: MarmotKitException.EventTooLarge,
    @StringRes fallbackResource: Int,
): AppText =
    if (tooLarge.artifact == OversizedArtifactFfi.WELCOME) {
        AppText.Resource(R.string.error_invite_welcome_too_large)
    } else {
        AppText.Resource(fallbackResource)
    }

internal fun groupCreateFailureDetail(
    throwable: Throwable,
    displayName: (String) -> String,
): AppText = inviteFailureDetail(throwable, displayName, fallbackResource = R.string.error_group_create_failed_retry)

/**
 * Sharing an install link is optional help for a confirmed missing package, never an invalid-package diagnosis, an
 * outdated recipient or a lookup that could not finish.
 */
internal fun startProfileChatFailureIsMissingSetup(throwable: Throwable): Boolean =
    throwable is MissingKeyPackage && throwable.reason == MissingKeyPackageReasonFfi.NOT_FOUND

internal fun startProfileChatFailureDetail(
    throwable: Throwable,
    displayName: (String) -> String,
): AppText = groupCreateFailureDetail(throwable, displayName)

/** A recipient with no KeyPackage at all is someone to invite, not a failure; every other failure keeps its title. */
internal fun startProfileChatErrorTitle(
    throwable: Throwable,
    displayName: (String) -> String,
): AppText =
    if (throwable is MissingKeyPackage && startProfileChatFailureIsMissingSetup(throwable)) {
        recipientFailureDetail(
            throwable.account,
            displayName,
            R.string.start_chat_not_on_white_noise,
            R.string.start_chat_not_on_white_noise_for,
        )
    } else {
        AppText.Resource(R.string.toast_couldnt_start_chat)
    }

/** Invitation guidance for a confirmed missing package; every other start-chat failure keeps its shared detail. */
internal fun startProfileChatErrorDetail(
    throwable: Throwable,
    displayName: (String) -> String,
    recipientName: String?,
): AppText =
    if (startProfileChatFailureIsMissingSetup(throwable)) {
        AppText.Resource(R.string.start_chat_not_on_white_noise_detail)
    } else {
        inviteFailureDetail(throwable, displayName, recipientName, R.string.error_group_create_failed_retry)
    }

/** Offers a diagnostic report for unexpected and publish failures while keeping known recovery guidance in the UI. */
internal fun groupCreateFailureCopyable(throwable: Throwable): Boolean =
    when (throwable) {
        is StartProfileChatNoActiveAccountException -> false
        is NoGroupMembersSelectedException -> false
        is MissingKeyPackage -> false
        is MarmotKitException.InvalidKeyPackageEvent -> false
        is MarmotKitException.MissingMemberInboxRoute -> false
        is MarmotKitException.InvalidIdentity -> false
        is MarmotKitException.Publish, is MarmotKitException.NoUsableRelay -> true
        is MarmotKitException -> false
        else -> true
    }

internal fun startProfileChatFailureCopyable(throwable: Throwable): Boolean = groupCreateFailureCopyable(throwable)

/**
 * Uses the captured recipient for a typed removal action. [skippedName] resolves a report covering multiple recipients
 * against that same submission. An incomplete lookup keeps its retry guidance.
 */
internal fun groupCreateSelectionFailureDetail(
    throwable: Throwable,
    recipientName: String?,
    skippedName: (String) -> String = { "" },
): AppText =
    when {
        throwable is NoGroupMembersSelectedException -> groupCreateFailureDetail(throwable, skippedName)
        recipientName != null -> groupCreateFailureDetail(throwable) { recipientName }
        throwable is MissingKeyPackage && throwable.reason == MissingKeyPackageReasonFfi.OBSOLETE_ONLY ->
            AppText.Resource(R.string.error_obsolete_key_package)
        throwable is MissingKeyPackage -> AppText.Resource(R.string.error_missing_key_package)
        throwable is MarmotKitException.DirectoryLookupIncomplete ->
            AppText.Resource(R.string.error_directory_lookup_incomplete)
        throwable is MarmotKitException.MissingMemberInboxRoute -> AppText.Resource(R.string.error_missing_member_inbox)
        else -> groupCreateFailureDetail(throwable) { "" }
    }
