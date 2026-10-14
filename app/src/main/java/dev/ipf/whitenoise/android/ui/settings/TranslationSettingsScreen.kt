@file:Suppress("FunctionNaming") // Unit-returning Compose controls use the established PascalCase convention.

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.translation.TranslationGlobalSettingsContent
import dev.ipf.whitenoise.android.translation.rememberTranslationPreferences

@Composable
internal fun TranslationSettingsScreen(onBack: () -> Unit) {
    val preferences = rememberTranslationPreferences()
    SettingsScaffold(title = stringResource(R.string.translation_title), onBack = onBack) {
        SettingsList { item { TranslationGlobalSettingsContent(preferences) } }
    }
}
