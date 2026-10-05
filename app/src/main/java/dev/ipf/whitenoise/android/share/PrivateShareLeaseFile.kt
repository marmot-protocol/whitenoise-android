package dev.ipf.whitenoise.android.share

import java.io.File
import java.security.MessageDigest

/** Opaque filesystem keys preserve the existing account/chat and pending-request lease layout. */
internal fun privateShareLeaseFile(
    root: File,
    kind: String,
    scope: String,
): File {
    val key =
        MessageDigest
            .getInstance("SHA-256")
            .digest(scope.toByteArray())
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    return File(root, "$kind-$key.lease")
}
