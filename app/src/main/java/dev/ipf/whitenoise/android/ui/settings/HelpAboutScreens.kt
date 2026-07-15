package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.oss.licenses.v2.OssLicensesMenuActivity
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.shouldUnlockDeveloperMode

private const val REPORT_BUG_URL =
    "https://github.com/marmot-protocol/whitenoise-android/issues/new?" +
        "labels=bug&" +
        "title=%5BBug%5D%3A%20&" +
        "body=%23%23%20Bug%20description%0A%0A%23%23%20Steps%20to%20reproduce%0A1.%20%0A%0A" +
        "%23%23%20Expected%20behavior%0A%0A%23%23%20Device%20and%20app%20version%0A"
private const val PRIVACY_POLICY_URL = "https://www.whitenoise.chat/privacy"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HelpScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    SettingsListScaffold(title = stringResource(R.string.help), onBack = onBack) {
        item {
            SettingsCardRow(
                title = stringResource(R.string.report_a_bug),
                subtitle = stringResource(R.string.report_a_bug_subtitle),
                onClick = { context.openExternalUrl(REPORT_BUG_URL) },
            )
        }
        item {
            SettingsCardRow(
                title = stringResource(R.string.about_and_licenses),
                subtitle = stringResource(R.string.about_and_licenses_subtitle),
                onClick = onOpenAbout,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var versionTapCount by remember { mutableIntStateOf(0) }

    SettingsListScaffold(title = stringResource(R.string.about_and_licenses), onBack = onBack) {
        item {
            SettingsCardRow(
                title = stringResource(R.string.settings_version_label, BuildConfig.VERSION_NAME),
                subtitle = stringResource(R.string.settings_marmotkit_sha_label, BuildConfig.MDK_SHORT_SHA),
                onClick = {
                    if (!appState.developerMode) {
                        versionTapCount += 1
                        if (shouldUnlockDeveloperMode(versionTapCount)) {
                            appState.updateDeveloperMode(true)
                            appState.present(R.string.developer_tools_unlocked)
                            versionTapCount = 0
                        }
                    }
                },
            )
        }
        item {
            SettingsCardRow(
                title = stringResource(R.string.open_source_licenses),
                subtitle = stringResource(R.string.open_source_licenses_subtitle),
                onClick = { context.openOpenSourceLicenses() },
            )
        }
        item {
            SettingsCardRow(
                title = stringResource(R.string.privacy_policy),
                subtitle = stringResource(R.string.privacy_policy_subtitle),
                onClick = { context.openExternalUrl(PRIVACY_POLICY_URL) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsListScaffold(
    title: String,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

private fun Context.openExternalUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun Context.openOpenSourceLicenses() {
    OssLicensesMenuActivity.setActivityTitle(getString(R.string.open_source_licenses))
    startActivity(Intent(this, OssLicensesMenuActivity::class.java))
}
