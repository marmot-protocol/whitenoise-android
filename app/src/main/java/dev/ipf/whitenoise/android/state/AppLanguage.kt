package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

internal const val APP_LANGUAGE_PREFERENCES_NAME = "whitenoise"
internal const val APP_LANGUAGE_TAG_KEY = "language_tag"

internal fun persistedApplicationLanguageTag(context: Context): String =
    context
        .getSharedPreferences(APP_LANGUAGE_PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getString(APP_LANGUAGE_TAG_KEY, null)
        .orEmpty()
        .trim()

internal fun applyApplicationLanguageTag(tag: String) {
    val normalized = tag.trim()
    if (AppCompatDelegate.getApplicationLocales().toLanguageTags() == normalized) return

    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalized))
}
