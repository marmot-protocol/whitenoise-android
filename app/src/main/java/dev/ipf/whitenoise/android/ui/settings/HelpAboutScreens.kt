package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.WhiteNoiseUrls
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton

/** Help: the bug report, reviewed before it leaves the app, and the build's own facts. */
@Suppress("FunctionNaming")
@Composable
internal fun HelpScreen(
    onBack: () -> Unit,
    onOpenBugReport: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.help), onBack = onBack) {
        SettingsList {
            item {
                SettingsGroup(modifier = Modifier.testTag("help.destinations")) {
                    row("bug_report") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.report_a_bug),
                            onClick = onOpenBugReport,
                            modifier = Modifier.testTag("help.report_bug"),
                            subtitle = stringResource(R.string.report_a_bug_subtitle),
                            leading = { HelpLeadingIcon(R.drawable.ic_bug_report) },
                        )
                    }
                    row("about") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.about_and_licenses),
                            onClick = onOpenAbout,
                            modifier = Modifier.testTag("help.about"),
                            subtitle = stringResource(R.string.about_and_licenses_subtitle),
                            leading = { HelpLeadingIcon(R.drawable.ic_info) },
                        )
                    }
                }
            }
        }
    }
}

/** Report a bug over the shipped issue form, opened only from this screen's own action. */
@Suppress("FunctionNaming")
@Composable
internal fun BugReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BugReportContent(onBack = onBack, onOpenReport = { openHelpUrl(context, WhiteNoiseUrls.BUG_REPORT) })
}

/**
 * What the hand-off does and what it does not attach, then one action. [onOpenReport] reports whether a
 * browser accepted it; a refusal keeps the screen and offers Retry.
 */
@Suppress("FunctionNaming")
@Composable
internal fun BugReportContent(
    onBack: () -> Unit,
    onOpenReport: () -> Boolean,
) {
    var openFailed by rememberSaveable { mutableStateOf(false) }

    fun openReport() {
        openFailed = !onOpenReport()
    }

    SettingsScaffold(
        title = stringResource(R.string.report_a_bug),
        onBack = onBack,
        bottomBar = {
            SettingsBottomAction {
                WhiteNoiseButton(
                    onClick = ::openReport,
                    modifier = Modifier.fillMaxWidth().testTag("help.bug.open"),
                ) { Text(stringResource(R.string.report_bug_open_github)) }
            }
        },
    ) {
        SettingsList {
            item { SettingsSection(stringResource(R.string.report_bug_destination_section)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("help.bug.destination")) {
                    row("destination") { context ->
                        SettingsValue(
                            context = context,
                            title = stringResource(R.string.report_bug_destination),
                            value = stringResource(R.string.report_bug_destination_detail),
                        )
                    }
                }
            }
            item { SettingsSection(stringResource(R.string.report_bug_privacy_section)) }
            item {
                SettingsCallout(
                    text = stringResource(R.string.report_bug_no_attachments_detail),
                    modifier = Modifier.testTag("help.bug.privacy"),
                    title = stringResource(R.string.report_bug_no_attachments_title),
                    leading = { HelpLeadingIcon(R.drawable.ic_settings_front_hand) },
                )
            }
            item {
                SettingsCallout(
                    text = stringResource(R.string.report_bug_public_reminder),
                    modifier = Modifier.testTag("help.bug.public"),
                    icon = R.drawable.ic_warning,
                )
            }
        }
    }
    if (openFailed) {
        HelpOpenFailureDialog(
            title = stringResource(R.string.report_bug_open_failed_title),
            body = stringResource(R.string.report_bug_open_failed_detail),
            onRetry = ::openReport,
            onDismiss = { openFailed = false },
        )
    }
}

/** About & licenses: the installed build, then the bundled notices and the privacy policy. */
@Suppress("FunctionNaming")
@Composable
internal fun AboutScreen(
    versionName: String,
    buildNumber: String,
    mdkShortSha: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    AboutContent(
        versionName = versionName,
        buildNumber = buildNumber,
        mdkShortSha = mdkShortSha,
        onBack = onBack,
        onOpenLicenses = { openSourceLicenses(context) },
        onOpenPrivacy = { openHelpUrl(context, WhiteNoiseUrls.PRIVACY_POLICY) },
    )
}

/** The list itself: build facts are read from the package, and each hand-off states its own failure. */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
@Composable
internal fun AboutContent(
    versionName: String,
    buildNumber: String,
    mdkShortSha: String,
    onBack: () -> Unit,
    onOpenLicenses: () -> Boolean,
    onOpenPrivacy: () -> Boolean,
) {
    var failure by rememberSaveable { mutableStateOf<AboutOpenFailure?>(null) }

    fun showLicenses() {
        failure = if (onOpenLicenses()) null else AboutOpenFailure.Licenses
    }

    fun openPrivacy() {
        failure = if (onOpenPrivacy()) null else AboutOpenFailure.Privacy
    }

    SettingsScaffold(title = stringResource(R.string.about_and_licenses), onBack = onBack) {
        SettingsList {
            item { SettingsSection(stringResource(R.string.about_app_section)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("about.app")) {
                    row("version") { context ->
                        SettingsValue(context, stringResource(R.string.about_version), versionName)
                    }
                    row("build") { context ->
                        SettingsValue(context, stringResource(R.string.about_build), buildNumber)
                    }
                    row("mdk") { context ->
                        SettingsValue(context, stringResource(R.string.about_mdk), mdkShortSha)
                    }
                }
            }
            item { SettingsSection(stringResource(R.string.about_legal_section)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("about.legal")) {
                    row("licenses") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.open_source_licenses),
                            onClick = ::showLicenses,
                            modifier = Modifier.testTag("about.licenses"),
                            subtitle = stringResource(R.string.open_source_licenses_subtitle),
                            leading = { HelpLeadingIcon(R.drawable.ic_description) },
                        )
                    }
                    row("privacy") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.privacy_policy),
                            onClick = ::openPrivacy,
                            modifier = Modifier.testTag("about.privacy_policy"),
                            subtitle = stringResource(R.string.privacy_policy_subtitle),
                            leading = { HelpLeadingIcon(R.drawable.ic_settings_front_hand) },
                        )
                    }
                }
            }
        }
    }
    failure?.let { unavailable ->
        HelpOpenFailureDialog(
            title = stringResource(unavailable.titleRes),
            body = stringResource(R.string.external_open_failed_detail),
            onRetry = if (unavailable == AboutOpenFailure.Licenses) ::showLicenses else ::openPrivacy,
            onDismiss = { failure = null },
        )
    }
}

/** Which About hand-off was refused, so Retry repeats that one and nothing else. */
internal enum class AboutOpenFailure(
    val titleRes: Int,
) {
    Licenses(R.string.licenses_open_failed_title),
    Privacy(R.string.privacy_policy_open_failed_title),
}

/** A refused hand-off keeps the screen: Retry repeats the same reviewed action, Cancel closes. */
@Suppress("FunctionNaming")
@Composable
private fun HelpOpenFailureDialog(
    title: String,
    body: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onRetry()
                },
            ) { Text(stringResource(R.string.retry)) }
        },
        modifier = Modifier.testTag("help.open_failed"),
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(title) },
        text = { Text(body) },
    )
}

/** Decorative: the row and the callout already carry their own visible names. */
@Suppress("FunctionNaming")
@Composable
private fun HelpLeadingIcon(
    @DrawableRes icon: Int,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = Modifier.size(HelpLeadingIconSize),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Reports whether a browser accepted the hand-off; no app-owned data is placed in the intent. */
private fun openHelpUrl(
    context: Context,
    url: String,
): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    return runCatching { context.startActivity(intent) }.isSuccess
}

/** Google's generated notice activity, titled as the row that opens it. */
private fun openSourceLicenses(context: Context): Boolean {
    OssLicensesMenuActivity.setActivityTitle(context.getString(R.string.open_source_licenses))
    return runCatching { context.startActivity(Intent(context, OssLicensesMenuActivity::class.java)) }.isSuccess
}

private val HelpLeadingIconSize = 24.dp
