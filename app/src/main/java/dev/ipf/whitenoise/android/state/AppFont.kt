package dev.ipf.whitenoise.android.state

/**
 * App-wide font family (Settings -> Appearance -> App font). [displayName] is
 * the family's proper name, shown untranslated; the System entry is labeled
 * via resources at the call site.
 */
enum class AppFont(
    val preferenceValue: String,
    val displayName: String,
) {
    Manrope("manrope", "Manrope"),
    System("system", "System"),
    Outfit("outfit", "Outfit"),
    Urbanist("urbanist", "Urbanist"),
    Figtree("figtree", "Figtree"),
    ;

    companion object {
        fun fromPreference(value: String?): AppFont = entries.firstOrNull { it.preferenceValue == value } ?: Manrope
    }
}
