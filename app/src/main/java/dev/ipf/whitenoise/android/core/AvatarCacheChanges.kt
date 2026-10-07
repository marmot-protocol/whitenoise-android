package dev.ipf.whitenoise.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opaque decoded-pixel publication signal, with no image bytes or protocol identity retained. */
internal object AvatarCacheChanges {
    private val changes = MutableStateFlow(Any())
    val revision = changes.asStateFlow()

    /** Presentation consumers can revisit cached pixels; this signal never schedules acquisition. */
    fun published() {
        changes.value = Any()
    }
}
