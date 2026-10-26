package dev.ipf.whitenoise.android.media

/** Decorative playback observes retirement without granting access to the compressed source or worker. */
internal interface PreemptibleAnimation {
    /** Compatibility for existing preemption-only controls and injected drawables. */
    suspend fun awaitPreemption()

    /** Production sessions report first-reason termination; older controls report takeover only. */
    suspend fun awaitTermination(): AnimationTermination {
        awaitPreemption()
        return AnimationTermination.Preempted
    }
}
