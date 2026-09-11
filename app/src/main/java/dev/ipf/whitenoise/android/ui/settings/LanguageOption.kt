package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R

/** One selectable app language: the BCP 47 tag the app applies and its localized label. */
internal data class LanguageOption(
    val tag: String,
    @param:StringRes val labelRes: Int,
)

/** The languages the app ships resources for; the empty tag follows the device. */
internal val languageOptions =
    listOf(
        LanguageOption("", R.string.language_system),
        LanguageOption("en", R.string.language_english),
        LanguageOption("de", R.string.language_german),
        LanguageOption("es", R.string.language_spanish),
        LanguageOption("fr", R.string.language_french),
        LanguageOption("it", R.string.language_italian),
        LanguageOption("pt", R.string.language_portuguese),
        LanguageOption("ru", R.string.language_russian),
        LanguageOption("tr", R.string.language_turkish),
        LanguageOption("zh", R.string.language_chinese_simplified),
        LanguageOption("zh-Hant", R.string.language_chinese_traditional),
    )
