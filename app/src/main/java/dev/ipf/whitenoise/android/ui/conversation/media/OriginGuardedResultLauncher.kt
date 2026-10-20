package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Starts one external request that remembers which account/conversation asked for it. */
internal fun interface OriginGuardedResultLauncher<I> {
    /** Records the current origin, then hands [input] to the platform launcher. */
    fun launch(input: I)
}

/**
 * Wraps a platform result launcher so a result reaches [onAccepted] only for the request's original
 * [origin] while [isCurrent] still holds. The origin is saved with the launcher's registry key, so the
 * protected shell's saved-state provider carries it across lock-driven disposal and Activity
 * recreation. A result after an account/runtime/chat change, or a duplicate delivery, is dropped.
 */
@Composable
internal fun <I, O> rememberOriginGuardedResultLauncher(
    contract: ActivityResultContract<I, O>,
    origin: String,
    isCurrent: () -> Boolean,
    onAccepted: (O) -> Unit,
): OriginGuardedResultLauncher<I> {
    var requestOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    val currentOrigin by rememberUpdatedState(origin)
    val launcher =
        rememberLauncherForActivityResult(contract) { result ->
            val owner = requestOrigin
            // Claim before delivery: a duplicate result cannot be accepted twice.
            requestOrigin = null
            if (owner != null && owner == currentOrigin && isCurrent()) onAccepted(result)
        }
    return remember(launcher) {
        OriginGuardedResultLauncher { input ->
            requestOrigin = currentOrigin
            var launched = false
            try {
                launcher.launch(input)
                launched = true
            } finally {
                // A picker that never started cannot answer, so its origin must not linger.
                if (!launched) requestOrigin = null
            }
        }
    }
}
