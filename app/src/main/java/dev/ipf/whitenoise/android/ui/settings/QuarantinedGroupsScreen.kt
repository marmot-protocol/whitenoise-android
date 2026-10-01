package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.ipf.marmotkit.AppGroupHydrationQuarantineReasonFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.QuarantineRecoveryOutcome
import dev.ipf.whitenoise.android.state.QuarantinedGroupsController
import dev.ipf.whitenoise.android.state.QuarantinedGroupsUiState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.quarantineAccountEligible
import dev.ipf.whitenoise.android.state.quarantinedGroupsAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Observable account and runtime generation changes replace the screen's controller. */
@Composable
internal fun rememberQuarantinedGroupsController(appState: WhiteNoiseAppState): QuarantinedGroupsController {
    val scope = rememberCoroutineScope()
    val eligible = appState.quarantineAccountEligible()
    val controller =
        remember(appState.activeAccountRef, appState.runtimeGeneration, appState.developerMode, eligible) {
            QuarantinedGroupsController(appState.quarantinedGroupsAccess(), scope)
        }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller) { controller.refresh() }
    return controller
}

/** The Developer Tools subtitle performs one native read per observable owner. */
@Composable
internal fun rememberQuarantinedGroupsSummary(appState: WhiteNoiseAppState): String {
    val eligible = appState.quarantineAccountEligible()
    val state by key(appState.activeAccountRef, appState.runtimeGeneration, eligible) {
        produceState(QuarantinedGroupsUiState(loading = true)) {
            val access = appState.quarantinedGroupsAccess()
            value =
                if (access == null) {
                    QuarantinedGroupsUiState(available = false)
                } else {
                    try {
                        val rows = access.load()
                        if (access.isCurrent()) {
                            QuarantinedGroupsUiState(loaded = true, rows = rows)
                        } else {
                            QuarantinedGroupsUiState(available = false)
                        }
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        currentCoroutineContext().ensureActive()
                        val current = access.isCurrent()
                        QuarantinedGroupsUiState(available = current, loadFailed = current)
                    } catch (_: Exception) {
                        QuarantinedGroupsUiState(loadFailed = true)
                    }
                }
        }
    }
    return quarantinedGroupsSummary(state)
}

@Suppress("FunctionNaming")
@Composable
internal fun QuarantinedGroupsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val controller = rememberQuarantinedGroupsController(appState)
    val state by controller.state.collectAsState()
    QuarantinedGroupsContent(state, onBack, controller::refresh, controller::recover)
}

@Composable
internal fun quarantinedGroupsSummary(state: QuarantinedGroupsUiState): String =
    when {
        !state.available -> stringResource(R.string.quarantine_unavailable)
        state.loading -> stringResource(R.string.quarantine_loading)
        state.loadFailed -> stringResource(R.string.quarantine_load_failed)
        state.loaded -> stringResource(R.string.quarantine_count, state.rows.size)
        else -> stringResource(R.string.quarantine_loading)
    }

internal fun quarantineGuidance(reason: AppGroupHydrationQuarantineReasonFfi): Int =
    when (reason) {
        AppGroupHydrationQuarantineReasonFfi.OPEN_MLS_LOAD_FAILED -> R.string.quarantine_stored_state
        AppGroupHydrationQuarantineReasonFfi.OPEN_MLS_GROUP_MISSING -> R.string.quarantine_missing_state
        AppGroupHydrationQuarantineReasonFfi.MEMBER_VALIDATION_FAILED -> R.string.quarantine_member_validation
        AppGroupHydrationQuarantineReasonFfi.GROUP_RECORD_LOAD_FAILED -> R.string.quarantine_group_record
        AppGroupHydrationQuarantineReasonFfi.PENDING_COMMIT_RECOVERY_FAILED -> R.string.quarantine_pending_commit
    }

internal fun quarantineOutcome(outcome: QuarantineRecoveryOutcome): Int =
    when (outcome) {
        QuarantineRecoveryOutcome.Recovered -> R.string.quarantine_recovered
        QuarantineRecoveryOutcome.StillQuarantined -> R.string.quarantine_still_quarantined
        QuarantineRecoveryOutcome.Failed -> R.string.quarantine_retry_failed
    }

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
internal fun QuarantinedGroupsContent(
    state: QuarantinedGroupsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRecover: (String) -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.quarantined_groups), onBack = onBack) {
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { if (state.available && !state.busy) onRefresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            SettingsList(modifier = Modifier.testTag("quarantine.list")) {
                item { SettingsExplainer(stringResource(R.string.quarantine_explanation)) }
                item {
                    SettingsGroup {
                        row("refresh") { context ->
                            SettingsLink(
                                context = context,
                                title = stringResource(R.string.quarantine_refresh),
                                onClick = onRefresh,
                                subtitle = quarantinedGroupsSummary(state),
                                enabled = state.available && !state.busy,
                                busy = state.loading,
                                modifier = Modifier.testTag("quarantine.refresh"),
                            )
                        }
                    }
                }
                quarantineCallouts(state)
                quarantineRows(state, onRecover)
            }
        }
    }
}

private fun LazyListScope.quarantineCallouts(state: QuarantinedGroupsUiState) {
    val loadedWithoutError = state.loaded && !state.loadFailed
    if (loadedWithoutError && state.rows.isEmpty() && !state.loading) {
        item { SettingsCallout(stringResource(R.string.quarantine_empty), Modifier.testTag("quarantine.empty")) }
    }
    if (state.loadFailed) {
        item {
            SettingsCallout(
                text = stringResource(R.string.quarantine_load_failed),
                isError = true,
                modifier = Modifier.testTag("quarantine.error"),
            )
        }
    }
    state.outcome?.let { outcome ->
        item {
            SettingsCallout(
                text = stringResource(quarantineOutcome(outcome)),
                isError = outcome == QuarantineRecoveryOutcome.Failed,
                modifier = Modifier.testTag("quarantine.outcome").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

private fun LazyListScope.quarantineRows(
    state: QuarantinedGroupsUiState,
    onRecover: (String) -> Unit,
) {
    state.rows.forEach { entry ->
        item(key = entry.groupId) {
            SettingsGroup {
                row("recovery") { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(R.string.quarantine_retry),
                        subtitle =
                            stringResource(R.string.quarantine_group, IdentityFormatter.short(entry.groupId)) +
                                "\n" + stringResource(quarantineGuidance(entry.reason)),
                        onClick = { onRecover(entry.groupId) },
                        enabled = state.available && state.loaded && !state.busy,
                        leading =
                            if (state.recoveringGroup == entry.groupId) {
                                { CircularProgressIndicator() }
                            } else {
                                null
                            },
                        modifier = Modifier.testTag("quarantine.recover"),
                    )
                }
            }
        }
    }
}
