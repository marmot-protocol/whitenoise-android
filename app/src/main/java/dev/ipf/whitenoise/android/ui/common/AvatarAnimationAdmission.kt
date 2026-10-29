package dev.ipf.whitenoise.android.ui.common

import android.graphics.drawable.Drawable
import dev.ipf.whitenoise.android.media.AnimationAdmissionBusyException
import dev.ipf.whitenoise.android.media.AnimationStartupException
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only transient worker contention parks playback; unsupported/corrupt sources keep static fallback. */
internal suspend fun decodeAvatarWhenAvailable(decode: suspend () -> Drawable?): Drawable? {
    while (true) {
        val attempt = runCatchingCancellable { decode() }
        val failure = attempt.exceptionOrNull()
        if (failure is AnimationStartupException) throw failure
        val busy = failure as? AnimationAdmissionBusyException
        if (busy == null) return attempt.getOrNull()
        busy.awaitAvailability()
        currentCoroutineContext().ensureActive()
    }
}
