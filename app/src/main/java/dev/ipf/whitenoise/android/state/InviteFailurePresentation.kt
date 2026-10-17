package dev.ipf.whitenoise.android.state

import androidx.annotation.StringRes
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MarmotKitException.MissingKeyPackage
import dev.ipf.marmotkit.MissingKeyPackageReasonFfi
import dev.ipf.whitenoise.android.R

private fun recipientFailureDetail(
    account: String,
    displayName: (String) -> String,
    @StringRes genericResource: Int,
    @StringRes namedResource: Int,
): AppText {
    val normalizedAccount = account.trim()
    return if (normalizedAccount.isEmpty()) {
        AppText.Resource(genericResource)
    } else {
        AppText.Resource(namedResource, listOf(displayName(normalizedAccount)))
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
        else -> AppText.Resource(fallbackResource)
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

internal fun groupCreateFailureCopyable(throwable: Throwable): Boolean =
    when (throwable) {
        is StartProfileChatNoActiveAccountException -> false
        is MissingKeyPackage -> false
        is MarmotKitException.InvalidKeyPackageEvent -> false
        is MarmotKitException.MissingMemberInboxRoute -> false
        is MarmotKitException.InvalidIdentity -> false
        is MarmotKitException.Publish -> true
        is MarmotKitException -> false
        else -> true
    }

internal fun startProfileChatFailureCopyable(throwable: Throwable): Boolean = groupCreateFailureCopyable(throwable)

/**
 * Founding-roster recovery names only a recipient matched to the submitted selection. An incomplete lookup is never
 * matched, because retrying, not removing the recipient, is its remedy.
 */
internal fun groupCreateSelectionFailureDetail(
    throwable: Throwable,
    recipientName: String?,
): AppText =
    when {
        recipientName != null -> groupCreateFailureDetail(throwable) { recipientName }
        throwable is MissingKeyPackage && throwable.reason == MissingKeyPackageReasonFfi.OBSOLETE_ONLY ->
            AppText.Resource(R.string.error_obsolete_key_package)
        throwable is MissingKeyPackage -> AppText.Resource(R.string.error_missing_key_package)
        throwable is MarmotKitException.DirectoryLookupIncomplete ->
            AppText.Resource(R.string.error_directory_lookup_incomplete)
        throwable is MarmotKitException.MissingMemberInboxRoute -> AppText.Resource(R.string.error_missing_member_inbox)
        else -> groupCreateFailureDetail(throwable) { "" }
    }
