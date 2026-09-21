package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.whitenoise.android.state.previewExpired
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

private const val CLOCK_RECHECK_SECONDS = 60uL
private const val MILLIS_PER_SECOND = 1_000uL

/** Owns a visible row's deadline timer and recomputes from wall time every foreground transition. */
@Composable
internal fun rememberChatPreviewExpired(preview: ChatListMessagePreviewFfi?): State<Boolean> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(preview.previewExpired(unixSeconds()), preview, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = unixSeconds()
                value = preview.previewExpired(now)
                val expiry = preview?.retentionExpiresAt
                if (preview?.retentionSeconds == null || preview.retentionSeconds == 0uL || expiry == null) {
                    awaitCancellation()
                }
                // Bounded periodic checks also handle wall-clock adjustments without overflowing milliseconds.
                val remaining = if (expiry > now) expiry - now else CLOCK_RECHECK_SECONDS
                delay((minOf(remaining, CLOCK_RECHECK_SECONDS) * MILLIS_PER_SECOND).toLong())
            }
        }
    }
}

/** Clamps pre-epoch clocks rather than interpreting a negative timestamp as a far-future unsigned value. */
private fun unixSeconds(): ULong = (System.currentTimeMillis().coerceAtLeast(0) / MILLIS_PER_SECOND.toLong()).toULong()
