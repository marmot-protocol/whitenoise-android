@file:Suppress("MatchingDeclarationName") // The screen owns its small presentation state declaration.

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ReviewDemoProblem
import dev.ipf.whitenoise.android.state.ReviewDemoStage
import dev.ipf.whitenoise.android.state.ReviewDemoStatus
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.theme.PillShape
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.launch

/** The build this copy of White Noise was made from, as the Developer and About surfaces report it. */
internal data class DeveloperBuildFacts(
    val versionName: String,
    val buildNumber: String,
    val mdkShortSha: String,
    val staging: Boolean = false,
)

/** The installed variant's facts; the staging resource overlay remains authoritative for its badge. */
internal fun developerBuildFacts(staging: Boolean): DeveloperBuildFacts {
    val buildNumber = BuildConfig.VERSION_CODE.toString()
    return DeveloperBuildFacts(BuildConfig.VERSION_NAME, buildNumber, BuildConfig.MDK_SHORT_SHA, staging)
}

/**
 * Developer Tools: the warning, the switch that owns developer mode, Key Packages as a recovery destination,
 * the debugging surfaces the switch reveals, and the build this app was made from. Telemetry and audit logs
 * stay in Privacy & Security — they are privacy controls, not developer tools.
 */
@Suppress("FunctionNaming")
@Composable
internal fun DeveloperScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenKeyPackages: () -> Unit,
    onOpenDemoChat: (ChatListItem) -> Unit = {},
) {
    val demo = appState.appReviewDemo
    val scope = rememberCoroutineScope()
    val openDemo: (String, String) -> Unit = { account, group ->
        scope.launch {
            val previous = appState.activeAccountRef
            val generation = appState.runtimeGeneration
            val activated =
                previous == account ||
                    appState.setActiveAccount(account, shouldActivate = {
                        appState.runtimeGeneration == generation && appState.activeAccountRef == previous
                    })
            if (activated && appState.activeAccountRef == account) {
                runCatchingCancellable { appState.preloadNotificationChatListItem(account, group) }
                    .onSuccess(onOpenDemoChat)
                    .onFailure { demo.reportOpenFailure() }
            } else {
                demo.reportOpenFailure()
            }
        }
    }
    var seedDialogOpen by remember { mutableStateOf(false) }
    DeveloperContent(
        developerMode = appState.developerMode,
        streamingDebug = appState.streamingDebugMode,
        build = developerBuildFacts(booleanResource(R.bool.staging_build)),
        onDeveloperModeChange = { appState.updateDeveloperMode(it) },
        onStreamingDebugChange = { appState.updateStreamingDebugMode(it) },
        onBack = onBack,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenKeyPackages = onOpenKeyPackages,
        demoStatus = demo.status,
        demoAvailable = demo.canBegin,
        demoHasSavedSetup = demo.hasSavedSetup,
        onStartDemo = { demo.start(openDemo) },
        onOpenDemo = { ready -> openDemo(ready.accountRef, ready.groupId) },
        onClearDemo = demo::clearSavedSetup,
        // Seeding sends real messages, so only a debuggable build offers it.
        onSeedConversationFixture = if (BuildConfig.DEBUG) ({ seedDialogOpen = true }) else null,
    )
    if (seedDialogOpen) {
        ConversationFixtureSeedDialog(appState = appState, onDismiss = { seedDialogOpen = false })
    }
}

/**
 * The list itself. Debugging appears only while developer mode is on; Key Packages never depends on it.
 * [onSeedConversationFixture] is null outside debuggable builds, and its row is then absent.
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun DeveloperContent(
    developerMode: Boolean,
    streamingDebug: Boolean,
    build: DeveloperBuildFacts,
    onDeveloperModeChange: (Boolean) -> Unit,
    onStreamingDebugChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenKeyPackages: () -> Unit,
    demoStatus: ReviewDemoStatus = ReviewDemoStatus.Idle,
    demoAvailable: Boolean = false,
    demoHasSavedSetup: Boolean = false,
    onStartDemo: () -> Unit = {},
    onOpenDemo: (ReviewDemoStatus.Ready) -> Unit = {},
    onClearDemo: () -> Unit = {},
    onSeedConversationFixture: (() -> Unit)? = null,
) {
    var confirmStart by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val demoActionText = if (demoHasSavedSetup) R.string.review_demo_resume else R.string.review_demo_create
    val demoConfirmTitle =
        if (demoHasSavedSetup) R.string.review_demo_resume_confirm_title else R.string.review_demo_confirm_title
    val demoConfirmBody =
        if (demoHasSavedSetup) R.string.review_demo_resume_confirm_body else R.string.review_demo_confirm_body
    val demoConfirmAction =
        if (demoHasSavedSetup) R.string.review_demo_resume_confirm_action else R.string.review_demo_confirm_action
    SettingsScaffold(title = stringResource(R.string.settings_developer_tools), onBack = onBack) {
        SettingsList {
            item {
                SettingsCallout(
                    text = stringResource(R.string.developer_testing_only_detail),
                    modifier = Modifier.padding(top = WhiteNoiseSpacing.Section).testTag("developer.warning"),
                    title = stringResource(R.string.developer_testing_only),
                    icon = R.drawable.ic_warning,
                )
            }
            item {
                SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.Section).testTag("developer.mode")) {
                    row("developer_mode") { context ->
                        SettingsSwitch(
                            context = context,
                            title = stringResource(R.string.developer_mode),
                            checked = developerMode,
                            onCheckedChange = onDeveloperModeChange,
                            modifier = Modifier.testTag("developer.mode.switch"),
                        )
                    }
                }
            }
            item { SettingsExplainer(stringResource(R.string.developer_mode_subtitle)) }
            item { SettingsSection(stringResource(R.string.review_demo_section)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("developer.demo")) {
                    row("review_demo") { context ->
                        SettingsLink(
                            context = context,
                            title =
                                when (demoStatus) {
                                    is ReviewDemoStatus.Ready -> stringResource(R.string.review_demo_open)
                                    else -> stringResource(demoActionText)
                                },
                            subtitle =
                                when (demoStatus) {
                                    is ReviewDemoStatus.Running -> reviewDemoStageText(demoStatus.stage)
                                    is ReviewDemoStatus.Failed ->
                                        stringResource(
                                            R.string.review_demo_stage_error,
                                            reviewDemoStageText(demoStatus.stage),
                                            reviewDemoProblemText(demoStatus.problem),
                                        )
                                    is ReviewDemoStatus.Ready -> stringResource(R.string.review_demo_ready)
                                    ReviewDemoStatus.Idle ->
                                        if (demoAvailable) {
                                            stringResource(R.string.review_demo_description)
                                        } else {
                                            stringResource(R.string.review_demo_unavailable)
                                        }
                                },
                            onClick = {
                                when (demoStatus) {
                                    is ReviewDemoStatus.Ready -> onOpenDemo(demoStatus)
                                    is ReviewDemoStatus.Running -> Unit
                                    else -> confirmStart = true
                                }
                            },
                            enabled =
                                demoAvailable &&
                                    demoStatus !is ReviewDemoStatus.Running &&
                                    !(
                                        demoStatus is ReviewDemoStatus.Failed &&
                                            demoStatus.problem == ReviewDemoProblem.InvalidCheckpoint
                                    ),
                            busy = demoStatus is ReviewDemoStatus.Running,
                            modifier = Modifier.testTag("developer.demo.action"),
                        )
                    }
                    if (demoHasSavedSetup && demoStatus !is ReviewDemoStatus.Running) {
                        row("review_demo_clear") { context ->
                            SettingsAction(
                                context = context,
                                title = stringResource(R.string.review_demo_clear),
                                onClick = { confirmClear = true },
                                destructive = true,
                                modifier = Modifier.testTag("developer.demo.clear"),
                            )
                        }
                    }
                }
            }
            item { SettingsExplainer(stringResource(R.string.review_demo_clear_explanation)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("developer.key_packages")) {
                    row("key_packages") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.key_packages),
                            onClick = onOpenKeyPackages,
                            modifier = Modifier.testTag("developer.key_packages.row"),
                            subtitle = stringResource(R.string.key_packages_settings_subtitle),
                        )
                    }
                }
            }
            if (developerMode) {
                item { SettingsSection(stringResource(R.string.developer_debugging)) }
                item {
                    SettingsGroup(modifier = Modifier.testTag("developer.debugging")) {
                        row("streaming_debug") { context ->
                            SettingsSwitch(
                                context = context,
                                title = stringResource(R.string.streaming_debug),
                                checked = streamingDebug,
                                onCheckedChange = onStreamingDebugChange,
                                modifier = Modifier.testTag("developer.streaming_debug"),
                            )
                        }
                        row("diagnostics") { context ->
                            SettingsLink(
                                context = context,
                                title = stringResource(R.string.diagnostics),
                                onClick = onOpenDiagnostics,
                                modifier = Modifier.testTag("developer.diagnostics"),
                                subtitle = stringResource(R.string.diagnostics_settings_subtitle),
                            )
                        }
                        if (onSeedConversationFixture != null) {
                            row("seed_fixture") { context ->
                                SettingsLink(
                                    context = context,
                                    title = stringResource(R.string.developer_seed_fixture),
                                    onClick = onSeedConversationFixture,
                                    modifier = Modifier.testTag("developer.seed_fixture"),
                                    subtitle = stringResource(R.string.developer_seed_fixture_subtitle),
                                )
                            }
                        }
                    }
                }
                item { SettingsExplainer(stringResource(R.string.streaming_debug_subtitle)) }
            }
            item { SettingsSection(stringResource(R.string.about)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("developer.build")) {
                    row("version") { context ->
                        SettingsValue(context, stringResource(R.string.about_version), build.versionName)
                    }
                    row("build") { context ->
                        SettingsValue(context, stringResource(R.string.about_build), build.buildNumber)
                    }
                    row("mdk") { context ->
                        SettingsValue(context, stringResource(R.string.about_mdk), build.mdkShortSha)
                    }
                }
            }
            if (build.staging) {
                item { DeveloperStagingBadge() }
            }
        }
    }
    if (confirmStart) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { confirmStart = false },
            title = {
                Text(
                    stringResource(demoConfirmTitle),
                )
            },
            text = {
                Text(
                    stringResource(demoConfirmBody),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStart = false
                        onStartDemo()
                    },
                    modifier = Modifier.testTag("developer.demo.confirm"),
                ) {
                    Text(
                        stringResource(demoConfirmAction),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (confirmClear) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.review_demo_clear)) },
            text = { Text(stringResource(R.string.review_demo_clear_explanation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearDemo()
                    },
                    modifier = Modifier.testTag("developer.demo.clear.confirm"),
                ) { Text(stringResource(R.string.review_demo_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun reviewDemoStageText(stage: ReviewDemoStage): String =
    stringResource(
        when (stage) {
            ReviewDemoStage.Preparing -> R.string.review_demo_preparing
            ReviewDemoStage.CreatingAccount -> R.string.review_demo_creating_account
            ReviewDemoStage.PublishingProfile -> R.string.review_demo_publishing_profile
            ReviewDemoStage.CreatingConversation -> R.string.review_demo_creating_conversation
            ReviewDemoStage.SendingOriginal -> R.string.review_demo_sending_original
            ReviewDemoStage.AcceptingInvitation -> R.string.review_demo_accepting_invitation
            ReviewDemoStage.SendingDemo -> R.string.review_demo_sending_demo
            ReviewDemoStage.VerifyingDelivery -> R.string.review_demo_verifying
            ReviewDemoStage.Returning -> R.string.review_demo_returning
        },
    )

@Composable
private fun reviewDemoProblemText(problem: ReviewDemoProblem): String =
    stringResource(
        when (problem) {
            ReviewDemoProblem.Unavailable -> R.string.review_demo_unavailable
            ReviewDemoProblem.InvalidCheckpoint -> R.string.review_demo_invalid_checkpoint
            ReviewDemoProblem.OriginalMissing -> R.string.review_demo_original_missing
            ReviewDemoProblem.DemoMissing -> R.string.review_demo_account_missing
            ReviewDemoProblem.AmbiguousAccount -> R.string.review_demo_ambiguous_account
            ReviewDemoProblem.OwnerChanged -> R.string.review_demo_owner_changed
            ReviewDemoProblem.AccountSetupTimedOut -> R.string.review_demo_setup_timed_out
            ReviewDemoProblem.DeliveryTimedOut -> R.string.review_demo_delivery_timed_out
            ReviewDemoProblem.ReactionUncertain -> R.string.review_demo_reaction_uncertain
            ReviewDemoProblem.Interrupted -> R.string.review_demo_interrupted
            ReviewDemoProblem.OperationFailed -> R.string.review_demo_operation_failed
        },
    )

/** Retains the production variant badge beside build facts without changing the Settings home footer. */
@Suppress("FunctionNaming")
@Composable
private fun DeveloperStagingBadge() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = PillShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.testTag("developer.staging"),
        ) {
            Text(
                text = stringResource(R.string.settings_staging_badge),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}
