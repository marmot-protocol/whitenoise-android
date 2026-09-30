package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R

private const val MAX_AMBER_ERROR_CAUSE_DEPTH = 8

/** Explains a duplicate Amber identity without exposing the native account value or changing its signer. */
internal fun amberSignInFailureDetail(error: Throwable): AppText {
    val duplicate =
        generateSequence(error) { it.cause }
            .take(MAX_AMBER_ERROR_CAUSE_DEPTH)
            .any { it is MarmotKitException.DuplicateIdentity }
    return AppText.Resource(
        if (duplicate) R.string.amber_identity_already_added else R.string.error_try_again,
    )
}
