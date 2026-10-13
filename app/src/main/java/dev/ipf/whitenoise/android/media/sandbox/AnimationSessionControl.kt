package dev.ipf.whitenoise.android.media.sandbox

/** Drawable lifecycle only; no compressed-source or native-code access is exposed to presentation. */
internal interface AnimationSessionControl : java.io.Closeable {
    fun play()

    fun pause()

    fun isOpen(): Boolean
}
