package dev.ipf.darkmatter.state

enum class AppThemeMode(
    val preferenceValue: String,
) {
    System("system"),
    Light("light"),
    Dark("dark"),
    Amoled("amoled"),
    ;

    fun resolveDarkTheme(systemDarkTheme: Boolean): Boolean =
        when (this) {
            System -> systemDarkTheme
            Light -> false
            Dark -> true
            Amoled -> true
        }

    val isAmoled: Boolean
        get() = this == Amoled

    companion object {
        fun fromPreference(value: String?): AppThemeMode = entries.firstOrNull { it.preferenceValue == value } ?: System
    }
}
