package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Saved UI interruption evidence only; never stores bytes, retries an upload or grants an old owner. */
internal class AvatarPreparationRecovery(
    val accepted: () -> Unit,
    val settled: () -> Unit,
)

/**
 * Remembers whether an accepted avatar crop was still preparing when state was saved, and reports the interruption once
 * on restore — only while [isCurrent] still owns the surface.
 */
@Composable
internal fun rememberAvatarPreparationRecovery(
    origin: String,
    isCurrent: () -> Boolean,
    onInterrupted: () -> Unit,
): AvatarPreparationRecovery {
    var pending by rememberSaveable(origin) { mutableStateOf(false) }
    val restoredPending = remember(origin) { pending }
    LaunchedEffect(origin) {
        if (restoredPending && pending) {
            pending = false
            if (isCurrent()) onInterrupted()
        }
    }
    return remember(origin) {
        AvatarPreparationRecovery(accepted = { pending = true }, settled = { pending = false })
    }
}
