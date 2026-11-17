package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.EncryptedBackupPassphraseStrength
import dev.ipf.whitenoise.android.core.encryptedBackupPassphraseInputsValid
import dev.ipf.whitenoise.android.core.encryptedBackupPassphraseStrength
import dev.ipf.whitenoise.android.core.groupedEncryptedBackup
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSecureTextField
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** The export dialogs: raw consequence, encrypted password/result, delivery failure and expiry. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun ProfileKeysDialogs(
    ui: ProfileKeysUiState,
    canExport: Boolean,
    onExport: (encrypted: Boolean, destination: KeyExportDestination) -> Unit,
    onCopyBackup: () -> Unit,
    onExportBackup: () -> Unit,
) {
    if (ui.rawExportDialog && canExport) {
        WhiteNoiseAlertDialog(
            onDismissRequest = ui::cancelExport,
            onDismissStart = ui::cancelExportPreparation,
            title = { Text(stringResource(R.string.keep_your_private_key_safe)) },
            text = {
                Text(
                    stringResource(R.string.export_private_key_consequence),
                    modifier = Modifier.fadingVerticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                FlowRow(horizontalArrangement = Arrangement.End) {
                    val shareLabel = stringResource(R.string.share)
                    val fileLabel = stringResource(R.string.export_nsec)
                    TextButton(
                        enabled = !ui.exportBusy,
                        onClick = { if (acceptsInput) onExport(false, KeyExportDestination.Share) },
                        modifier =
                            Modifier.explainOnLongPress(
                                Explanation(
                                    shareLabel,
                                    stringResource(R.string.explanation_organize_keys_raw_share),
                                ),
                            ),
                    ) {
                        ProfileKeyActionLabel(
                            shareLabel,
                            R.drawable.ic_share,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(
                        enabled = !ui.exportBusy,
                        onClick = { if (acceptsInput) onExport(false, KeyExportDestination.File) },
                        modifier =
                            Modifier.explainOnLongPress(
                                Explanation(
                                    fileLabel,
                                    stringResource(R.string.explanation_organize_keys_raw_file),
                                ),
                            ),
                    ) {
                        ProfileKeyActionLabel(
                            fileLabel,
                            R.drawable.ic_download,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss) {
                    ProfileKeyActionLabel(stringResource(R.string.cancel), R.drawable.ic_close)
                }
            },
        )
    }
    if (ui.passwordDialog && canExport) {
        ExportPasswordDialog(
            password = ui.password,
            confirmation = ui.confirmation,
            busy = ui.exportBusy,
            onConfirm = { onExport(true, KeyExportDestination.File) },
            onViewBackup = { onExport(true, KeyExportDestination.Preview) },
            onDismiss = ui::cancelExport,
            onDismissStart = ui::cancelExportPreparation,
        )
    }
    ui.encryptedBackup?.let { backup ->
        EncryptedBackupResultDialog(
            backup = backup.content,
            copied = ui.copiedKey == CopiedProfileKey.Encrypted,
            onCopy = onCopyBackup,
            onExport = onExportBackup,
            onHide = ui::cancelExport,
        )
    }
    ProfileKeyFeedbackDialogs(
        nativeExportError = ui.nativeExportError,
        shareErrorDialog = ui.shareErrorDialog,
        saveErrorDialog = ui.saveErrorDialog,
        expiredExportDialog = ui.expiredExportDialog,
        onNativeErrorDismiss = { ui.nativeExportError = null },
        onShareErrorDismiss = { ui.shareErrorDialog = false },
        onSaveErrorDismiss = { ui.saveErrorDialog = false },
        onExpiryDismiss = { ui.expiredExportDialog = false },
    )
}

/** Decorative action glyphs share button content colour and leave the label as the accessible name. */
@Suppress("FunctionNaming")
@Composable
internal fun ProfileKeyActionLabel(
    text: String,
    @DrawableRes icon: Int,
    color: Color = LocalContentColor.current,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        Text(text, color = color)
    }
}

/** Actual export feedback; callers keep operation ownership and clearing each error flag. */
@Composable
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
internal fun ProfileKeyFeedbackDialogs(
    nativeExportError: Int?,
    shareErrorDialog: Boolean,
    saveErrorDialog: Boolean,
    expiredExportDialog: Boolean,
    onNativeErrorDismiss: () -> Unit,
    onShareErrorDismiss: () -> Unit,
    onSaveErrorDismiss: () -> Unit,
    onExpiryDismiss: () -> Unit,
) {
    nativeExportError?.let { title ->
        WhiteNoiseAlertDialog(
            onDismissRequest = onNativeErrorDismiss,
            title = { Text(stringResource(title)) },
            confirmButton = {
                TextButton(onClick = dismiss) {
                    ProfileKeyActionLabel(stringResource(R.string.ok), R.drawable.ic_check)
                }
            },
        )
    }
    if (shareErrorDialog) {
        WhiteNoiseAlertDialog(
            onDismissRequest = onShareErrorDismiss,
            title = { Text(stringResource(R.string.outbound_share_failed)) },
            confirmButton = {
                TextButton(onClick = dismiss) {
                    ProfileKeyActionLabel(stringResource(R.string.ok), R.drawable.ic_check)
                }
            },
        )
    }
    if (saveErrorDialog) {
        WhiteNoiseAlertDialog(
            onDismissRequest = onSaveErrorDismiss,
            title = { Text(stringResource(R.string.couldnt_save_file)) },
            text = { Text(stringResource(R.string.choose_another_location_and_try_again)) },
            confirmButton = {
                TextButton(onClick = dismiss) {
                    ProfileKeyActionLabel(stringResource(R.string.ok), R.drawable.ic_check)
                }
            },
        )
    }
    if (expiredExportDialog) {
        WhiteNoiseAlertDialog(
            onDismissRequest = onExpiryDismiss,
            title = { Text(stringResource(R.string.key_export_expired_title)) },
            text = { Text(stringResource(R.string.key_export_expired_body)) },
            confirmButton = {
                TextButton(onClick = dismiss) {
                    ProfileKeyActionLabel(stringResource(R.string.close), R.drawable.ic_close)
                }
            },
        )
    }
}

/** Two secure fields, the mismatch/help line and strength meter; matching fields unlock both destinations. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
private fun ExportPasswordDialog(
    password: TextFieldState,
    confirmation: TextFieldState,
    busy: Boolean,
    onConfirm: () -> Unit,
    onViewBackup: () -> Unit,
    onDismiss: () -> Unit,
    onDismissStart: () -> Unit,
) {
    val passwordValue = password.text.toString()
    val confirmationValue = confirmation.text.toString()
    val mismatch = confirmationValue.isNotEmpty() && passwordValue != confirmationValue
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        onDismissStart = onDismissStart,
        title = { Text(stringResource(R.string.encrypted_private_key)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                WhiteNoiseSecureTextField(
                    state = password,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("profile_keys.export_password"),
                    label = { Text(stringResource(R.string.password)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                )
                WhiteNoiseSecureTextField(
                    state = confirmation,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("profile_keys.export_confirmation"),
                    label = { Text(stringResource(R.string.confirm_password)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    errorMessage = if (mismatch) stringResource(R.string.passwords_mismatch) else null,
                )
                Text(
                    text =
                        stringResource(
                            if (mismatch) R.string.passwords_mismatch else R.string.export_password_help,
                        ),
                    color =
                        if (mismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (passwordValue.isNotEmpty()) {
                    ExportPasswordStrengthIndicator(encryptedBackupPassphraseStrength(passwordValue))
                }
            }
        },
        confirmButton = {
            val viewLabel = stringResource(R.string.key_export_view_backup)
            val exportLabel = stringResource(R.string.export)
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(
                    enabled = !busy && encryptedBackupPassphraseInputsValid(passwordValue, confirmationValue),
                    onClick = onViewBackup,
                    modifier =
                        Modifier
                            .testTag("profile_keys.view_backup")
                            .explainOnLongPress(
                                Explanation(
                                    viewLabel,
                                    stringResource(R.string.explanation_organize_keys_view_backup),
                                ),
                            ),
                ) { ProfileKeyActionLabel(viewLabel, R.drawable.ic_visibility) }
                TextButton(
                    enabled = !busy && encryptedBackupPassphraseInputsValid(passwordValue, confirmationValue),
                    onClick = onConfirm,
                    modifier =
                        Modifier
                            .testTag("profile_keys.export_file")
                            .explainOnLongPress(
                                Explanation(
                                    exportLabel,
                                    stringResource(R.string.explanation_organize_keys_backup_export),
                                ),
                            ),
                ) { ProfileKeyActionLabel(exportLabel, R.drawable.ic_download) }
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss) {
                ProfileKeyActionLabel(stringResource(R.string.cancel), R.drawable.ic_close)
            }
        },
    )
}

/** Temporary account-owned encrypted result; copying and file export are separate deliberate actions. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun EncryptedBackupResultDialog(
    backup: String,
    copied: Boolean,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onHide: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onHide,
        title = { Text(stringResource(R.string.encrypted_backup_result_title)) },
        text = {
            Column(
                modifier = Modifier.fadingVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                Text(stringResource(R.string.key_export_preview_help))
                Text(
                    text = groupedEncryptedBackup(backup),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("profile_keys.encrypted_backup"),
                )
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                val copyLabel = stringResource(R.string.copy)
                val exportLabel = stringResource(R.string.export)
                TextButton(
                    onClick = onCopy,
                    modifier =
                        Modifier.explainOnLongPress(
                            Explanation(
                                copyLabel,
                                stringResource(R.string.explanation_organize_keys_result_copy),
                            ),
                        ),
                ) {
                    ProfileKeyActionLabel(
                        stringResource(if (copied) R.string.copied else R.string.copy),
                        copyGlyph(copied),
                    )
                }
                TextButton(
                    onClick = onExport,
                    modifier =
                        Modifier.explainOnLongPress(
                            Explanation(
                                exportLabel,
                                stringResource(R.string.explanation_organize_keys_result_export),
                            ),
                        ),
                ) { ProfileKeyActionLabel(exportLabel, R.drawable.ic_download) }
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss) {
                ProfileKeyActionLabel(stringResource(R.string.hide), R.drawable.ic_visibility_off)
            }
        },
    )
}

/** Strength label and a linear meter in the strength colour, without the stop indicator. */
@Suppress("FunctionNaming")
@Composable
private fun ExportPasswordStrengthIndicator(strength: EncryptedBackupPassphraseStrength) {
    val color = strength.color()
    Column(
        modifier = Modifier.fillMaxWidth().testTag("profile_keys.password_strength"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.password_strength), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(strength.labelRes()), color = color, style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { strength.progress() },
            modifier = Modifier.fillMaxWidth(),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            drawStopIndicator = {},
        )
    }
}

@StringRes
internal fun EncryptedBackupPassphraseStrength.labelRes(): Int =
    when (this) {
        EncryptedBackupPassphraseStrength.TooShort -> R.string.encrypted_backup_strength_too_short
        EncryptedBackupPassphraseStrength.Weak -> R.string.encrypted_backup_strength_weak
        EncryptedBackupPassphraseStrength.Fair -> R.string.encrypted_backup_strength_fair
        EncryptedBackupPassphraseStrength.Strong -> R.string.encrypted_backup_strength_strong
    }

/** Progress fraction for a passphrase strength level. */
@Suppress("MagicNumber")
internal fun EncryptedBackupPassphraseStrength.progress(): Float =
    when (this) {
        EncryptedBackupPassphraseStrength.TooShort -> 0.15f
        EncryptedBackupPassphraseStrength.Weak -> 0.34f
        EncryptedBackupPassphraseStrength.Fair -> 0.67f
        EncryptedBackupPassphraseStrength.Strong -> 1f
    }

/** Colour for a passphrase strength level. */
@Composable
internal fun EncryptedBackupPassphraseStrength.color(): Color =
    when (this) {
        EncryptedBackupPassphraseStrength.TooShort -> MaterialTheme.colorScheme.error
        EncryptedBackupPassphraseStrength.Weak -> MaterialTheme.colorScheme.error
        EncryptedBackupPassphraseStrength.Fair -> MaterialTheme.colorScheme.tertiary
        EncryptedBackupPassphraseStrength.Strong -> MaterialTheme.colorScheme.primary
    }
