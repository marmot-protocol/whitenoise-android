package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.RememberObserver

/**
 * Cleanup for remembered values that begin work in their constructor. An
 * abandoned composition never runs DisposableEffect, so a value whose teardown
 * lives there would leak — Compose calls exactly one of [onRemembered] or
 * [onAbandoned], and the committed lifecycle stays owned by the route's
 * DisposableEffect, so this guard cleans up only the never-committed case.
 */
internal class RememberAbandonmentGuard<T>(
    val value: T,
    private val onAbandonedCleanup: (T) -> Unit,
) : RememberObserver {
    override fun onRemembered() = Unit

    override fun onForgotten() = Unit

    override fun onAbandoned() {
        onAbandonedCleanup(value)
    }
}
