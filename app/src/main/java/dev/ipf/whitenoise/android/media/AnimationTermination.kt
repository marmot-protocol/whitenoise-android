package dev.ipf.whitenoise.android.media

/** A session's first terminal reason; no outcome is permission to release a live worker. */
internal enum class AnimationTermination {
    Preempted,
    TransportUnavailable,
    WorkerLostUnknown,
    CodecRejected,
    ProtocolRejected,
    OwnerClosed,
}

/** Only classified transport or unknown worker loss may spend the owner's finite fault allowance. */
internal class AnimationStartupException(
    val reason: AnimationTermination,
) : Exception("animation startup: $reason") {
    init {
        require(reason == AnimationTermination.TransportUnavailable || reason == AnimationTermination.WorkerLostUnknown)
    }
}
