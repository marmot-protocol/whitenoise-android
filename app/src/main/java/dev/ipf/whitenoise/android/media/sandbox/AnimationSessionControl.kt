package dev.ipf.whitenoise.android.media.sandbox

/** Drawable lifecycle only; no compressed-source or native-code access is exposed to presentation. */
internal interface AnimationSessionControl : java.io.Closeable {
    /** Explicit-viewer retirement only; null for controls without resumable decorative playback. */
    val preempted: kotlinx.coroutines.flow.StateFlow<Boolean>?
        get() = null

    fun play()

    fun pause()

    fun isOpen(): Boolean
}
