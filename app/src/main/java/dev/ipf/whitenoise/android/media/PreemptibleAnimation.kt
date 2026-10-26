package dev.ipf.whitenoise.android.media

/** A decorative drawable can resume after yielding to an explicit viewer, but not after codec failure. */
internal interface PreemptibleAnimation {
    /** Suspends until interactive playback retires this session; ordinary failure does not trigger retries. */
    suspend fun awaitPreemption()
}
