package dev.ipf.whitenoise.android.ui.conversation.media

import kotlinx.coroutines.CancellationException

internal enum class AttachmentCacheState {
    Resolving,
    Cached,
    Missing,
}

internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
