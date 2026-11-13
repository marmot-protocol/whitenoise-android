package dev.ipf.whitenoise.android.ui.settings

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AuditDeliveryState
import dev.ipf.whitenoise.android.state.AuditDeliverySummary
import dev.ipf.whitenoise.android.state.AuditDiagnosticsSettings
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.auditRetentionChoices
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Refreshes local audit state on entry and when the settings screen returns to the foreground. */
@Composable
internal fun rememberAuditDiagnosticsSettings(appState: WhiteNoiseAppState): AuditDiagnosticsSettings {
    val settings = remember(appState, appState.runtimeGeneration) { appState.auditDiagnosticsSettings() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(settings, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            coroutineScope {
                launch { settings.refreshRetention() }
                launch { settings.refreshDelivery() }
            }
        }
    }
    return settings
}

/** Shows the device-wide status and offers an explicit retry only when delivery is paused. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun AuditDeliverySection(
    settings: AuditDiagnosticsSettings,
    consentBusy: Boolean,
    onRefresh: () -> Unit,
    onResume: () -> Unit,
) {
    val delivery = settings.delivery?.getOrNull()
    val status = stringResource(auditDeliveryLabel(delivery?.state, settings.delivery?.isFailure == true))
    val enabled = !consentBusy && !settings.resumingDelivery
    SettingsGroup(modifier = Modifier.testTag("audit.delivery.group")) {
        row("audit_delivery_status") { rowContext ->
            SettingsValue(
                context = rowContext,
                title = stringResource(R.string.audit_delivery_title),
                value = listOfNotNull(status, delivery?.httpStatus?.let { "HTTP $it" }).joinToString(" · "),
            )
        }
        auditDeliveryMetadata(delivery)
        if (delivery?.state == AuditDeliveryState.PAUSED) {
            row("audit_resume_delivery") { rowContext ->
                SettingsAction(
                    context = rowContext,
                    title = stringResource(R.string.audit_delivery_resume),
                    onClick = onResume,
                    enabled = enabled,
                    subtitle =
                        when {
                            settings.resumingDelivery -> stringResource(R.string.audit_delivery_resuming)
                            settings.deliveryResumeFailed -> stringResource(R.string.audit_delivery_resume_failed)
                            else -> null
                        },
                    leading = { SettingsLeadingIcon(R.drawable.ic_play_arrow) },
                )
            }
        }
        if (settings.delivery != null && delivery?.state != AuditDeliveryState.OFF) {
            row("audit_refresh_delivery") { rowContext ->
                SettingsAction(
                    rowContext,
                    stringResource(R.string.refresh),
                    onRefresh,
                    enabled = enabled,
                    leading = { SettingsLeadingIcon(R.drawable.ic_refresh) },
                )
            }
        }
    }
}

/** Adds the measured delivery time and backlog when MDK reports them. */
private fun SettingsGroupScope.auditDeliveryMetadata(delivery: AuditDeliverySummary?) {
    if (delivery == null) return
    if (delivery.state != AuditDeliveryState.OFF && delivery.state != AuditDeliveryState.UNCONFIGURED) {
        row("audit_last_success") { rowContext ->
            val lastCheck =
                delivery.lastSuccessAtMs?.let {
                    DateUtils.formatDateTime(
                        LocalContext.current,
                        it,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
                    )
                } ?: stringResource(R.string.download_never)
            SettingsValue(rowContext, stringResource(R.string.audit_delivery_last_success), lastCheck)
        }
    }
    if (delivery.waitingBytes > 0L) {
        row("audit_delivery_backlog") { rowContext ->
            SettingsValue(
                rowContext,
                stringResource(R.string.audit_delivery_waiting),
                Formatter.formatShortFileSize(LocalContext.current, delivery.waitingBytes),
            )
        }
    }
}

/** Maps typed MDK status to short copy and reserves the loading label for the first read. */
@StringRes
private fun auditDeliveryLabel(
    state: AuditDeliveryState?,
    failed: Boolean,
): Int =
    when (state) {
        AuditDeliveryState.OFF -> R.string.usage_diagnostics_off
        AuditDeliveryState.UNCONFIGURED -> R.string.usage_diagnostics_unconfigured
        AuditDeliveryState.ACTIVE -> R.string.audit_delivery_active
        AuditDeliveryState.RETRYING -> R.string.audit_delivery_retrying
        AuditDeliveryState.PAUSED -> R.string.audit_delivery_paused
        null -> if (failed) R.string.relay_list_status_unavailable else R.string.audit_settings_loading
    }

/** The age setting is editable with or without sharing; reducing the age requires confirmation. */
@Suppress("FunctionNaming")
@Composable
internal fun AuditRetentionRow(
    context: SettingsRowContext,
    settings: AuditDiagnosticsSettings,
    onRefresh: () -> Unit,
    onChoose: (UInt, Boolean) -> Unit,
) {
    val current = settings.retention?.getOrNull()?.maxAgeDays
    var pickerOpen by remember(settings) { mutableStateOf(false) }
    var confirmDays by remember(settings) { mutableStateOf<UInt?>(null) }
    val label = stringResource(R.string.audit_retention_title)
    SettingsLink(
        context = context,
        title = label,
        modifier = Modifier.testTag("audit.retention"),
        onClick = { if (current == null) onRefresh() else pickerOpen = true },
        value = current?.let { auditRetentionDays(it) },
        subtitle =
            when {
                settings.retentionSaveFailed -> stringResource(R.string.audit_retention_save_failed)
                settings.retention?.isFailure == true -> stringResource(R.string.relay_list_status_unavailable)
                else -> null
            },
        busy = settings.retention == null || settings.savingRetention,
        leading = { SettingsLeadingIcon(R.drawable.ic_history) },
    )
    if (pickerOpen && current != null) {
        val values = auditRetentionChoices(current)
        val labels = values.associateWith { auditRetentionDays(it) }
        ChoiceDialog(
            title = label,
            values = values,
            selected = current,
            label = labels::getValue,
            onDismiss = { pickerOpen = false },
            onSelect = { days ->
                pickerOpen = false
                when {
                    days < current -> confirmDays = days
                    days > current -> onChoose(days, false)
                }
            },
        )
    }
    confirmDays?.let { days ->
        ShorterAuditRetentionDialog(
            days = days,
            onDismiss = { confirmDays = null },
            onConfirm = {
                confirmDays = null
                onChoose(days, true)
            },
        )
    }
}

/** Confirms local expiry before writing a shorter MDK retention period. */
@Suppress("FunctionNaming")
@Composable
private fun ShorterAuditRetentionDialog(
    days: UInt,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audit_retention_confirm_title)) },
        text = { Text(stringResource(R.string.audit_retention_confirm_body, auditRetentionDays(days))) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.audit_retention_confirm_action), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Formats the age using the app's resource locale. */
@Composable
private fun auditRetentionDays(days: UInt): String {
    val quantity = days.toInt()
    return pluralStringResource(R.plurals.audit_retention_days, quantity, quantity)
}
