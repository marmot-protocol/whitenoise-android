package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ipf.whitenoise.android.media.holdCacheFileInUse
import dev.ipf.whitenoise.android.media.releaseCacheFileInUse
import java.io.File

/** Exact capture-file leases owned by the retained shell, independent of a mounted composer. */
internal class PendingCameraCaptureFiles {
    private data class Capture(
        val file: File,
        val uri: Uri,
        val origin: String,
    )

    private val captures = mutableMapOf<String, Capture>()
    private var owner: String? = null

    /** A live account/runtime replacement retires only this owner's pending captures. */
    fun retainOwner(currentOwner: String) {
        if (owner != null && owner != currentOwner) releaseAll()
        owner = currentOwner
    }

    /** Idempotent across shell remounts and saved-state restoration in the same process. */
    fun hold(
        file: File,
        uri: Uri,
        origin: String,
    ) {
        if (captures.putIfAbsent(file.absolutePath, Capture(file, uri, origin)) == null) holdCacheFileInUse(file)
    }

    /** Transfer happens only after native staging owns a durable readable copy of this exact source. */
    fun releasePersisted(uri: Uri) {
        captures.values
            .filter { it.uri == uri }
            .map { it.file }
            .forEach(::release)
    }

    /** Committed navigation retirement cannot be confused with temporary lock/activity disposal. */
    fun releaseOrigin(origin: String) {
        captures.values.filter { it.origin == origin }.map { it.file }.forEach { file ->
            release(file)
            file.delete()
        }
    }

    /** Explicit shelf removal abandons only that capture's private output. */
    fun discard(uri: Uri) {
        captures.values.filter { it.uri == uri }.map { it.file }.forEach { file ->
            release(file)
            file.delete()
        }
    }

    /** Ends one capture's hold on cancellation, failed launch, or native staging handoff. */
    fun release(file: File) {
        if (captures.remove(file.absolutePath) != null) releaseCacheFileInUse(file)
    }

    /** Retired requests can no longer publish; remove only their owned private outputs. */
    fun releaseAll() {
        captures.values.toList().forEach { capture ->
            release(capture.file)
            capture.file.delete()
        }
    }
}

internal val LocalPendingCameraCaptureFiles = staticCompositionLocalOf<PendingCameraCaptureFiles?> { null }
