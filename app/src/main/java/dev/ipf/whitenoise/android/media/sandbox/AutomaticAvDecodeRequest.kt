package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import dev.ipf.whitenoise.android.BuildConfig
import java.io.File

/** Constructs the bounded operation fields and selected descriptor capabilities for automatic decoding. */
internal fun automaticAvDecodeRequest(
    context: Context,
    input: ParcelFileDescriptor,
    output: SharedMemory,
    request: MediaDecodeRequest,
): Bundle =
    Bundle().apply {
        if (BuildConfig.DEBUG) {
            val canary = File(context.filesDir, "media-containment-canary")
            if (canary.isFile) putString("canaryPath", canary.absolutePath)
        }
        putInt("version", MediaDecodePolicy.VERSION)
        putInt("operation", request.operation)
        putInt("edge", request.edge)
        putBoolean("oriented", request.option)
        putLong("positionUs", request.positionUs)
        putBoolean("playbackColors", request.playbackColors)
        putLong("offset", request.offset)
        putLong("length", request.length ?: input.statSize)
        request.region?.let {
            putInt("left", it.left)
            putInt("top", it.top)
            putInt("right", it.right)
            putInt("bottom", it.bottom)
        }
        putParcelable("input", input)
        putParcelable("output", output)
    }
