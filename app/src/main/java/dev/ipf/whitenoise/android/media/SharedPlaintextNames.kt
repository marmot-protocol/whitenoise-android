package dev.ipf.whitenoise.android.media

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val ACCOUNT_SHARE_PREFIX = "account_"
private const val SHARE_KEY_NAME = "shared_plaintext_filename_key"
private const val SHARE_KEY_BYTES = 32
private const val UNSIGNED_BYTE_MASK = 0xff
private const val SHARE_MAC = "HmacSHA256"
private val keyLock = Any()

/** Returns a stable account prefix that a recipient cannot derive from the public key. Call from disk IO. */
internal fun sharedPlaintextPrefix(
    context: Context,
    accountRef: String,
): String {
    require(accountRef.isNotBlank())
    val key = sharedPlaintextKey(context.noBackupFilesDir)
    val mac = Mac.getInstance(SHARE_MAC).apply { init(SecretKeySpec(key, SHARE_MAC)) }
    return formatSharePrefix(mac.doFinal(accountRef.encodeToByteArray()))
}

/** Identifies staged names from the earlier wipe build for cleanup only. */
internal fun legacySharedPlaintextPrefix(accountRef: String): String =
    formatSharePrefix(MessageDigest.getInstance("SHA-256").digest(accountRef.encodeToByteArray()))

private fun formatSharePrefix(bytes: ByteArray): String {
    val owner = bytes.joinToString("") { "%02x".format(it.toInt() and UNSIGNED_BYTE_MASK) }
    return "$ACCOUNT_SHARE_PREFIX${owner}_"
}

/** Atomically creates the installation key and verifies it is readable before staging any files. */
private fun sharedPlaintextKey(directory: File): ByteArray =
    synchronized(keyLock) {
        val keyFile = AtomicFile(File(directory, SHARE_KEY_NAME))
        val key =
            try {
                keyFile.readFully()
            } catch (_: FileNotFoundException) {
                val generated = ByteArray(SHARE_KEY_BYTES).also(SecureRandom()::nextBytes)
                val output = keyFile.startWrite()
                try {
                    output.write(generated)
                    keyFile.finishWrite(output)
                } catch (error: IOException) {
                    keyFile.failWrite(output)
                    throw error
                }
                keyFile.readFully().also { check(it.contentEquals(generated)) { "could not persist share filename key" } }
            }
        check(key.size == SHARE_KEY_BYTES) { "invalid share filename key" }
        key
    }
