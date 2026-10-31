package dev.ipf.whitenoise.android.media.sandbox

import android.os.Process
import java.nio.ByteBuffer

/** JNI is loaded only by permissionless workers; the host receives raw bounded outputs. */
internal object NativeMediaEngine {
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        check(Process.isIsolated()) { "Native media codecs require an isolated UID" }
        if (!loaded) {
            System.loadLibrary("media_decoder")
            loaded = true
        }
    }

    @JvmStatic external fun nativeOpen(
        fd: Int,
        offset: Long,
        length: Long,
        edge: Int,
    ): Long

    @JvmStatic external fun nativeInfo(handle: Long): LongArray

    @JvmStatic external fun nativeRead(
        handle: Long,
        videoOut: ByteBuffer?,
        audioOut: ByteBuffer?,
    ): LongArray

    @JvmStatic external fun nativeSeek(
        handle: Long,
        timeUs: Long,
    )

    // Bounded zero-argument diagnostics; consumers must validate the versioned nine-scalar reply.
    @JvmStatic external fun nativeSandboxDiagnostics(): LongArray

    // Only a disposable debug-test worker probes this: Android may SIGSYS clone3 before returning errno.
    @JvmStatic external fun nativeSandboxClone3Errno(): Int

    @JvmStatic external fun nativeClose(handle: Long)
}
