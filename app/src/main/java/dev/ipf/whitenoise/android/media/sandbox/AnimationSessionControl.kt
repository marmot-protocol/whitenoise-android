package dev.ipf.whitenoise.android.media.sandbox

/** Drawable lifecycle only; no compressed-source or native-code access is exposed to presentation. */
internal interface AnimationSessionControl : java.io.Closeable {
    /** Explicit-viewer retirement only; null for controls without resumable decorative playback. */
    val preempted: kotlinx.coroutines.flow.StateFlow<Boolean>?
        get() = null

    /** First terminal reason is retained even when no drawable was delivered yet. */
    val termination: kotlinx.coroutines.flow.StateFlow<dev.ipf.whitenoise.android.media.AnimationTermination?>?
        get() = null

    /** Requests bounded frame delivery from the already-admitted isolated worker. */
    fun play()

    /** Stops future frame requests without releasing admission or the displayed first-frame fallback. */
    fun pause()

    /** False once this capability is retired, even if the worker's death acknowledgement is pending. */
    fun isOpen(): Boolean
}
