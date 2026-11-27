@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.LinkedDeviceRow
import dev.ipf.whitenoise.android.state.LinkedDeviceSettingsPatch
import dev.ipf.whitenoise.android.state.deviceLabelFits
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsCallout
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsList
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.settings.SettingsSwitch

internal const val DEVICE_DETAIL_LIST_TAG = "device_detail.list"
internal const val DEVICE_DETAIL_RENAME_TAG = "device_detail.rename"
internal const val DEVICE_DETAIL_RENAME_FIELD_TAG = "device_detail.rename.field"
internal const val DEVICE_DETAIL_RENAME_SAVE_TAG = "device_detail.rename.save"
internal const val DEVICE_DETAIL_ADD_TO_CHATS_TAG = "device_detail.add_to_chats"
internal const val DEVICE_DETAIL_BRING_HISTORY_TAG = "device_detail.bring_history"
internal const val DEVICE_DETAIL_KEEP_TAG = "device_detail.keep"
internal const val DEVICE_DETAIL_REMOVE_TAG = "device_detail.remove"
internal const val DEVICE_DETAIL_REMOVE_CONFIRM_TAG = "device_detail.remove.confirm"

/** What a device's detail page can ask its owner to do. Each maps to one MDK call. */
internal data class LinkedDeviceDetailActions(
    val onRename: (String) -> Unit = {},
    val onSettings: (LinkedDeviceSettingsPatch) -> Unit = {},
    val onRemove: () -> Unit = {},
)

/**
 * One linked device: its facts, its name, its three settings and, for a device other than this one, removal.
 * Every value shown is the roster's. A toggle sends only the setting it changes, and the switch moves when MDK's
 * roster does, so a refused or lost edit never looks applied. While [busy], or while the device's removal is
 * pending, nothing can be edited.
 */
@Composable
internal fun LinkedDeviceDetailContent(
    device: LinkedDeviceRow,
    busy: Boolean,
    onBack: () -> Unit,
    actions: LinkedDeviceDetailActions = LinkedDeviceDetailActions(),
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    val name = device.label.ifBlank { stringResource(R.string.linked_devices_unnamed) }
    val editable = !busy && !device.removalPending
    SettingsScaffold(title = name, onBack = onBack) {
        SettingsList(modifier = Modifier.testTag(DEVICE_DETAIL_LIST_TAG)) {
            linkedDeviceDetailRows(device, name, editable, actions.onSettings, { renaming = true }, { removing = true })
        }
    }
    if (renaming) {
        RenameDeviceDialog(
            current = device.label,
            onSave = {
                renaming = false
                actions.onRename(it)
            },
            onDismiss = { renaming = false },
        )
    }
    if (removing) {
        RemoveDeviceDialog(
            name = name,
            onRemove = {
                removing = false
                actions.onRemove()
            },
            onDismiss = { removing = false },
        )
    }
}

/**
 * The rows of a device's page: the removal notice, its head, Rename, its settings, and Remove device for a
 * device other than this one whose removal is not already pending.
 */
@Suppress("LongParameterList") // One flat set of values and the three things a row can ask for.
private fun LazyListScope.linkedDeviceDetailRows(
    device: LinkedDeviceRow,
    name: String,
    editable: Boolean,
    onSettings: (LinkedDeviceSettingsPatch) -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    if (device.removalPending) {
        item(key = "removing") { SettingsCallout(stringResource(R.string.linked_devices_removal_pending)) }
    }
    item(key = "hero") { LinkedDeviceHero(device, name) }
    item(key = "rename") {
        SettingsGroup {
            row("rename") { context ->
                SettingsLink(
                    context = context,
                    title = stringResource(R.string.device_detail_rename),
                    onClick = onRename,
                    modifier = Modifier.testTag(DEVICE_DETAIL_RENAME_TAG),
                    explanation = stringResource(R.string.device_detail_rename_detail),
                    enabled = editable,
                    leading = { SettingsLeadingIcon(R.drawable.ic_edit) },
                )
            }
        }
    }
    item(key = "settings") { LinkedDeviceSettingsGroup(device, editable, onSettings) }
    if (!device.isThisDevice && !device.removalPending) {
        item(key = "remove") {
            SettingsGroup {
                row("remove") { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(R.string.device_detail_remove),
                        onClick = onRemove,
                        modifier = Modifier.testTag(DEVICE_DETAIL_REMOVE_TAG),
                        explanation = stringResource(R.string.device_detail_remove_detail),
                        enabled = editable,
                        destructive = true,
                        leading = { SettingsLeadingIcon(R.drawable.ic_delete) },
                    )
                }
            }
        }
    }
}

/** The device's three settings. Each switch sends a patch naming only itself. */
@Composable
private fun LinkedDeviceSettingsGroup(
    device: LinkedDeviceRow,
    editable: Boolean,
    onSettings: (LinkedDeviceSettingsPatch) -> Unit,
) {
    SettingsGroup {
        row("add_to_chats") { context ->
            SettingsSwitch(
                context = context,
                title = stringResource(R.string.link_request_add_to_chats),
                checked = device.addToAllChats,
                onCheckedChange = { onSettings(LinkedDeviceSettingsPatch(addToAllChats = it)) },
                modifier = Modifier.testTag(DEVICE_DETAIL_ADD_TO_CHATS_TAG),
                explanation = stringResource(R.string.device_detail_add_to_chats_detail),
                subtitle = stringResource(R.string.device_detail_add_to_chats_detail),
                enabled = editable,
                leading = { SettingsLeadingIcon(R.drawable.ic_settings_chat_bubble_outline) },
            )
        }
        row("bring_history") { context ->
            SettingsSwitch(
                context = context,
                title = stringResource(R.string.link_request_bring_history),
                checked = device.bringHistory,
                onCheckedChange = { onSettings(LinkedDeviceSettingsPatch(bringHistory = it)) },
                modifier = Modifier.testTag(DEVICE_DETAIL_BRING_HISTORY_TAG),
                explanation = stringResource(R.string.device_detail_bring_history_detail),
                subtitle = stringResource(R.string.device_detail_bring_history_detail),
                enabled = editable,
                leading = { SettingsLeadingIcon(R.drawable.ic_history) },
            )
        }
        row("keep_while_inactive") { context ->
            SettingsSwitch(
                context = context,
                title = stringResource(R.string.device_detail_keep),
                checked = device.keepWhileInactive,
                onCheckedChange = { onSettings(LinkedDeviceSettingsPatch(keepWhileInactive = it)) },
                modifier = Modifier.testTag(DEVICE_DETAIL_KEEP_TAG),
                explanation = stringResource(R.string.device_detail_keep_detail),
                subtitle = stringResource(R.string.device_detail_keep_detail),
                enabled = editable,
                leading = { SettingsLeadingIcon(R.drawable.ic_schedule) },
            )
        }
    }
}

/**
 * Asks for the device's new name. An empty name clears it, as MDK allows. A name over MDK's 64-byte limit says so
 * and cannot be saved; no other rule is checked here.
 */
@Composable
private fun RenameDeviceDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val field = rememberTextFieldState(current)
    val name = field.text.toString().trim()
    val tooLong = stringResource(R.string.link_request_label_too_long).takeUnless { deviceLabelFits(name) }
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_detail_rename)) },
        text = {
            WhiteNoiseTextField(
                state = field,
                modifier = Modifier.fillMaxWidth().testTag(DEVICE_DETAIL_RENAME_FIELD_TAG),
                label = { Text(stringResource(R.string.link_request_label)) },
                supportingText = tooLong?.let { message -> { Text(message) } },
                errorMessage = tooLong,
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name) },
                enabled = tooLong == null,
                modifier = Modifier.testTag(DEVICE_DETAIL_RENAME_SAVE_TAG),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Confirms removing a device, saying what removal does and that it is carried out as the devices are able to. */
@Composable
private fun RemoveDeviceDialog(
    name: String,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.device_detail_remove),
        message = stringResource(R.string.device_detail_remove_confirm, name),
        confirmLabel = stringResource(R.string.device_detail_remove),
        onConfirm = onRemove,
        onDismiss = onDismiss,
        destructive = true,
        confirmTag = DEVICE_DETAIL_REMOVE_CONFIRM_TAG,
        protectInput = true,
    )
}
