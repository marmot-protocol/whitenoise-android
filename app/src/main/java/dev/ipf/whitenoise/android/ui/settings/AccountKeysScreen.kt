package dev.ipf.whitenoise.android.ui.settings

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ENCRYPTED_BACKUP_MIN_PASSPHRASE_LENGTH
import dev.ipf.whitenoise.android.core.EncryptedBackupPassphraseStrength
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.encryptedBackupPassphraseInputsValid
import dev.ipf.whitenoise.android.core.encryptedBackupPassphraseStrength
import dev.ipf.whitenoise.android.core.groupedEncryptedBackup
import dev.ipf.whitenoise.android.state.SignOutCompletion
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.WipeReport
import dev.ipf.whitenoise.android.state.WipeStage
import dev.ipf.whitenoise.android.state.WipeStageReport
import dev.ipf.whitenoise.android.state.wipeReport
import dev.ipf.whitenoise.android.ui.common.CopyableValueRow
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.common.lifecycleOwner
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether the destructive "Sign Out & Wipe" path is wired to Marmot's
 * [dev.ipf.marmotkit.Marmot.signOutAndWipe] FFI.
 */
private const val WIPE_ENGINE_FFI_AVAILABLE = true

internal enum class IdentitySecretExportAction {
    Request,
    ToggleReveal,
    Cancel,
}

internal data class IdentitySecretExportState(
    val confirmationVisible: Boolean = false,
    val revealed: Boolean = false,
)

internal fun identitySecretExportState(
    state: IdentitySecretExportState,
    action: IdentitySecretExportAction,
): IdentitySecretExportState =
    when (action) {
        IdentitySecretExportAction.Request -> IdentitySecretExportState(confirmationVisible = true)
        IdentitySecretExportAction.ToggleReveal ->
            if (state.confirmationVisible) state.copy(revealed = !state.revealed) else state
        IdentitySecretExportAction.Cancel -> IdentitySecretExportState()
    }

internal fun maskedIdentitySecret(
    secret: String,
    revealed: Boolean,
): String = if (revealed) secret else "•".repeat(MASKED_IDENTITY_SECRET_LENGTH)

internal const val IDENTITY_SECRET_EXPORT_CONTENT_TAG = "identity-secret-export-content"
private const val MASKED_IDENTITY_SECRET_LENGTH = 24

internal suspend fun exportIdentitySecretForSession(
    sessionId: Long,
    exporter: suspend () -> String?,
    isSessionActive: (Long) -> Boolean,
    onExported: (String) -> Unit,
): Boolean {
    val exported = exporter()
    return if (exported != null && isSessionActive(sessionId)) {
        onExported(exported)
        true
    } else {
        false
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
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val active = appState.activeAccount
    var showSignOutSheet by remember { mutableStateOf(false) }
    var showWipeSheet by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var showEncryptedBackupSheet by remember { mutableStateOf(false) }
    var secretExportState by remember { mutableStateOf(IdentitySecretExportState()) }
    var secretForExport by remember { mutableStateOf<String?>(null) }
    var secretExportSessionId by remember { mutableLongStateOf(0L) }
    var secretExportJob by remember { mutableStateOf<Job?>(null) }
    var secretExportInProgress by remember { mutableStateOf(false) }
    // Type-to-confirm input for the destructive wipe (#348). Reset whenever the
    // confirm dialog is dismissed so a previous match can't carry over into a
    // later open.
    var wipeConfirmInput by remember { mutableStateOf("") }
    val shareSecretKeyTitle = stringResource(R.string.share_secret_key)

    fun dismissSecretExport() {
        secretExportSessionId++
        secretExportJob?.cancel()
        secretExportJob = null
        secretExportInProgress = false
        secretForExport = null
        secretExportState = IdentitySecretExportState()
    }

    fun beginSecretExport() {
        dismissSecretExport()
        secretExportState =
            identitySecretExportState(
                IdentitySecretExportState(),
                IdentitySecretExportAction.Request,
            )
    }

    fun shareSecretKey(text: String) {
        val sendIntent =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                // Marks the payload as private so the share sheet and clipboard
                // keep the raw nsec out of previews, history, and logs.
                .putExtra(ClipDescription.EXTRA_IS_SENSITIVE, true)
        context.startActivity(
            Intent.createChooser(sendIntent, shareSecretKeyTitle),
        )
    }

    Scaffold(
        contentWindowInsets = contentWindowInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_and_keys)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.identity)) {
                    if (active == null) {
                        Text(stringResource(R.string.no_active_account_period), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        DiagnosticRow(stringResource(R.string.display_name), appState.displayName(active.accountIdHex))
                        val npub = appState.npubForDisplay(active.accountIdHex)
                        if (npub.isNotBlank()) {
                            CopyableValueRow(
                                label = stringResource(R.string.public_key),
                                value = npub,
                                displayValue = IdentityFormatter.short(npub, prefix = 10, suffix = 8),
                                clipboard = clipboard,
                            )
                        }
                        DiagnosticRow(stringResource(R.string.local_signing), stringResource(if (active.localSigning) R.string.yes else R.string.no))
                        DiagnosticRow(stringResource(R.string.status), stringResource(if (active.running) R.string.online else R.string.idle))
                    }
                }
            }
            if (active?.localSigning == true) {
                item {
                    SectionCard(title = stringResource(R.string.secret_key_backup)) {
                        Text(
                            stringResource(R.string.secret_key_backup_help),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                beginSecretExport()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.export_nsec))
                        }
                        OutlinedButton(
                            onClick = { showEncryptedBackupSheet = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.encrypted_backup_create))
                        }
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.account_session)) {
                    Text(
                        stringResource(R.string.sign_out_session_help),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Primary, non-destructive sign-out. Opens an explanatory
                    // sheet; the sheet's button performs the actual sign-out so
                    // the user reads what stays vs. changes before committing.
                    Button(
                        onClick = { showSignOutSheet = true },
                        enabled = active != null,
                        modifier = Modifier.fillMaxWidth().semantics { traversalIndex = 0f },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sign_out))
                    }
                    // Keep the irreversible action in its own terminal danger
                    // section so routine and destructive actions are distinct.
                    if (WIPE_ENGINE_FFI_AVAILABLE) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        )
                        OutlinedButton(
                            onClick = { showWipeSheet = true },
                            enabled = active != null,
                            modifier = Modifier.fillMaxWidth().semantics { traversalIndex = 1f },
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
        }
    }

    if (showEncryptedBackupSheet) {
        EncryptedBackupSheet(
            appState = appState,
            onDismiss = { showEncryptedBackupSheet = false },
        )
    }

    if (secretExportState.confirmationVisible) {
        val secret = secretForExport.orEmpty()
        AlertDialog(
            onDismissRequest = {
                dismissSecretExport()
            },
            title = { Text(stringResource(R.string.share_secret_key)) },
            text = {
                IdentitySecretExportContent(
                    state = secretExportState,
                    secret = secret,
                    onToggleReveal = {
                        if (secretExportState.revealed) {
                            secretForExport = null
                            secretExportState =
                                identitySecretExportState(
                                    secretExportState,
                                    IdentitySecretExportAction.ToggleReveal,
                                )
                        } else {
                            if (secretExportInProgress) return@IdentitySecretExportContent
                            val sessionId = secretExportSessionId
                            secretExportInProgress = true
                            secretExportJob =
                                scope.launch {
                                    try {
                                        exportIdentitySecretForSession(
                                            sessionId = sessionId,
                                            exporter = { appState.exportActiveAccountNsec() },
                                            isSessionActive = {
                                                it == secretExportSessionId && secretExportState.confirmationVisible
                                            },
                                            onExported = { exported ->
                                                secretForExport = exported
                                                secretExportState = secretExportState.copy(revealed = true)
                                            },
                                        )
                                    } finally {
                                        if (sessionId == secretExportSessionId) {
                                            secretExportInProgress = false
                                            secretExportJob = null
                                        }
                                    }
                                }
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cachedSecret = secretForExport
                        if (cachedSecret != null) {
                            dismissSecretExport()
                            shareSecretKey(cachedSecret)
                        } else if (!secretExportInProgress) {
                            val sessionId = secretExportSessionId
                            secretExportInProgress = true
                            secretExportJob =
                                scope.launch {
                                    try {
                                        exportIdentitySecretForSession(
                                            sessionId = sessionId,
                                            exporter = { appState.exportActiveAccountNsec() },
                                            isSessionActive = {
                                                it == secretExportSessionId && secretExportState.confirmationVisible
                                            },
                                            onExported = { exported ->
                                                secretForExport = null
                                                secretExportState = IdentitySecretExportState()
                                                secretExportSessionId++
                                                secretExportJob = null
                                                secretExportInProgress = false
                                                shareSecretKey(exported)
                                            },
                                        )
                                    } finally {
                                        if (sessionId == secretExportSessionId) {
                                            secretExportInProgress = false
                                            secretExportJob = null
                                        }
                                    }
                                }
                        }
                    },
                    enabled = !secretExportInProgress,
                ) {
                    Text(stringResource(R.string.share))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dismissSecretExport()
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showSignOutSheet) {
        SignOutSheet(
            onConfirm = { deleteKeyPackages ->
                showSignOutSheet = false
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
                            null -> Unit
                        }
                    } finally {
                        appState.signOutInProgress = false
                    }
                }
            },
            onDismiss = { showSignOutSheet = false },
        )
    }

    // Block the screen with a spinner while a sign-out / wipe teardown runs, so
    // the confirm doesn't leave the user staring at an unchanged screen until
    // navigation resets. Non-dismissible — the teardown can't be cancelled.
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

    if (WIPE_ENGINE_FFI_AVAILABLE && showWipeSheet) {
        SignOutAndWipeSheet(
            onConfirm = {
                showWipeSheet = false
                wipeConfirmInput = ""
                showWipeConfirm = true
            },
            onDismiss = { showWipeSheet = false },
        )
    }

    if (WIPE_ENGINE_FFI_AVAILABLE && showWipeConfirm) {
        // Type-to-confirm gate (#348): the destructive confirm button stays
        // disabled until the user types the confirmation keyword. The match is
        // case-insensitive and ignores surrounding whitespace. This is the last
        // stop before the engine destroys the local DB, MLS state, keychain
        // entry, and relay key packages, so a single tap must not be enough.
        val confirmKeyword = stringResource(R.string.sign_out_and_wipe_confirm_keyword)
        val wipeConfirmed = wipeConfirmInput.trim().equals(confirmKeyword, ignoreCase = true)
        AlertDialog(
            onDismissRequest = {
                showWipeConfirm = false
                wipeConfirmInput = ""
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
                        value = wipeConfirmInput,
                        onValueChange = { wipeConfirmInput = it },
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
                        showWipeConfirm = false
                        wipeConfirmInput = ""
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
                                        // Local wipe completed regardless; the
                                        // app-root sheet lists the best-effort
                                        // relay/group failures (#350).
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
                        showWipeConfirm = false
                        wipeConfirmInput = ""
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun IdentitySecretExportContent(
    state: IdentitySecretExportState,
    secret: String,
    onToggleReveal: () -> Unit,
) {
    val semanticLabel = stringResource(R.string.export_nsec)
    Column(
        modifier = Modifier.testTag(IDENTITY_SECRET_EXPORT_CONTENT_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.secret_key_backup_help),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = maskedIdentitySecret(secret, state.revealed),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clearAndSetSemantics { contentDescription = semanticLabel },
        )
        OutlinedButton(onClick = onToggleReveal) {
            Text(stringResource(if (state.revealed) R.string.hide else R.string.show))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EncryptedBackupSheet(
    appState: WhiteNoiseAppState,
    onDismiss: () -> Unit,
) {
    WindowSecureFlag()
    val context = LocalContext.current
    val lifecycleOwner = context.lifecycleOwner()
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var revealPassphrase by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun clearSensitiveState() {
        passphrase = ""
        confirmation = ""
        backup = null
        busy = false
    }

    fun dismissAndClear() {
        clearSensitiveState()
        onDismiss()
    }

    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) {
            onDispose { }
        } else {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) {
                        dismissAndClear()
                    }
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(backup) {
        if (backup != null) {
            delay(60_000)
            backup = null
        }
    }

    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = { dismissAndClear() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.encrypted_backup_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.encrypted_backup_explainer),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val encryptedBackup = backup
            if (encryptedBackup == null) {
                EncryptedBackupPassphraseFields(
                    passphrase = passphrase,
                    confirmation = confirmation,
                    revealPassphrase = revealPassphrase,
                    onPassphraseChange = { passphrase = it },
                    onConfirmationChange = { confirmation = it },
                    onRevealToggle = { revealPassphrase = !revealPassphrase },
                )
                Text(
                    stringResource(R.string.encrypted_backup_short_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        val chosenPassphrase = passphrase
                        busy = true
                        scope.launch {
                            val exported = appState.exportEncryptedSecretKeyBackup(chosenPassphrase)
                            if (exported != null) {
                                passphrase = ""
                                confirmation = ""
                                backup = exported
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && encryptedBackupPassphraseInputsValid(passphrase, confirmation),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Lock, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.encrypted_backup_create))
                }
            } else {
                Text(
                    stringResource(R.string.encrypted_backup_result_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.encrypted_backup_result_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = amoledSurfaceBorderStroke(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        groupedEncryptedBackup(encryptedBackup),
                        modifier = Modifier.padding(16.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            // Flag the clip sensitive so Android 13+ doesn't render
                            // the passphrase-protected backup in the clipboard preview.
                            val clip = android.content.ClipData.newPlainText("encrypted backup", encryptedBackup)
                            clip.description.extras =
                                android.os.PersistableBundle().apply {
                                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                                }
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(clip)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.copy))
                    }
                    OutlinedButton(
                        onClick = { backup = null },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.hide))
                    }
                }
            }
        }
    }
}

@Composable
private fun EncryptedBackupPassphraseFields(
    passphrase: String,
    confirmation: String,
    revealPassphrase: Boolean,
    onPassphraseChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onRevealToggle: () -> Unit,
) {
    val strength = encryptedBackupPassphraseStrength(passphrase)
    val mismatch = confirmation.isNotEmpty() && passphrase != confirmation
    val visualTransformation = if (revealPassphrase) VisualTransformation.None else PasswordVisualTransformation()
    val focusManager = LocalFocusManager.current
    val keyboardOptions =
        KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
        )

    OutlinedTextField(
        value = passphrase,
        onValueChange = onPassphraseChange,
        label = { Text(stringResource(R.string.encrypted_backup_passphrase_label)) },
        singleLine = true,
        visualTransformation = visualTransformation,
        // Enter advances to the Confirm field with the keyboard up, instead of
        // dismissing it and stranding the user on field one.
        keyboardOptions = keyboardOptions.copy(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        trailingIcon = {
            TextButton(onClick = onRevealToggle) {
                Text(stringResource(if (revealPassphrase) R.string.hide else R.string.show))
            }
        },
        supportingText = {
            Text(
                stringResource(
                    R.string.encrypted_backup_minimum_hint,
                    ENCRYPTED_BACKUP_MIN_PASSPHRASE_LENGTH,
                ),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EncryptedBackupStrengthMeter(strength)
        Text(
            stringResource(strength.labelRes()),
            style = MaterialTheme.typography.bodySmall,
            color = strength.color(),
        )
    }
    OutlinedTextField(
        value = confirmation,
        onValueChange = onConfirmationChange,
        label = { Text(stringResource(R.string.encrypted_backup_confirm_label)) },
        singleLine = true,
        isError = mismatch,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions.copy(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        supportingText = {
            if (mismatch) {
                Text(stringResource(R.string.encrypted_backup_mismatch))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EncryptedBackupStrengthMeter(strength: EncryptedBackupPassphraseStrength) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(strength.progress())
                    .fillMaxHeight()
                    .background(strength.color()),
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
 * Non-destructive sign-out sheet (#348, #349). Explains what stays on device
 * and what changes, offers the "Delete key packages from relays" toggle
 * (default ON — passed through to the engine `sign_out` FFI), then performs
 * the actual sign-out via [onConfirm].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignOutSheet(
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
