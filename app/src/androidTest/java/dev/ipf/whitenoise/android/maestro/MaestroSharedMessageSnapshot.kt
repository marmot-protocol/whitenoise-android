package dev.ipf.whitenoise.android.maestro

internal data class MaestroSharedMessageSnapshot(
    val id: String,
    val text: String?,
    val deleted: Boolean,
    val edited: Boolean,
    val replyTo: String?,
)
