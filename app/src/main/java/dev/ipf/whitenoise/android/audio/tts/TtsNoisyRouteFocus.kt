package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.whitenoise.android.audio.NoisyAudioRouteListener
import dev.ipf.whitenoise.android.audio.NoisyAudioRouteRegistrar

/**
 * Read-aloud holds its focus from a start request until pause or session end, in both focus modes
 * (media mix grants without a platform request). A headphone-disconnect listener follows that hold:
 * a granted request arms it, while a refused request or any release disarms it.
 */
internal class TtsNoisyRouteFocus(
    private val focus: TtsAudioFocus,
    registrar: NoisyAudioRouteRegistrar,
    onNoisy: () -> Unit,
) : TtsAudioFocus {
    private val listener = NoisyAudioRouteListener({ registrar }, onNoisy)

    /** True while a listener is registered; exposed for lifetime tests. */
    val listening: Boolean get() = listener.armed

    override fun acquire(
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean = focus.acquire(onFocusLoss, onOwnerSurrender).also(::followGrant)

    override fun acquire(
        mode: TtsAudioFocusMode,
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean = focus.acquire(mode, onFocusLoss, onOwnerSurrender).also(::followGrant)

    override fun release() {
        listener.disarm()
        focus.release()
    }

    private fun followGrant(granted: Boolean) {
        if (granted) listener.arm() else listener.disarm()
    }
}
