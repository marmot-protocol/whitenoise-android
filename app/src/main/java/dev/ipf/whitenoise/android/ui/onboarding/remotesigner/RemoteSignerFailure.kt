package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import androidx.annotation.StringRes
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.Nip46ErrorFfi
import dev.ipf.whitenoise.android.R

/** Why pairing stopped, in user terms. [startOver] failures cannot be retried with the same session. */
internal enum class RemoteSignerFailure(
    @StringRes val messageRes: Int,
    val startOver: Boolean = false,
) {
    Declined(R.string.remote_signer_error_declined),
    TimedOut(R.string.remote_signer_error_timeout),
    Unreachable(R.string.remote_signer_error_unreachable),
    NotSaved(R.string.remote_signer_error_storage),
    SignInFailed(R.string.remote_signer_error_sign_in),
    WrongAccount(R.string.remote_signer_error_wrong_account, startOver = true),
    Unsupported(R.string.remote_signer_error_unsupported, startOver = true),
    Ended(R.string.remote_signer_error_ended, startOver = true),
    PermissionsChanged(R.string.remote_signer_error_unreachable),
}

/** Maps an MDK pairing or login failure; null means the user cancelled and the flow simply steps back. */
internal fun remoteSignerFailureFor(error: Throwable): RemoteSignerFailure? =
    when (error) {
        is Nip46ErrorFfi -> nip46Failure(error)
        is MarmotKitException.Nip46 -> nip46Failure(error.reason)
        is MarmotKitException.ExternalSignerRejected -> RemoteSignerFailure.Declined
        is MarmotKitException.ExternalSignerMismatch -> RemoteSignerFailure.WrongAccount
        else -> RemoteSignerFailure.SignInFailed
    }

private fun nip46Failure(reason: Nip46ErrorFfi): RemoteSignerFailure? =
    when (reason) {
        is Nip46ErrorFfi.Cancelled -> null
        is Nip46ErrorFfi.Rejected -> RemoteSignerFailure.Declined
        is Nip46ErrorFfi.Timeout -> RemoteSignerFailure.TimedOut
        is Nip46ErrorFfi.Storage -> RemoteSignerFailure.NotSaved
        is Nip46ErrorFfi.Mismatch -> RemoteSignerFailure.WrongAccount
        is Nip46ErrorFfi.Unsupported, is Nip46ErrorFfi.Invalid, is Nip46ErrorFfi.ProxyConfig ->
            RemoteSignerFailure.Unsupported
        is Nip46ErrorFfi.LoggedOut -> RemoteSignerFailure.Ended
        is Nip46ErrorFfi.PermissionApprovalRequired -> RemoteSignerFailure.PermissionsChanged
        is Nip46ErrorFfi.Unavailable,
        is Nip46ErrorFfi.RelayPublish,
        is Nip46ErrorFfi.Busy,
        is Nip46ErrorFfi.Disconnected,
        is Nip46ErrorFfi.RequestNotFound,
        -> RemoteSignerFailure.Unreachable
    }
