package dev.ipf.whitenoise.android.notifications

import android.content.Context
import androidx.security.crypto.MasterKey
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncryptedNotificationReply(
    val initializationVector: ByteArray,
    val ciphertext: ByteArray,
)

/**
 * Seals notification replies before they enter WorkManager's plaintext database.
 * The stable request id and all routing metadata are authenticated as associated
 * data so ciphertext cannot be moved to another work request or destination.
 */
internal class NotificationReplyCipher(
    private val secretKey: SecretKey,
) {
    fun encrypt(
        reply: String,
        requestId: UUID,
        action: NotificationAction,
    ): EncryptedNotificationReply {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD(associatedData(requestId, action))
        return EncryptedNotificationReply(
            initializationVector = cipher.iv,
            ciphertext = cipher.doFinal(reply.toByteArray(Charsets.UTF_8)),
        )
    }

    fun decrypt(
        encryptedReply: EncryptedNotificationReply,
        requestId: UUID,
        action: NotificationAction,
    ): String {
        require(encryptedReply.initializationVector.size == GCM_IV_BYTES) { "Invalid notification reply IV" }
        require(encryptedReply.ciphertext.size >= GCM_TAG_BYTES) { "Invalid notification reply ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(GCM_TAG_BITS, encryptedReply.initializationVector),
        )
        cipher.updateAAD(associatedData(requestId, action))
        return cipher.doFinal(encryptedReply.ciphertext).toString(Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / Byte.SIZE_BITS
        private const val GCM_IV_BYTES = 12
        private const val AAD_DOMAIN = "whitenoise.notification-reply"
        private const val AAD_VERSION = 1
        private const val KEY_ALIAS = "whitenoise_notification_reply_aes_gcm_v1"
        private val KEY_LOCK = Any()

        @Suppress("DEPRECATION")
        fun create(context: Context): NotificationReplyCipher =
            synchronized(KEY_LOCK) {
                MasterKey
                    .Builder(context.applicationContext, KEY_ALIAS)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val keyStore =
                    KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                        load(null)
                    }
                val secretKey =
                    keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                        ?: error("Missing notification reply encryption key")
                NotificationReplyCipher(secretKey)
            }

        private fun associatedData(
            requestId: UUID,
            action: NotificationAction,
        ): ByteArray =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeCanonicalString(AAD_DOMAIN)
                    output.writeInt(AAD_VERSION)
                    output.writeLong(requestId.mostSignificantBits)
                    output.writeLong(requestId.leastSignificantBits)
                    output.writeCanonicalString(action.kind.name)
                    output.writeCanonicalString(action.target.accountRef)
                    output.writeCanonicalString(action.target.groupIdHex)
                    output.writeCanonicalNullableString(action.target.messageIdHex)
                    output.writeCanonicalString(action.target.kind.name)
                    output.writeCanonicalString(action.notificationTag)
                    output.writeInt(action.notificationId)
                }
                bytes.toByteArray()
            }

        private fun DataOutputStream.writeCanonicalString(value: String) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            writeInt(encoded.size)
            write(encoded)
        }

        private fun DataOutputStream.writeCanonicalNullableString(value: String?) {
            writeBoolean(value != null)
            if (value != null) writeCanonicalString(value)
        }

        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
