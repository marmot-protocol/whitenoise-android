package dev.ipf.whitenoise.android.maestro

/** Identity read from the genuine native row before Maestro can mutate the message. */
internal data class MaestroMessageBaseline(
    val account: String,
    val messageId: String,
    val group: String,
    val peer: String,
)
