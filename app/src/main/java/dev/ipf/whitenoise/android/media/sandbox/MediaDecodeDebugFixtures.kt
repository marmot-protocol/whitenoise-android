package dev.ipf.whitenoise.android.media.sandbox

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process

/**
 * Debug-only worker fixtures for device instrumentation. Each entry point is inert unless the build is
 * debuggable, and the state here exists only inside one isolated worker process.
 */
internal object MediaDecodeDebugFixtures {
    // A production build never writes these fields.
    @Volatile private var retainedCanary: String? = null

    @Volatile private var retainedDescriptor: ParcelFileDescriptor? = null

    @Volatile private var ignoreRetirement = false

    private const val CANARY_DIGEST_CHARS = 16

    /** Whether this worker was told to ignore RETIRE and its own teardown, as a compromised worker could. */
    fun ignoringRetirement(): Boolean = dev.ipf.whitenoise.android.BuildConfig.DEBUG && ignoreRetirement

    /** Runs a hang fixture and returns true, the stubborn variant also ignores RETIRE. */
    fun hang(operation: Int): Boolean {
        val stubborn = operation == MediaDecodePolicy.DEBUG_STUBBORN_HANG
        if (!dev.ipf.whitenoise.android.BuildConfig.DEBUG || (operation != MediaDecodePolicy.DEBUG_HANG && !stubborn)) {
            return false
        }
        if (stubborn) ignoreRetirement = true
        Thread.sleep(MediaDecodePolicy.DEBUG_HANG_MS)
        return true
    }

    /** Whether [operation] is a worker-state canary request. */
    fun canary(operation: Int): Boolean =
        dev.ipf.whitenoise.android.BuildConfig.DEBUG &&
            (operation == MediaDecodePolicy.DEBUG_CANARY || operation == MediaDecodePolicy.DEBUG_IGNORE_RETIRE)

    /**
     * Reports the state an earlier input left in this process, then retains this input's digest and a
     * duplicate of its descriptor. A fresh generation reports neither, a reused worker reports both.
     */
    fun retainCanary(
        bytes: ByteArray,
        input: ParcelFileDescriptor,
        operation: Int,
        response: Bundle,
    ): Bundle {
        val previousDescriptor =
            retainedDescriptor?.let { descriptor ->
                runCatching {
                    android.system.Os.lseek(descriptor.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
                    readDecoderInput(descriptor)?.let(::digest)
                }.getOrNull() ?: "unreadable"
            }
        val previous = retainedCanary
        retainedCanary = digest(bytes)
        retainedDescriptor = ParcelFileDescriptor.dup(input.fileDescriptor)
        if (operation == MediaDecodePolicy.DEBUG_IGNORE_RETIRE) ignoreRetirement = true
        response.putString("mime", "${Process.myPid()}:${previous ?: "-"}:${previousDescriptor ?: "-"}")
        response.putBoolean("success", true)
        return response
    }

    private fun digest(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(CANARY_DIGEST_CHARS)
}
