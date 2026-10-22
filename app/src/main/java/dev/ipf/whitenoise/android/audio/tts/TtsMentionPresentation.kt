package dev.ipf.whitenoise.android.audio.tts

/** Resolved display context belonging to one speech projection, not a profile cache. */
data class TtsMentionPresentation(
    val names: Map<String, String?>,
) {
    /** Resolves only the names frozen for this speech projection. */
    fun displayName(key: String): String? = names[key]
}
