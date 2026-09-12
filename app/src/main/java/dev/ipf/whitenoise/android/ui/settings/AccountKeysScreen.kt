package dev.ipf.whitenoise.android.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.EncryptedBackupPassphraseStrength
import dev.ipf.whitenoise.android.core.encryptedBackupPassphraseInputsValid
import dev.ipf.whitenoise.android.core.encryptedBackupPassphraseStrength
import dev.ipf.whitenoise.android.state.SignOutCompletion
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.WipeReport
import dev.ipf.whitenoise.android.state.WipeStage
import dev.ipf.whitenoise.android.state.WipeStageReport
import dev.ipf.whitenoise.android.state.wipeReport
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSecureTextField
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether the destructive "Sign Out & Wipe" path is wired to Marmot's
 * [dev.ipf.marmotkit.Marmot.signOutAndWipe] FFI.
 */
private const val WIPE_ENGINE_FFI_AVAILABLE = true

internal const val WIPE_ACTION_TAG = "wipe-action"

/** The key the copy glyph last acknowledged; it shows a check for two seconds. */
private enum class CopiedProfileKey { Public, Private }

/** A confirmed export waiting for the user to pick a file; it expires like a revealed key does. */
private data class PendingKeyExport(
    val encrypted: Boolean,
    val content: String,
    val createdAtMillis: Long,
)

/** Timing and geometry the prototype fixes for Profile Keys. */
private object ProfileKeysDefaults {
    const val EXPIRY_MILLIS = 30_000L
    const val COPIED_MILLIS = 2_000L
    const val MASK = "••••••••••••••••••••••••••••••••"
    const val FILE_STEM_LENGTH = 16
    val ValueRowMinHeight = 64.dp
    val ValueRowEndInset = 4.dp
    val IconSize = 24.dp
}

/** Transient screen state; everything sensitive clears on stop and on dispose. */
private class ProfileKeysUiState {
    var privateKey by mutableStateOf<String?>(null)
    var revealRequested by mutableStateOf(false)
    var copiedKey by mutableStateOf<CopiedProfileKey?>(null)
    var pendingExport by mutableStateOf<PendingKeyExport?>(null)
    var passwordDialog by mutableStateOf(false)
    var rawExportDialog by mutableStateOf(false)
    var saveErrorDialog by mutableStateOf(false)
    var expiredExportDialog by mutableStateOf(false)
    var exportBusy by mutableStateOf(false)
    var wipeSheet by mutableStateOf(false)
    var wipeConfirm by mutableStateOf(false)
    var wipeConfirmInput by mutableStateOf("")
    val password = TextFieldState()
    val confirmation = TextFieldState()

    /** Hides the revealed key. */
    fun hidePrivateKey() {
        revealRequested = false
        privateKey = null
    }

    /** Clears both export password fields. */
    fun clearPasswords() {
        password.edit { replace(0, length, "") }
        confirmation.edit { replace(0, length, "") }
    }

    /** Closes the export dialogs and hides the key; used before handing off to the file picker and on stop. */
    fun hideSensitive() {
        hidePrivateKey()
        rawExportDialog = false
        passwordDialog = false
        clearPasswords()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AccountKeysScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
) {
    // The screen surfaces the raw nsec; keep it out of Recents thumbnails and
    // screenshots, matching the encrypted-backup sheet's posture.
    WindowSecureFlag()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = appState.activeAccount
    val accountIdHex = active?.accountIdHex
    val npub = accountIdHex?.let(appState::npubForDisplay).orEmpty()
    val hasLocalKey = active?.localSigning == true
    val publicKeyLabel = stringResource(R.string.public_key)
    val privateKeyLabel = stringResource(R.string.private_key)
    val ui = remember(accountIdHex) { ProfileKeysUiState() }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val request = ui.pendingExport
            ui.pendingExport = null
            when {
                uri == null -> Unit
                request == null || request.isExpired() -> ui.expiredExportDialog = true
                else -> ui.saveErrorDialog = !writeExport(context, uri, request.content)
            }
        }

    fun launchExport(
        encrypted: Boolean,
        content: String,
    ) {
        ui.pendingExport = PendingKeyExport(encrypted, content, SystemClock.elapsedRealtime())
        ui.hideSensitive()
        val stem = npub.take(ProfileKeysDefaults.FILE_STEM_LENGTH)
        val filename = if (encrypted) "$stem-white-noise-key.wnkey.txt" else "$stem-white-noise-key.txt"
        if (runCatching { exportLauncher.launch(filename) }.isFailure) {
            ui.pendingExport = null
            ui.saveErrorDialog = true
        }
    }

    fun beginExport(encrypted: Boolean) {
        if (!hasLocalKey || ui.exportBusy || ui.pendingExport != null) return
        val passphrase = ui.password.text.toString()
        ui.exportBusy = true
        scope.launch {
            try {
                val content =
                    if (encrypted) {
                        appState.exportEncryptedSecretKeyBackup(passphrase)
                    } else {
                        appState.exportActiveAccountNsec()
                    }
                if (content != null) launchExport(encrypted, content) else ui.saveErrorDialog = true
            } finally {
                ui.exportBusy = false
            }
        }
    }

    ProfileKeysEffects(appState = appState, ui = ui, lifecycle = lifecycle)

    SettingsScaffold(
        title = stringResource(R.string.settings_profile_keys),
        onBack = onBack,
        contentWindowInsets = contentWindowInsets,
    ) {
        ProfileKeysList(
            npub = npub,
            hasLocalKey = hasLocalKey,
            showWipe = WIPE_ENGINE_FFI_AVAILABLE && active != null,
            ui = ui,
            onCopyPublic = {
                copyToClipboard(context, publicKeyLabel, npub)
                ui.copiedKey = CopiedProfileKey.Public
            },
            onToggleReveal = { if (ui.revealRequested) ui.hidePrivateKey() else ui.revealRequested = true },
            onCopyPrivate = {
                scope.launch {
                    val secret = ui.privateKey ?: appState.exportActiveAccountNsec()
                    if (secret != null) {
                        copyToClipboard(context, privateKeyLabel, secret, sensitive = true)
                        ui.copiedKey = CopiedProfileKey.Private
                    }
                }
            },
            onExportEncrypted = { ui.passwordDialog = true },
            onExportRaw = { ui.rawExportDialog = true },
            onWipe = { ui.wipeSheet = true },
        )
    }
    ProfileKeysDialogs(ui = ui, canExport = hasLocalKey, onExport = ::beginExport)
    AccountWipeFlow(appState = appState, ui = ui)
}

/** Reveal loading and expiry, pending-export expiry, copied-glyph reset, and hiding everything sensitive on stop. */
@Suppress("FunctionNaming")
@Composable
private fun ProfileKeysEffects(
    appState: WhiteNoiseAppState,
    ui: ProfileKeysUiState,
    lifecycle: Lifecycle,
) {
    LaunchedEffect(ui.revealRequested) {
        if (!ui.revealRequested) {
            ui.privateKey = null
            return@LaunchedEffect
        }
        val exported = appState.exportActiveAccountNsec()
        if (exported == null) {
            ui.revealRequested = false
            return@LaunchedEffect
        }
        ui.privateKey = exported
        delay(ProfileKeysDefaults.EXPIRY_MILLIS)
        ui.hidePrivateKey()
    }
    LaunchedEffect(ui.pendingExport) {
        val request = ui.pendingExport ?: return@LaunchedEffect
        delay(ProfileKeysDefaults.EXPIRY_MILLIS)
        if (ui.pendingExport === request) {
            ui.pendingExport = null
            ui.expiredExportDialog = true
        }
    }
    LaunchedEffect(ui.copiedKey) {
        if (ui.copiedKey != null) {
            delay(ProfileKeysDefaults.COPIED_MILLIS)
            ui.copiedKey = null
        }
    }
    DisposableEffect(lifecycle, ui) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) ui.hideSensitive()
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            ui.hideSensitive()
            ui.pendingExport = null
        }
    }
}

/** The three prototype groups: public key, private key (local signing only) and export, then the wipe action. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
private fun ProfileKeysList(
    npub: String,
    hasLocalKey: Boolean,
    showWipe: Boolean,
    ui: ProfileKeysUiState,
    onCopyPublic: () -> Unit,
    onToggleReveal: () -> Unit,
    onCopyPrivate: () -> Unit,
    onExportEncrypted: () -> Unit,
    onExportRaw: () -> Unit,
    onWipe: () -> Unit,
) {
    val revealed = ui.privateKey != null
    val exportsEnabled = ui.pendingExport == null && !ui.exportBusy
    SettingsList {
        item { SettingsSection(stringResource(R.string.public_key)) }
        item {
            SettingsGroup {
                row("public_key") { context ->
                    ProfileKeyValueRow(
                        context = context,
                        value = npub,
                        valueModifier = Modifier.testTag("profile_keys.public_key_value"),
                    ) {
                        val copied = ui.copiedKey == CopiedProfileKey.Public
                        IconButton(onClick = onCopyPublic) {
                            Icon(
                                painter = painterResource(copyGlyph(copied)),
                                contentDescription =
                                    stringResource(
                                        if (copied) R.string.public_key_copied else R.string.copy_public_key,
                                    ),
                            )
                        }
                    }
                }
            }
        }
        item { ProfileKeySupportingText(stringResource(R.string.profile_public_key_help)) }
        if (!hasLocalKey) {
            item {
                SettingsCallout(
                    title = stringResource(R.string.access_amber_signed_in),
                    text = stringResource(R.string.access_amber_owns_key),
                    modifier = Modifier.testTag("profile_keys.amber_info"),
                )
            }
        } else {
            item { SettingsSection(stringResource(R.string.private_key)) }
            item {
                SettingsGroup {
                    row("private_key") { context ->
                        PrivateKeyValueRow(context, ui.privateKey, revealed, onToggleReveal)
                    }
                    row("copy_private_key") { context ->
                        val copied = ui.copiedKey == CopiedProfileKey.Private
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.copy_private_key),
                            onClick = onCopyPrivate,
                            leading = {
                                Icon(
                                    painter = painterResource(copyGlyph(copied)),
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
            }
            item { ProfileKeySupportingText(stringResource(R.string.profile_private_key_help)) }
            item { SettingsSection(stringResource(R.string.export)) }
            item {
                SettingsGroup {
                    row("export_encrypted") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.export_encrypted_private_key),
                            onClick = onExportEncrypted,
                            enabled = exportsEnabled,
                            leading = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
                        )
                    }
                    row("export_raw") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.export_nsec),
                            onClick = onExportRaw,
                            modifier = Modifier.testTag("profile_keys.export_raw"),
                            enabled = exportsEnabled,
                            leading = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                        )
                    }
                }
            }
        }
        if (showWipe) {
            item {
                SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.Section)) {
                    row("wipe") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.sign_out_and_wipe),
                            onClick = onWipe,
                            modifier = Modifier.testTag(WIPE_ACTION_TAG),
                            destructive = true,
                            leading = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Masked or revealed private key with the visibility toggle; the value's semantics say which state it is in. */
@Suppress("FunctionNaming")
@Composable
private fun PrivateKeyValueRow(
    context: SettingsRowContext,
    privateKey: String?,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
) {
    val stateDescription =
        stringResource(if (revealed) R.string.private_key_revealed else R.string.private_key_hidden)
    ProfileKeyValueRow(
        context = context,
        value = privateKey ?: ProfileKeysDefaults.MASK,
        overflow = if (revealed) TextOverflow.MiddleEllipsis else TextOverflow.Clip,
        valueModifier =
            Modifier
                .testTag("profile_keys.private_key_value")
                .clearAndSetSemantics { contentDescription = stateDescription },
    ) {
        IconButton(onClick = onToggleReveal) {
            Icon(
                painter = painterResource(if (revealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                contentDescription =
                    stringResource(if (revealed) R.string.hide_private_key else R.string.show_private_key),
            )
        }
    }
}

/** 64 dp row with a monospace, middle-ellipsized key value and one trailing icon action at the 4 dp edge. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun ProfileKeyValueRow(
    context: SettingsRowContext,
    value: String,
    valueModifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.MiddleEllipsis,
    trailingAction: @Composable () -> Unit,
) {
    Surface(
        color = context.containerColor,
        shape = context.shapes.shape,
        modifier = Modifier.fillMaxWidth().settingsRowBorder(context, editable = true),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ProfileKeysDefaults.ValueRowMinHeight)
                    .padding(start = WhiteNoiseSpacing.CompactScreenMargin, end = ProfileKeysDefaults.ValueRowEndInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                modifier = valueModifier.weight(1f),
                maxLines = 1,
                overflow = overflow,
                fontFamily = FontFamily.Monospace,
            )
            trailingAction()
        }
    }
}

/** Helper copy under a key group at the 32 dp content line. */
@Suppress("FunctionNaming")
@Composable
private fun ProfileKeySupportingText(text: String) {
    Text(
        text = text,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = WhiteNoiseSpacing.SettingsSectionInset,
                    end = WhiteNoiseSpacing.SettingsSectionInset,
                    top = WhiteNoiseSpacing.Related,
                ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** The export dialogs: raw consequence, encrypted password, save failure and expiry. */
@Suppress("FunctionNaming")
@Composable
private fun ProfileKeysDialogs(
    ui: ProfileKeysUiState,
    canExport: Boolean,
    onExport: (encrypted: Boolean) -> Unit,
) {
    if (ui.rawExportDialog && canExport) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { ui.rawExportDialog = false },
            title = { Text(stringResource(R.string.keep_your_private_key_safe)) },
            text = { Text(stringResource(R.string.export_private_key_consequence)) },
            confirmButton = {
                TextButton(onClick = { onExport(false) }) {
                    Text(stringResource(R.string.export_nsec), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { ui.rawExportDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (ui.passwordDialog && canExport) {
        ExportPasswordDialog(
            password = ui.password,
            confirmation = ui.confirmation,
            busy = ui.exportBusy,
            onConfirm = { onExport(true) },
            onDismiss = {
                ui.passwordDialog = false
                ui.clearPasswords()
            },
        )
    }
    if (ui.saveErrorDialog) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { ui.saveErrorDialog = false },
            title = { Text(stringResource(R.string.couldnt_save_file)) },
            text = { Text(stringResource(R.string.choose_another_location_and_try_again)) },
            confirmButton = {
                TextButton(onClick = { ui.saveErrorDialog = false }) { Text(stringResource(R.string.ok)) }
            },
        )
    }
    if (ui.expiredExportDialog) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { ui.expiredExportDialog = false },
            title = { Text(stringResource(R.string.key_export_expired_title)) },
            text = { Text(stringResource(R.string.key_export_expired_body)) },
            confirmButton = {
                TextButton(onClick = { ui.expiredExportDialog = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

/** Two secure fields, the mismatch or help line, and the strength meter; Export enables once both fields agree. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun ExportPasswordDialog(
    password: TextFieldState,
    confirmation: TextFieldState,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val passwordValue = password.text.toString()
    val confirmationValue = confirmation.text.toString()
    val mismatch = confirmationValue.isNotEmpty() && passwordValue != confirmationValue
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.encrypted_private_key)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                WhiteNoiseSecureTextField(
                    state = password,
                    modifier = Modifier.fillMaxWidth().testTag("profile_keys.export_password"),
                    label = { Text(stringResource(R.string.password)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                )
                WhiteNoiseSecureTextField(
                    state = confirmation,
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
            TextButton(
                enabled = !busy && encryptedBackupPassphraseInputsValid(passwordValue, confirmationValue),
                onClick = onConfirm,
            ) { Text(stringResource(R.string.export)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
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

/** The copy action's glyph: a check while the last copy is still fresh. */
@DrawableRes
private fun copyGlyph(copied: Boolean): Int = if (copied) R.drawable.ic_check else R.drawable.ic_content_copy

/** Whether a pending export has outlived the prototype's 30 s window. */
private fun PendingKeyExport.isExpired(): Boolean {
    val age = SystemClock.elapsedRealtime() - createdAtMillis
    return age >= ProfileKeysDefaults.EXPIRY_MILLIS
}

/** Writes an export to the document the user picked; false when the stream could not be written. */
private fun writeExport(
    context: Context,
    uri: Uri,
    content: String,
): Boolean =
    runCatching {
        checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(content) }
    }.isSuccess

/** Copies [text] to the clipboard; sensitive clips stay out of the clipboard preview and history. */
private fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
    sensitive: Boolean = false,
) {
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}

/** Sign out and wipe: teardown spinner, explanatory sheet and the typed confirmation, unchanged from before. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun AccountWipeFlow(
    appState: WhiteNoiseAppState,
    ui: ProfileKeysUiState,
) {
    if (appState.signOutInProgress) {
        Dialog(
            onDismissRequest = {},
            properties =
                DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        }
    }

    if (WIPE_ENGINE_FFI_AVAILABLE && ui.wipeSheet) {
        SignOutAndWipeSheet(
            onConfirm = {
                ui.wipeSheet = false
                ui.wipeConfirmInput = ""
                ui.wipeConfirm = true
            },
            onDismiss = { ui.wipeSheet = false },
        )
    }

    if (WIPE_ENGINE_FFI_AVAILABLE && ui.wipeConfirm) {
        // Type-to-confirm gate (#348): the destructive confirm button stays
        // disabled until the user types the confirmation keyword. The match is
        // case-insensitive and ignores surrounding whitespace. This is the last
        // stop before the engine destroys the local DB, MLS state, keychain
        // entry, and relay key packages, so a single tap must not be enough.
        val confirmKeyword = stringResource(R.string.sign_out_and_wipe_confirm_keyword)
        val wipeConfirmed = ui.wipeConfirmInput.trim().equals(confirmKeyword, ignoreCase = true)
        WhiteNoiseAlertDialog(
            onDismissRequest = {
                ui.wipeConfirm = false
                ui.wipeConfirmInput = ""
            },
            title = { Text(stringResource(R.string.sign_out_and_wipe_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.sign_out_and_wipe_confirm_body))
                    Text(
                        stringResource(R.string.sign_out_and_wipe_confirm_instruction, confirmKeyword),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = ui.wipeConfirmInput,
                        onValueChange = { ui.wipeConfirmInput = it },
                        label = { Text(stringResource(R.string.sign_out_and_wipe_confirm_field_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                autoCorrectEnabled = false,
                            ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ui.wipeConfirm = false
                        ui.wipeConfirmInput = ""
                        // Run on the process-lifetime mutation scope, not this
                        // screen's rememberCoroutineScope. signOutAndWipeActiveAccount
                        // flips activeAccountRef partway through and keeps suspending
                        // (push teardown, notification refresh); the account-change nav
                        // reset in MainShell then pops AccountKeysScreen out of composition,
                        // which would cancel a screen-scoped coroutine before the wipe
                        // finishes and before the outcome is presented (#547).
                        //
                        // wipeInProgress (not signOutInProgress) drives the
                        // app-root staged progress sheet (#350), which survives
                        // that nav reset.
                        appState.wipeInProgress = true
                        appState.launchMutation {
                            try {
                                val outcome = appState.signOutAndWipeActiveAccount()
                                if (outcome == null) {
                                    // Total FFI failure: nothing was wiped and the
                                    // runtime state was restored — this one is
                                    // worth a bug report, so keep it copyable.
                                    appState.present(R.string.toast_couldnt_wipe_account, copyable = true)
                                } else {
                                    val report = wipeReport(outcome)
                                    if (report.clean) {
                                        appState.presentTransient(R.string.toast_signed_out_and_wiped)
                                    } else {
                                        // The app-root sheet lists every
                                        // incomplete stage. If local cleanup
                                        // failed, AppState restored the active
                                        // session instead of tearing it down.
                                        appState.pendingWipeReport = report
                                    }
                                }
                            } finally {
                                appState.wipeInProgress = false
                            }
                        }
                    },
                    enabled = wipeConfirmed,
                ) {
                    Text(
                        stringResource(R.string.wipe),
                        color =
                            if (wipeConfirmed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        ui.wipeConfirm = false
                        ui.wipeConfirmInput = ""
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
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

internal fun EncryptedBackupPassphraseStrength.progress(): Float =
    when (this) {
        EncryptedBackupPassphraseStrength.TooShort -> 0.15f
        EncryptedBackupPassphraseStrength.Weak -> 0.34f
        EncryptedBackupPassphraseStrength.Fair -> 0.67f
        EncryptedBackupPassphraseStrength.Strong -> 1f
    }

@Composable
internal fun EncryptedBackupPassphraseStrength.color(): Color =
    when (this) {
        EncryptedBackupPassphraseStrength.TooShort -> MaterialTheme.colorScheme.error
        EncryptedBackupPassphraseStrength.Weak -> MaterialTheme.colorScheme.error
        EncryptedBackupPassphraseStrength.Fair -> MaterialTheme.colorScheme.tertiary
        EncryptedBackupPassphraseStrength.Strong -> MaterialTheme.colorScheme.primary
    }

/**
 * Runs the confirmed sign-out on the mutation scope and reports its outcome. The Settings hub and
 * the keys screen share this so both entry points tear the account down identically.
 */
internal fun signOutActiveAccount(
    appState: WhiteNoiseAppState,
    deleteKeyPackages: Boolean,
) {
    appState.signOutInProgress = true
    // Mutation scope, not the screen scope: signOutActiveAccount()
    // flips activeAccountRef before its disk-media wipe finishes,
    // and the account-change nav reset pops this screen — a
    // screen-scoped job would be cancelled mid-teardown.
    appState.launchMutation {
        try {
            when (appState.signOutActiveAccount(deleteKeyPackages)) {
                SignOutCompletion.Complete -> appState.presentTransient(R.string.toast_signed_out)
                // Local sign-out completed but the engine call
                // failed or reported relay cleanup failures. This
                // is informational and not copyable (#966); MDK
                // does not retain a retry queue for the deletions.
                SignOutCompletion.RelayCleanupIncomplete ->
                    appState.present(R.string.toast_signed_out_relay_cleanup_incomplete)
                // MDK kept the account active, so the screen and
                // all account-scoped state remain intact.
                SignOutCompletion.AccountCleanupIncomplete ->
                    appState.present(R.string.toast_couldnt_sign_out)
                null -> Unit
            }
        } finally {
            appState.signOutInProgress = false
        }
    }
}

/**
 * Non-destructive sign-out sheet (#348, #349). Explains what stays on device
 * and what changes, offers the "Delete key packages from relays" toggle
 * (default ON — passed through to the engine `sign_out` FFI), then performs
 * the actual sign-out via [onConfirm].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun SignOutSheet(
    onConfirm: (deleteKeyPackages: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var deleteKeyPackages by remember { mutableStateOf(true) }
    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.sign_out_sheet_title), style = MaterialTheme.typography.titleLarge)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.sign_out_sheet_stays_heading),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.sign_out_sheet_stays_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.sign_out_sheet_changes_heading),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.sign_out_sheet_changes_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = deleteKeyPackages,
                        role = Role.Switch,
                        onValueChange = { deleteKeyPackages = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.sign_out_delete_key_packages_label))
                    Text(
                        stringResource(R.string.sign_out_delete_key_packages_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = deleteKeyPackages, onCheckedChange = null)
            }
            Button(onClick = { onConfirm(deleteKeyPackages) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sign_out))
            }
        }
    }
}

/**
 * Destructive "Sign Out & Wipe" sheet (#348). Lists exactly what gets destroyed
 * and that signing back in starts fresh, then hands off to a type-to-confirm
 * dialog via [onConfirm]. Only shown when the engine FFI exists
 * ([WIPE_ENGINE_FFI_AVAILABLE]); the copy describes the full teardown the engine
 * sub-issue will perform.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignOutAndWipeSheet(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.sign_out_and_wipe_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(stringResource(R.string.sign_out_and_wipe_sheet_intro))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.sign_out_and_wipe_sheet_destroyed_heading),
                    style = MaterialTheme.typography.titleSmall,
                )
                WipeBullet(stringResource(R.string.sign_out_and_wipe_sheet_destroyed_db))
                WipeBullet(stringResource(R.string.sign_out_and_wipe_sheet_destroyed_keychain))
                WipeBullet(stringResource(R.string.sign_out_and_wipe_sheet_destroyed_relays))
            }
            Text(
                stringResource(R.string.sign_out_and_wipe_sheet_fresh_start),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sign_out_and_wipe))
            }
        }
    }
}

@Composable
private fun WipeBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("\u2022", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Non-cancellable staged progress sheet shown while the engine's
 * `signOutAndWipe` runs (#350). The FFI is a single suspend call that reports
 * per-stage results only in its final [dev.ipf.marmotkit.WipeOutcomeFfi] \u2014
 * there is no streaming progress \u2014 so this deliberately renders all three
 * stages as in-flight with indeterminate expressive indicators rather than
 * faking real-time per-stage advancement; the stages are marked from the
 * outcome afterwards (partial-failure sheet, or success toast). Hosted at the
 * app root because the wipe pops the screen that started it mid-flight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WipeProgressSheet() {
    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        // The teardown cannot be cancelled: swallow scrim taps, reject the
        // hide gesture, keep back from dismissing, and drop the drag handle so
        // the sheet doesn't advertise a dismissal it won't honor.
        onDismissRequest = {},
        sheetState =
            rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden },
            ),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.wipe_progress_title), style = MaterialTheme.typography.titleLarge)
            WipeProgressStageRow(stringResource(R.string.wipe_stage_leaving_groups))
            WipeProgressStageRow(stringResource(R.string.wipe_stage_deleting_key_packages))
            WipeProgressStageRow(stringResource(R.string.wipe_stage_wiping_local_data))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WipeProgressStageRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LoadingIndicator(modifier = Modifier.size(28.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Post-wipe partial-failure sheet (#350): "Wipe finished with N issues", one
 * row per engine stage with its best-effort failures. Renders only the mapped
 * [WipeReport] snapshot \u2014 the wiped account's ref is invalid by the time this
 * shows, so nothing here may reach back into the FFI (see #956). Shown over
 * the post-wipe end state (next account's chat list, or onboarding).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WipeOutcomeSheet(
    report: WipeReport,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.wipe_finished_with_issues, report.issueCount, report.issueCount),
                style = MaterialTheme.typography.titleLarge,
            )
            report.stages.forEach { stage -> WipeOutcomeStageRow(stage) }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

@Composable
private fun WipeOutcomeStageRow(stage: WipeStageReport) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            if (stage.hasIssues) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (stage.hasIssues) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(wipeOutcomeStageSummary(stage))
            stage.failures.forEach { failure ->
                Text(
                    listOfNotNull(failure.subject, failure.reason.takeIf { it.isNotBlank() }).joinToString(" \u2014 "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun wipeOutcomeStageSummary(stage: WipeStageReport): String =
    when (stage.stage) {
        WipeStage.LeavingGroups ->
            stringResource(R.string.wipe_outcome_groups_left, stage.completedCount ?: 0)
        WipeStage.DeletingKeyPackages ->
            stringResource(R.string.wipe_outcome_key_packages_deleted, stage.completedCount ?: 0)
        WipeStage.WipingLocalData ->
            stringResource(
                if (stage.hasIssues) R.string.wipe_outcome_local_wipe_incomplete else R.string.wipe_outcome_local_wipe_done,
            )
    }
