package dev.ipf.whitenoise.android.ui.conversation.composer

import android.window.OnBackInvokedCallback

internal fun interface ComposerOverlayBackRegistrar {
    fun register(
        priority: Int,
        callback: OnBackInvokedCallback,
    ): () -> Unit
}
