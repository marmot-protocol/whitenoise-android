package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Sign out as the last group: a destructive link that opens the production sign-out sheet. */
@Composable
@Suppress("FunctionNaming")
internal fun SignOutGroup(onSignOut: () -> Unit) {
    SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.Section)) {
        row("sign_out") { context ->
            SettingsLink(
                context = context,
                title = stringResource(R.string.sign_out),
                explanation = stringResource(R.string.explanation_account_keys_sign_out),
                onClick = onSignOut,
                destructive = true,
                leading = {
                    SettingsLeadingIcon(
                        R.drawable.ic_settings_logout,
                        tint = MaterialTheme.colorScheme.error,
                        testTag = "settings.icon.sign_out",
                    )
                },
            )
        }
    }
}
