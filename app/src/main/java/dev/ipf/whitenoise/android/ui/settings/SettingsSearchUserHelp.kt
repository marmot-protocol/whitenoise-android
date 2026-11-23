package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail

internal fun userHelpSearchEntries(): List<SettingsSearchEntry> =
    listOf(
        hubEntry(
            "home.LearnWhiteNoise",
            R.string.learn_white_noise,
            R.string.settings_help_support,
            SettingsDetail.LearnWhiteNoise,
        ),
    )
