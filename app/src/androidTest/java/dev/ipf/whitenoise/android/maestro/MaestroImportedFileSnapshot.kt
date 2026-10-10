package dev.ipf.whitenoise.android.maestro

import android.net.Uri

/** Expected provider metadata and exact private copy identity; the three source bytes are checked separately. */
internal data class MaestroImportedFileSnapshot(
    val uri: Uri,
    val name: String,
    val mime: String,
)
