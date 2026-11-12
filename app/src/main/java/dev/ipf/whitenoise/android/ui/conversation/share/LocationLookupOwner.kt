package dev.ipf.whitenoise.android.ui.conversation.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Lets only the newest current-location lookup move the picker. A newer locate request or a manual
 * pan retires the older lookup, so a late fix can neither replace the user's selection nor clear
 * the progress of the request that replaced it (#3719).
 */
internal class LocationLookupOwner(
    private val scope: CoroutineScope,
    initiallyLocating: Boolean = false,
) {
    private var request = 0
    private var job: Job? = null

    /** Whether the current request is still looking up the device location. */
    var locating by mutableStateOf(initiallyLocating)
        private set

    /** Cancels the outstanding lookup, if any; whatever it returns later is ignored. */
    fun retire() {
        request += 1
        job?.cancel()
        job = null
        locating = false
    }

    /**
     * Starts a lookup that replaces any older one and hands a found location to [publish] while still
     * current. A lookup that throws counts as no fix, so its progress always ends.
     */
    fun start(
        lookup: suspend () -> SharedLocation?,
        publish: (SharedLocation) -> Unit,
    ) {
        retire()
        val owned = request
        locating = true
        job =
            scope.launch {
                val found = runCatchingCancellable { lookup() }.getOrNull()
                if (owned != request) return@launch
                found?.let(publish)
                locating = false
            }
    }
}
