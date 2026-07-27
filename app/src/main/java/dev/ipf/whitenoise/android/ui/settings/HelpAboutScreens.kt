package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.core.net.toUri
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.WhiteNoiseUrls
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SettingsGroup

// Taps on the version row needed to reveal the hidden developer surface —
// the platform "you are now a developer" gesture, so non-devs never see it.
private const val DEV_UNLOCK_TAPS = 7

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HelpScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.help)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsGroup {
                    item {
                        SettingsRow(
                            title = stringResource(R.string.report_a_bug),
                            subtitle = stringResource(R.string.report_a_bug_subtitle),
                            icon = Icons.Filled.BugReport,
                            onClick = { openUrl(context, WhiteNoiseUrls.BUG_REPORT) },
                        )
                    }
                    item {
                        SettingsRow(
                            title = stringResource(R.string.about_and_licenses),
                            subtitle = stringResource(R.string.about_and_licenses_subtitle),
                            icon = Icons.Filled.Info,
                            onClick = onOpenAbout,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AboutScreen(
    appState: WhiteNoiseAppState,
    versionName: String,
    mdkShortSha: String,
    onBack: () -> Unit,
    onOpenDeveloper: () -> Unit,
) {
    val context = LocalContext.current
    var tapCount by remember { mutableIntStateOf(0) }
    val unlockedMessage = stringResource(R.string.developer_tools_unlocked)
    val alreadyOnMessage = stringResource(R.string.developer_tools_already_enabled)

    fun onVersionTap() {
        if (appState.developerMode) {
            Toast.makeText(context, alreadyOnMessage, Toast.LENGTH_SHORT).show()
            return
        }
        tapCount++
        if (tapCount >= DEV_UNLOCK_TAPS) {
            tapCount = 0
            appState.updateDeveloperMode(true)
            Toast.makeText(context, unlockedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_and_licenses)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                // Expressive reveal: when the gate unlocks, the group grows to
                // admit the Developer row along the theme's spatial spring.
                Column(Modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())) {
                    SettingsGroup {
                        item {
                            // Seven taps here unlock the developer gate.
                            SettingsRow(
                                title = stringResource(R.string.settings_version_label, versionName),
                                subtitle = stringResource(R.string.settings_mdk_version_label, mdkShortSha),
                                icon = Icons.Filled.Info,
                                onClick = { onVersionTap() },
                            )
                        }
                        item {
                            SettingsRow(
                                title = stringResource(R.string.open_source_licenses),
                                subtitle = stringResource(R.string.open_source_licenses_subtitle),
                                icon = Icons.Filled.Description,
                                onClick = { openLicenses(context) },
                            )
                        }
                        item {
                            SettingsRow(
                                title = stringResource(R.string.privacy_policy),
                                subtitle = stringResource(R.string.privacy_policy_subtitle),
                                icon = Icons.Filled.PrivacyTip,
                                onClick = { openUrl(context, WhiteNoiseUrls.PRIVACY_POLICY) },
                            )
                        }
                        if (appState.developerMode) {
                            item {
                                SettingsRow(
                                    title = stringResource(R.string.developer),
                                    subtitle = stringResource(R.string.developer_mode_subtitle),
                                    icon = Icons.Filled.Code,
                                    onClick = onOpenDeveloper,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openUrl(
    context: Context,
    url: String,
) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

private fun openLicenses(context: Context) {
    OssLicensesMenuActivity.setActivityTitle(context.getString(R.string.open_source_licenses))
    runCatching { context.startActivity(Intent(context, OssLicensesMenuActivity::class.java)) }
}
