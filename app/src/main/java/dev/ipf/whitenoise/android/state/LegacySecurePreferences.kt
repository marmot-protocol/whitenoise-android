package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Read-only access to the retired `androidx.security-crypto` stores, kept
 * solely so an existing install's values can be imported into
 * [KeystoreSecureStore] once and the old file deleted.
 *
 * Nothing writes through here. The dependency itself cannot leave the build
 * until the remaining consumer (the push-token store) migrates too, but no new
 * data is committed to the EOL library through this path.
 */
@Suppress("DEPRECATION")
internal object LegacySecurePreferences {
    /**
     * Returns the decrypted contents of [fileName], or null when the file was
     * never created. Throws whatever the library throws for an unreadable
     * keyset so callers can treat it as corruption and drop the file.
     */
    fun read(
        context: Context,
        fileName: String,
    ): Map<String, String>? {
        if (!exists(context, fileName)) return null
        val masterKey =
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        val prefs =
            EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        return prefs.all.mapNotNull { (key, value) -> stringOf(value)?.let { key to it } }.toMap()
    }

    // Opening a never-created EncryptedSharedPreferences would generate a
    // keyset for a file with nothing to import, so check the backing file
    // first rather than materializing one.
    private fun exists(
        context: Context,
        fileName: String,
    ): Boolean = File(File(context.applicationInfo.dataDir, "shared_prefs"), "$fileName.xml").exists()

    // Values were stored as strings or, for the app-lock timestamp, a long.
    private fun stringOf(value: Any?): String? =
        when (value) {
            is String -> value
            is Long -> value.toString()
            is Int -> value.toString()
            else -> null
        }
}
