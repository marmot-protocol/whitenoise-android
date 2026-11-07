package dev.ipf.whitenoise.android.media.sandbox

import android.os.Process
import java.nio.ByteBuffer

/** JNI is loaded only by permissionless workers; the host receives raw bounded outputs. */
internal object NativeMediaEngine {
    private var loaded = false

    /** Loads native parsers only under an isolated UID; the app process must never initialize them. */
    @Synchronized
    fun ensureLoaded() {
        check(Process.isIsolated()) { "Native media codecs require an isolated UID" }
        if (!loaded) {
            System.loadLibrary("media_decoder")
            loaded = true
        }
    }

    /** Positive values own a native session; -1 is explicit container invalid-data; zero is no session.
     * Other refusals throw inside the isolated worker. No parser error text crosses IPC.
     */
    @JvmStatic external fun nativeOpen(
        fd: Int,
        offset: Long,
        length: Long,
        edge: Int,
    ): Long

    /** Returns fixed bounded metadata for a positive generation handle; no encoded bytes leave JNI. */
    @JvmStatic external fun nativeInfo(handle: Long): LongArray

    /** Copies bounded raw output; exactly [-1] reports demuxer INVALIDDATA, all other refusals throw. */
    @JvmStatic external fun nativeRead(
        handle: Long,
        videoOut: ByteBuffer?,
        audioOut: ByteBuffer?,
    ): LongArray

    /** Reads one raw track with the same [-1] demuxer marker; no compressed packets leave JNI. */
    @JvmStatic external fun nativeReadPreferred(
        handle: Long,
        videoOut: ByteBuffer?,
        audioOut: ByteBuffer?,
        preferred: Int,
    ): LongArray

    /** Seeks within the retained native generation; it does not acquire a new source capability. */
    @JvmStatic external fun nativeSeek(
        handle: Long,
        timeUs: Long,
    )

    // Bounded zero-argument diagnostics; consumers must validate the versioned nine-scalar reply.

    /** Returns the fixed versioned restriction diagnostics vector for explicit qualification tests. */
    @JvmStatic external fun nativeSandboxDiagnostics(): LongArray

    // Only a disposable debug-test worker probes this: Android may SIGSYS clone3 before returning errno.

    /** Probes clone3 only in a disposable test worker because platform policy may terminate it. */
    @JvmStatic external fun nativeSandboxClone3Errno(): Int

    /** Releases only the matching native registry generation; stale handles cannot close another session. */
    @JvmStatic external fun nativeClose(handle: Long)
}
