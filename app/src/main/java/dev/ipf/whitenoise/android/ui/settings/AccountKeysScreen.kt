package dev.ipf.whitenoise.android.ui.settings

import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.encryptedBackupPassphraseInputsValid
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.exportActiveAccountNsec
import dev.ipf.whitenoise.android.state.exportEncryptedSecretKeyBackup
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.common.copySensitiveToClipboard
import dev.ipf.whitenoise.android.ui.common.copyToClipboard
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.delay

/**
 * Whether the destructive "Sign Out & Wipe" path is wired to Marmot's
 * [dev.ipf.marmotkit.Marmot.signOutAndWipe] FFI.
 */
internal const val WIPE_ENGINE_FFI_AVAILABLE = true

internal const val WIPE_ACTION_TAG = "wipe-action"

/** The key the copy glyph last acknowledged; it shows a check for two seconds. */
internal enum class CopiedProfileKey { Public, Private, Encrypted }

/** Explicit user-selected delivery of an account-bound native export. */
internal enum class KeyExportDestination { File, Preview, Share }

/** Timing and geometry the prototype fixes for Profile Keys. */
private object ProfileKeysDefaults {
    const val COPIED_MILLIS = 2_000L
    const val MASK = "••••••••••••••••••••••••••••••••"
    const val FILE_STEM_LENGTH = 16
    val ValueRowMinHeight = 64.dp
    val ValueRowEndInset = 4.dp
    val IconSize = 24.dp
}

/** Transient screen state; everything sensitive clears on stop and on dispose. */
internal class ProfileKeysUiState {
    var privateKey by mutableStateOf<String?>(null)
    var revealRequested by mutableStateOf(false)
    var copiedKey by mutableStateOf<CopiedProfileKey?>(null)
    var pendingExport by mutableStateOf<PendingKeyExport?>(null)
    var encryptedBackup by mutableStateOf<PendingKeyExport?>(null)
    var shareErrorDialog by mutableStateOf(false)
    var nativeExportError by mutableStateOf<Int?>(null)
    var passwordDialog by mutableStateOf(false)
    var rawExportDialog by mutableStateOf(false)
    var rawKeyConsent = Any()
    var saveErrorDialog by mutableStateOf(false)
    var expiredExportDialog by mutableStateOf(false)
    var exportBusy by mutableStateOf(false)
    var savingExport by mutableStateOf(false)
    var wipeSheet by mutableStateOf(false)
    var wipeConfirm by mutableStateOf(false)
    var wipeConfirmInput by mutableStateOf("")
    val exportOperation = ProfileKeyOperation()
    val copyOperation = ProfileKeyOperation()
    val revealOperation = ProfileKeyOperation()
    val saveOperation = ProfileKeyOperation()
    val password = TextFieldState()
    val confirmation = TextFieldState()

    /** Hides the revealed key. */
    fun hidePrivateKey() {
        revealOperation.cancel()
        revealRequested = false
        privateKey = null
    }

    /** Invalidates native work immediately while the inert confirmation finishes its exit. */
    fun cancelExportPreparation() {
        rawKeyConsent = Any()
        exportOperation.cancel()
        exportBusy = false
        clearPasswords()
    }

    /** Removes the confirmation after exit, or immediately when its sensitive owner is lost. */
    fun cancelExport() {
        cancelExportPreparation()
        rawExportDialog = false
        passwordDialog = false
        encryptedBackup = null
    }

    /** True when no export is being prepared, picked or written. */
    val exportIdle: Boolean
        get() = !exportBusy && pendingExport == null && !savingExport

    /**
     * Prevents delayed exports, file saves and feedback, clipboard writes and reveals after backgrounding or leaving
     * this account.
     */
    fun stopSensitiveOperations() {
        cancelExport()
        saveOperation.cancel()
        savingExport = false
        copyOperation.cancel()
        hidePrivateKey()
        copiedKey = null
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
        encryptedBackup = null
        clearPasswords()
    }
}

/** Presents account-bound key operations whose results are discarded after dismissal or lifecycle invalidation. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AccountKeysScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    rawExportAuthenticator: RawKeyExportAuthentication? = null,
) {
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
    val encryptedBackupLabel = stringResource(R.string.encrypted_backup_result_title)
    val shareTitle = stringResource(R.string.share_secret_key)
    val accountRef = appState.activeAccountRef
    val runtimeGeneration = appState.runtimeGeneration
    val ui = remember(accountIdHex, accountRef, runtimeGeneration) { ProfileKeysUiState() }
    var showSignOut by remember(ui) { mutableStateOf(false) }
    val rawAuthentication = rawExportAuthenticator ?: rememberRawKeyExportAuthentication(ui, appState)

    fun ownsAccount(): Boolean =
        accountIdHex != null &&
            appState.activeAccount?.accountIdHex == accountIdHex &&
            appState.activeAccountRef == accountRef &&
            appState.runtimeGeneration == runtimeGeneration

    fun canDeliverSecret(): Boolean =
        ownsAccount() &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            appState.activeAccount?.localSigning == true &&
            !appState.appLock.screenVisible &&
            !appState.signOutInProgress &&
            !appState.wipeInProgress

    fun saveExport(
        uri: Uri,
        content: String,
    ) {
        ui.savingExport = true
        val started =
            ui.saveOperation.start(
                scope = scope,
                canDeliver = ::canDeliverSecret,
                load = { writeKeyExport(content) { context.contentResolver.openOutputStream(uri) } },
                onResult = { saved -> ui.saveErrorDialog = !saved },
                onFinished = { ui.savingExport = false },
            )
        if (!started) ui.savingExport = false
    }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val request = ui.pendingExport
            ui.pendingExport = null
            when {
                uri == null || !canDeliverSecret() -> Unit
                request == null || request.isExpired() -> ui.expiredExportDialog = true
                else -> saveExport(uri, request.content)
            }
        }

    fun launchExport(
        encrypted: Boolean,
        content: String,
        createdAtMillis: Long = SystemClock.elapsedRealtime(),
    ) {
        ui.pendingExport = PendingKeyExport(encrypted, content, createdAtMillis)
        ui.hideSensitive()
        val stem = npub.take(ProfileKeysDefaults.FILE_STEM_LENGTH)
        val filename = if (encrypted) "$stem-white-noise-key.wnkey.txt" else "$stem-white-noise-key.txt"
        if (runCatching { exportLauncher.launch(filename) }.isFailure) {
            ui.pendingExport = null
            ui.saveErrorDialog = true
        }
    }

    fun beginExport(
        encrypted: Boolean,
        destination: KeyExportDestination,
    ) {
        if (!hasLocalKey || !ui.exportIdle || !canDeliverSecret()) return
        if (encrypted &&
            !encryptedBackupPassphraseInputsValid(
                ui.password.text.toString(),
                ui.confirmation.text.toString(),
            )
        ) {
            return
        }
        val passphrase = if (encrypted) ui.password.text.toString() else ""
        ui.clearPasswords()
        ui.exportBusy = true
        ui.exportOperation.start(
            scope = scope,
            canDeliver = ::canDeliverSecret,
            load = {
                if (encrypted) {
                    appState.exportEncryptedSecretKeyBackup(passphrase)
                } else {
                    appState.exportActiveAccountNsec()
                }
            },
            onResult = { content ->
                if (content == null) {
                    ui.nativeExportError =
                        if (encrypted) {
                            R.string.toast_couldnt_create_encrypted_backup
                        } else {
                            R.string.toast_couldnt_export_nsec
                        }
                } else {
                    when (destination) {
                        KeyExportDestination.File -> launchExport(encrypted, content)
                        KeyExportDestination.Preview -> {
                            ui.hideSensitive()
                            ui.copiedKey = null
                            ui.encryptedBackup = PendingKeyExport(true, content, SystemClock.elapsedRealtime())
                        }
                        KeyExportDestination.Share -> {
                            ui.hideSensitive()
                            ui.shareErrorDialog = !sharePrivateKey(context, shareTitle, content)
                        }
                    }
                }
            },
            onFinished = { ui.exportBusy = false },
        )
    }

    ProfileKeysEffects(ui, lifecycle, rawAuthentication::cancel)
    LaunchedEffect(hasLocalKey, appState.signOutInProgress, appState.wipeInProgress) {
        if (!hasLocalKey || appState.signOutInProgress || appState.wipeInProgress) {
            ui.stopSensitiveOperations()
            ui.pendingExport = null
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_profile_keys),
        onBack = onBack,
        contentWindowInsets = contentWindowInsets,
    ) {
        ProfileKeysList(
            identity =
                active?.let {
                    AccountKeysIdentity(
                        appState.displayName(it.accountIdHex),
                        npub,
                        appState.avatarUrl(it.accountIdHex),
                        it.localSigning,
                        it.running,
                        accountKeysConnectionLabel(appState),
                    )
                },
            npub = npub,
            hasLocalKey = hasLocalKey,
            remoteSigner = rememberRemoteSignerAccountActions(appState, accountIdHex),
            showWipe = WIPE_ENGINE_FFI_AVAILABLE && active != null,
            ui = ui,
            onCopyPublic = {
                if (copyToClipboard(context, publicKeyLabel, npub)) ui.copiedKey = CopiedProfileKey.Public
            },
            onToggleReveal = {
                if (ui.revealRequested) {
                    ui.hidePrivateKey()
                } else {
                    rawAuthentication.authorize(ui, ::canDeliverSecret) {
                        ui.revealRequested = true
                        ui.revealOperation.start(
                            scope = scope,
                            canDeliver = { ui.revealRequested && canDeliverSecret() },
                            load = { appState.exportActiveAccountNsec() },
                            onResult = { secret ->
                                ui.privateKey = secret
                                if (secret == null) ui.revealRequested = false
                            },
                        )
                    }
                }
            },
            onCopyPrivate = {
                rawAuthentication.authorize(ui, ::canDeliverSecret) {
                    ui.copyOperation.start(
                        scope = scope,
                        canDeliver = ::canDeliverSecret,
                        load = { ui.privateKey ?: appState.exportActiveAccountNsec() },
                        onResult = { secret ->
                            if (secret != null) {
                                copySensitiveToClipboard(context, privateKeyLabel, secret)
                                ui.copiedKey = CopiedProfileKey.Private
                            }
                        },
                    )
                }
            },
            onExportEncrypted = ui::openEncryptedExport,
            onExportRaw = ui::openRawExport,
            onWipe = { ui.wipeSheet = true },
            onSignOut = { if (ownsAccount()) showSignOut = true },
        )
    }
    ProfileKeysDialogs(
        ui = ui,
        canExport = hasLocalKey,
        onExport = rawAuthentication.gateExport(ui, ::canDeliverSecret, ::beginExport),
        cancelRawAuthentication = rawAuthentication::cancel,
        onCopyBackup = {
            val backup = ui.encryptedBackup
            if (backup != null && !backup.isExpired() && canDeliverSecret()) {
                copySensitiveToClipboard(context, encryptedBackupLabel, backup.content)
                ui.copiedKey = CopiedProfileKey.Encrypted
            } else {
                ui.encryptedBackup = null
            }
        },
        onExportBackup = {
            val backup = ui.encryptedBackup
            if (backup != null && !backup.isExpired() && canDeliverSecret()) {
                launchExport(true, backup.content, backup.createdAtMillis)
            } else {
                ui.encryptedBackup = null
                if (canDeliverSecret()) ui.expiredExportDialog = true
            }
        },
    )
    AccountWipeFlow(appState = appState, ui = ui)
    if (showSignOut) {
        SignOutSheet(
            onConfirm = { deleteKeyPackages ->
                showSignOut = false
                if (ownsAccount()) signOutActiveAccount(appState, deleteKeyPackages)
            },
            onDismiss = { showSignOut = false },
        )
    }
}

/** Reveal loading and expiry, pending-export expiry, copied-glyph reset, and hiding everything sensitive on stop. */
@Suppress("FunctionNaming")
@Composable
private fun ProfileKeysEffects(
    ui: ProfileKeysUiState,
    lifecycle: Lifecycle,
    cancelAuthentication: () -> Unit,
) {
    LaunchedEffect(ui.privateKey) {
        if (ui.privateKey != null) {
            delay(PRIVATE_KEY_EXPIRY_MILLIS)
            ui.hidePrivateKey()
        }
    }
    LaunchedEffect(ui.pendingExport) {
        val request = ui.pendingExport ?: return@LaunchedEffect
        delay(request.remainingMillis())
        if (ui.pendingExport === request) {
            ui.pendingExport = null
            ui.expiredExportDialog = true
        }
    }
    LaunchedEffect(ui.encryptedBackup) {
        val backup = ui.encryptedBackup ?: return@LaunchedEffect
        delay(backup.remainingMillis())
        if (ui.encryptedBackup === backup) ui.encryptedBackup = null
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
                if (event == Lifecycle.Event.ON_STOP) {
                    ui.stopSensitiveOperations()
                    cancelAuthentication()
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            ui.stopSensitiveOperations()
            ui.pendingExport = null
        }
    }
}

/** The three prototype groups: public key, private key (local signing only) and export, then the wipe action. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
private fun ProfileKeysList(
    identity: AccountKeysIdentity?,
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
    onSignOut: () -> Unit,
    remoteSigner: RemoteSignerAccountActions? = null,
) {
    val revealed = ui.privateKey != null
    val exportsEnabled = ui.exportIdle
    SettingsList {
        if (identity == null) {
            item(key = "search.AccountKeys.1") { SettingsExplainer(stringResource(R.string.no_active_account_period)) }
            return@SettingsList
        }
        item(key = "search.AccountKeys.2") { AccountKeysIdentityHeader(identity) }
        item(key = "search.AccountKeys.3") { SettingsSection(stringResource(R.string.public_key)) }
        item(key = "search.AccountKeys.4") {
            SettingsGroup(destination = SettingsDetail.AccountKeys, surface = "account-public-key") {
                row("public_key") { context ->
                    ProfileKeyValueRow(
                        context = context,
                        value = npub,
                        valueModifier =
                            Modifier
                                .testTag("profile_keys.public_key_value")
                                .explainOnLongPress(
                                    Explanation(
                                        stringResource(R.string.public_key),
                                        stringResource(R.string.explanation_account_keys_public_key),
                                    ),
                                ),
                        fullPublicKey = true,
                    ) {
                        val copied = ui.copiedKey == CopiedProfileKey.Public
                        val copyPublicLabel = stringResource(R.string.copy_public_key)
                        IconButton(
                            onClick = onCopyPublic,
                            modifier =
                                Modifier.explainOnLongPress(
                                    Explanation(
                                        copyPublicLabel,
                                        stringResource(R.string.explanation_organize_keys_copy_public),
                                    ),
                                ),
                        ) {
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
        item(key = "search.AccountKeys.5") {
            ProfileKeySupportingText(stringResource(R.string.profile_public_key_help))
        }
        if (remoteSigner != null) {
            remoteSignerAccountItems(remoteSigner)
        } else if (!hasLocalKey) {
            item(key = "search.AccountKeys.6") {
                SettingsCallout(
                    title = stringResource(R.string.access_amber_signed_in),
                    text = stringResource(R.string.access_amber_owns_key),
                    modifier = Modifier.testTag("profile_keys.amber_info"),
                )
            }
        } else {
            item(key = "search.AccountKeys.7") { SettingsSection(stringResource(R.string.private_key)) }
            item(key = "search.AccountKeys.8") {
                SettingsGroup {
                    row("private_key") { context ->
                        PrivateKeyValueRow(context, ui.privateKey, revealed, onToggleReveal)
                    }
                    row("copy_private_key") { context ->
                        val copied = ui.copiedKey == CopiedProfileKey.Private
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.copy_private_key),
                            explanation = stringResource(R.string.explanation_account_keys_copy_private),
                            onClick = onCopyPrivate,
                            leading = {
                                SettingsLeadingIcon(copyGlyph(copied))
                            },
                        )
                    }
                }
            }
            item(key = "search.AccountKeys.9") {
                ProfileKeySupportingText(stringResource(R.string.profile_private_key_help))
                ProfileKeySupportingText(stringResource(R.string.profile_private_key_use_help))
            }
            item(key = "search.AccountKeys.10") { SettingsSection(stringResource(R.string.export)) }
            item(key = "search.AccountKeys.11") {
                SettingsGroup {
                    row("export_encrypted") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.export_encrypted_private_key),
                            explanation = stringResource(R.string.explanation_account_keys_export_encrypted),
                            onClick = onExportEncrypted,
                            enabled = exportsEnabled,
                            leading = { SettingsLeadingIcon(R.drawable.ic_lock) },
                        )
                    }
                    row("export_raw") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.export_nsec),
                            explanation = stringResource(R.string.explanation_account_keys_export_raw),
                            onClick = onExportRaw,
                            modifier = Modifier.testTag("profile_keys.export_raw"),
                            enabled = exportsEnabled,
                            leading = { SettingsLeadingIcon(R.drawable.ic_download) },
                        )
                    }
                }
            }
        }
        item(key = "search.AccountKeys.12") { SettingsSection(stringResource(R.string.account_keys_session)) }
        item(key = "search.AccountKeys.13") {
            SettingsGroup {
                row("sign_out") { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(R.string.sign_out),
                        explanation = stringResource(R.string.explanation_account_keys_sign_out),
                        onClick = onSignOut,
                        modifier = Modifier.testTag("profile_keys.sign_out"),
                        leading = {
                            SettingsLeadingIcon(R.drawable.ic_settings_logout, tint = MaterialTheme.colorScheme.error)
                        },
                    )
                }
            }
        }
        if (showWipe) {
            item(key = "search.AccountKeys.14") {
                SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.Section)) {
                    row("wipe") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.sign_out_and_wipe),
                            explanation = stringResource(R.string.explanation_account_keys_wipe),
                            onClick = onWipe,
                            modifier = Modifier.testTag(WIPE_ACTION_TAG),
                            destructive = true,
                            leading = {
                                SettingsLeadingIcon(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.error)
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
                .explainOnLongPress(
                    Explanation(
                        stringResource(R.string.private_key),
                        stringResource(R.string.explanation_account_keys_private_key),
                    ),
                ).clearAndSetSemantics { contentDescription = stateDescription },
    ) {
        val revealLabel = stringResource(if (revealed) R.string.hide_private_key else R.string.show_private_key)
        IconButton(
            onClick = onToggleReveal,
            modifier =
                Modifier.explainOnLongPress(
                    Explanation(revealLabel, stringResource(R.string.explanation_account_keys_reveal)),
                ),
        ) {
            Icon(
                painter = painterResource(if (revealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                contentDescription = revealLabel,
            )
        }
    }
}

/**
 * 64 dp row with a monospace key value and one trailing icon action at the 4 dp edge. A secret value stays on one
 * middle-ellipsized line; with [fullPublicKey] the public key shows in full, grouped for comparison (#3321).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun ProfileKeyValueRow(
    context: SettingsRowContext,
    value: String,
    valueModifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.MiddleEllipsis,
    fullPublicKey: Boolean = false,
    trailingAction: @Composable () -> Unit,
) {
    Surface(
        color = context.containerColor,
        shape = context.shapes.shape,
        modifier = Modifier.fillMaxWidth().settingsRowBorder(context),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ProfileKeysDefaults.ValueRowMinHeight)
                    .padding(start = WhiteNoiseSpacing.CompactScreenMargin, end = ProfileKeysDefaults.ValueRowEndInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (fullPublicKey) {
                // The public key in full, grouped for comparison (#3321); secret values keep one ellipsized line.
                FullPublicKey(
                    npub = value,
                    modifier = valueModifier.weight(1f).padding(vertical = WhiteNoiseSpacing.Related),
                    textAlign = TextAlign.Start,
                )
            } else {
                Text(
                    text = value,
                    modifier = valueModifier.weight(1f),
                    maxLines = 1,
                    overflow = overflow,
                    fontFamily = FontFamily.Monospace,
                )
            }
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

/** The copy action's glyph: a check while the last copy is still fresh. */
@DrawableRes
internal fun copyGlyph(copied: Boolean): Int = if (copied) R.drawable.ic_check else R.drawable.ic_content_copy
