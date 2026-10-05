package dev.ipf.whitenoise.android.notifications

import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticates routing fields while SystemUI is allowed to add an unsent reply extra. */
internal class NotificationBoundTap(
    private val preferences: SharedPreferences,
) {
    /** Persist the routing secret before issuing a signature; callers fall back to an immutable tap on failure. */
    fun sign(
        notificationKey: String,
        tapToken: String,
        target: NotificationTarget,
    ): String =
        synchronized(secretLock) {
            val secret =
                readSecret() ?: ByteArray(SECRET_BYTES).also {
                    SecureRandom().nextBytes(it)
                    if (!preferences.edit().putString(SECRET_KEY, encode(it)).commit()) {
                        preferences.edit().remove(SECRET_KEY).apply()
                        error("Could not persist notification routing secret")
                    }
                }
            signature(secret, notificationKey, tapToken, target)
        }

    /** Validate the complete destination against the current card token using a constant-time signature comparison. */
    fun matches(
        notificationKey: String,
        tapToken: String,
        target: NotificationTarget,
        candidateSignature: String?,
    ): Boolean {
        val secret = readSecret()
        val candidate = candidateSignature?.takeIf { it.length == SIGNATURE_CHARS }
        return secret != null &&
            candidate != null &&
            MessageDigest.isEqual(
                signature(secret, notificationKey, tapToken, target).toByteArray(Charsets.UTF_8),
                candidate.toByteArray(Charsets.UTF_8),
            )
    }

    /** Malformed or missing secrets cannot authenticate a tap and are never replaced during validation. */
    private fun readSecret(): ByteArray? =
        preferences.getString(SECRET_KEY, null)?.let {
            val decoded = runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull()
            decoded?.takeIf { bytes -> bytes.size == SECRET_BYTES }
        }

    /** Length prefixes prevent ambiguous field boundaries; reply plaintext is deliberately excluded. */
    private fun signature(
        secret: ByteArray,
        notificationKey: String,
        tapToken: String,
        target: NotificationTarget,
    ): String {
        val fields =
            listOf(
                notificationKey,
                tapToken,
                target.accountRef,
                target.groupIdHex,
                target.messageIdHex.orEmpty(),
                target.kind.name,
            )
        val input = fields.joinToString(separator = "") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return encode(mac.doFinal(input))
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        const val SECRET_KEY = "bound_route_secret_v1"
        const val SECRET_BYTES = 32
        const val SIGNATURE_CHARS = 43
        val secretLock = Any()
    }
}
