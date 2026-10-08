package dev.ipf.whitenoise.android.audio.tts

/** Resolved display context belonging to one speech projection, not a profile cache. */
data class TtsMentionPresentation(
    val names: Map<String, String?>,
    val members: Map<String, Boolean>?,
) {
    fun displayName(key: String): String? = names[key]

    fun membershipResolver(): ((String) -> Boolean)? = members?.let { snapshot -> { key -> snapshot[key] == true } }
}
