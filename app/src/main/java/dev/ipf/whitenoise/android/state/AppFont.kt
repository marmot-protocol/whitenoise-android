package dev.ipf.whitenoise.android.state

/**
 * App-wide font family (Settings -> Appearance -> App font). [displayName] is
 * the family's proper name, shown untranslated; the System entry is labeled
 * via resources at the call site. The system face is the default; a saved
 * choice of any bundled family is honoured as before.
 */
enum class AppFont(
    val preferenceValue: String,
    val displayName: String,
) {
    System("system", "System"),
    Manrope("manrope", "Manrope"),
    Outfit("outfit", "Outfit"),
    Urbanist("urbanist", "Urbanist"),
    Figtree("figtree", "Figtree"),
    ;

    companion object {
        fun fromPreference(value: String?): AppFont = entries.firstOrNull { it.preferenceValue == value } ?: System
    }
}
