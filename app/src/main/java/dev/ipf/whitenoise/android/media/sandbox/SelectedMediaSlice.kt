package dev.ipf.whitenoise.android.media.sandbox

import android.os.SystemClock
import android.system.Os
import java.io.FileDescriptor
import java.io.OutputStream

/** A provider subrange becomes its own descriptor capability, without exposing the surrounding file. */
internal fun copySelectedMediaSlice(
    input: FileDescriptor,
    offset: Long,
    length: Long,
    output: OutputStream,
): Boolean {
    val chunk = ByteArray(MediaDecodePolicy.STAGING_CHUNK_BYTES)
    val deadline = SystemClock.elapsedRealtime() + MediaDecodePolicy.DEADLINE_MS
    var copied = 0L
    while (copied < length && SystemClock.elapsedRealtime() <= deadline) {
        val count = Os.pread(input, chunk, 0, minOf(chunk.size.toLong(), length - copied).toInt(), offset + copied)
        if (count <= 0) return false
        output.write(chunk, 0, count)
        copied += count
    }
    return copied == length
}
