package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Log
import java.security.GeneralSecurityException

/**
 * Persists the small Android-owned installer scheduling record independently
 * from the attachment cache and MDK's protocol state.
 */
internal interface AttachmentInstallerHandoffRecordStore {
    /** Returns the decrypted record fields, or an empty map when none exist. */
    fun readAll(): Map<String, String>

    /** Atomically replaces every record field and reports whether it reached disk. */
    fun replaceAllDurably(values: Map<String, String>): Boolean
}

/** Keystore-backed production persistence for sensitive attachment identifiers. */
internal class EncryptedAttachmentInstallerHandoffRecordStore(
    private val secureStore: KeystoreSecureStore,
) : AttachmentInstallerHandoffRecordStore {
    /** Reads and authenticates the sealed record, dropping an unusable ciphertext. */
    override fun readAll(): Map<String, String> = recoverFromSecurityFailure(emptyMap()) { secureStore.readAll() }

    /** Encrypts the complete replacement record before its synchronous disk commit. */
    override fun replaceAllDurably(values: Map<String, String>): Boolean =
        recoverFromSecurityFailure(false) {
            secureStore.replaceAllDurably(values)
        }

    /** Clears an unreadable encrypted record and returns the operation's safe fallback. */
    private inline fun <T> recoverFromSecurityFailure(
        fallback: T,
        operation: () -> T,
    ): T =
        try {
            operation()
        } catch (error: GeneralSecurityException) {
            secureStore.clear()
            Log.w(TAG, "installer handoff secure store was reset after a cryptographic failure", error)
            fallback
        }

    companion object {
        private const val SECURE_FILE = "whitenoise.attachment_installer_handoff.keystore"
        private const val KEY_ALIAS = "whitenoise.attachment_installer_handoff.aes_gcm.v1"
        private const val TAG = "InstallerHandoffStore"

        /** Builds the dedicated encrypted store for the application process. */
        fun create(context: Context): EncryptedAttachmentInstallerHandoffRecordStore =
            EncryptedAttachmentInstallerHandoffRecordStore(
                KeystoreSecureStore(
                    context = context.applicationContext,
                    fileName = SECURE_FILE,
                    keyProvider = AndroidKeystoreSecretKeyProvider(KEY_ALIAS),
                ),
            )
    }
}

/** Non-durable test seam; production construction always supplies the encrypted store. */
internal class VolatileAttachmentInstallerHandoffRecordStore : AttachmentInstallerHandoffRecordStore {
    private var values: Map<String, String> = emptyMap()

    /** Returns the current in-memory record snapshot. */
    override fun readAll(): Map<String, String> = synchronized(this) { values.toMap() }

    /** Replaces the in-memory record synchronously. */
    override fun replaceAllDurably(values: Map<String, String>): Boolean =
        synchronized(this) {
            this.values = values.toMap()
            true
        }
}
