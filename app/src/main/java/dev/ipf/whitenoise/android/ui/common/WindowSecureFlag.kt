package dev.ipf.whitenoise.android.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner

internal tailrec fun Context.lifecycleOwner(): LifecycleOwner? =
    when (this) {
        is LifecycleOwner -> this
        is ContextWrapper -> baseContext.lifecycleOwner()
        else -> null
    }

// Compose's `LocalContext.current` is whatever the host wired in — often
// the Activity directly, but themed/wrapped contexts (test surfaces, custom
// theme wrappers) return a `ContextWrapper`. A direct `as? Activity` cast on
// those silently yields null, which for a FLAG_SECURE setter is the worst
// failure mode (looks like it works, doesn't). Walk the wrapper chain.
internal tailrec fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

private val windowSecureFlagRefCounts = java.util.WeakHashMap<android.view.Window, Int>()

internal fun android.view.Window.retainSecureFlag() {
    synchronized(windowSecureFlagRefCounts) {
        val previous = windowSecureFlagRefCounts[this] ?: 0
        windowSecureFlagRefCounts[this] = previous + 1
        if (previous == 0) {
            setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
    }
}

internal fun android.view.Window.releaseSecureFlag() {
    synchronized(windowSecureFlagRefCounts) {
        val previous = windowSecureFlagRefCounts[this] ?: return
        if (previous <= 1) {
            windowSecureFlagRefCounts.remove(this)
            clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            windowSecureFlagRefCounts[this] = previous - 1
        }
    }
}

/**
 * Applies or clears `FLAG_SECURE` on the host activity window for the duration
 * of this composition. `FLAG_SECURE` blocks the OS Recents/overview thumbnail,
 * screenshots, screen recording, and casting from capturing the window's
 * contents. Identity / secret-key surfaces call this unconditionally; chat
 * surfaces pass the user's screenshot preference so the setting applies live.
 */
@Composable
internal fun WindowSecureFlag(enabled: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(context, enabled) {
        val window = context.activity()?.window
        if (enabled) {
            window?.retainSecureFlag()
        }
        onDispose {
            // No symmetric restore for enabled=false: this is the shared
            // activity window, so each secure surface must assert the flag for
            // itself instead of resurrecting stale state when a permissive
            // chat surface leaves composition.
            if (enabled) {
                window?.releaseSecureFlag()
            }
        }
    }
}
