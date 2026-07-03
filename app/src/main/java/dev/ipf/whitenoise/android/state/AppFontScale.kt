package dev.ipf.whitenoise.android.state

/**
 * In-app font-size step (Settings -> Appearance -> Font size, #403).
 * [factor] multiplies every Material typography sp size. Compose sp already
 * includes the OS font scale, so the effective text size composes with (never
 * replaces) the system setting: final = system_scale x app_scale.
 */
enum class AppFontScale(
    val preferenceValue: String,
    val factor: Float,
) {
    Small("small", 0.85f),
    Default("default", 1f),
    Large("large", 1.15f),
    ExtraLarge("extra_large", 1.3f),
    ;

    companion object {
        fun fromPreference(value: String?): AppFontScale = entries.firstOrNull { it.preferenceValue == value } ?: Default
    }
}
