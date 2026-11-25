@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.SignOutCompletion
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.rethrowIfCancellation
import dev.ipf.whitenoise.android.state.retireDeviceUnlinkedNotice
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetInset
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.common.rememberSheetTerminalMotion
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Sign out and wipe: teardown spinner, explanatory sheet and the typed confirmation, unchanged from before. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun AccountWipeFlow(
    appState: WhiteNoiseAppState,
    ui: ProfileKeysUiState,
) {
    if (appState.signOutInProgress) {
        SignOutProgressDialog()
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
        val requested = appState.activeAccountRef
        val epoch = appState.runtimeGeneration
        // Type-to-confirm gate (#348): the destructive confirm button stays
        // disabled until the user types the confirmation keyword. The match is
        // case-insensitive and ignores surrounding whitespace. This is the last
        // stop before the engine destroys the local DB, MLS state, keychain
        // entry, and relay key packages, so a single tap must not be enough.
        ProfileKeyWipeConfirmationDialog(
            input = ui.wipeConfirmInput,
            onInputChange = { ui.wipeConfirmInput = it },
            onDismiss = {
                ui.wipeConfirm = false
                ui.wipeConfirmInput = ""
            },
            onConfirm = {
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
                if (requested != null) launchAccountWipe(appState, requested, epoch)
            },
        )
    }
}

/**
 * Signs the active account out and wipes it on the process-lifetime scope, showing the app-root staged progress and
 * reporting the outcome. The typed confirmation runs on the app scope so navigation cannot interrupt cleanup.
 */
private fun launchAccountWipe(
    appState: WhiteNoiseAppState,
    requested: String,
    epoch: Int,
) {
    appState.wipeInProgress = true
    appState.launchMutation {
        runAccountWipeMutation(
            onUnconfirmed = { error ->
                Log.w("AccountWipe", "Wipe outcome could not be confirmed: ${error.javaClass.simpleName}")
                appState.present(R.string.toast_wipe_outcome_unconfirmed, copyable = true)
            },
            onFinished = { appState.wipeInProgress = false },
        ) {
            if (appState.activeAccountRef != requested || appState.runtimeGeneration != epoch) {
                return@runAccountWipeMutation
            }
            val report =
                try {
                    appState.signOutAndWipeActiveAccount()?.report
                } finally {
                    // Cleanup after the removal is published can still throw.
                    appState.retireDeviceUnlinkedNotice(requested)
                }
            if (report == null) {
                // Total FFI failure: nothing was wiped and the
                // runtime state was restored — this one is
                // worth a bug report, so keep it copyable.
                appState.present(R.string.toast_couldnt_wipe_account, copyable = true)
            } else if (report.clean) {
                appState.presentTransient(R.string.toast_signed_out_and_wiped)
            } else {
                // The app-root sheet lists native and Android cleanup failures.
                appState.pendingWipeReport = report
            }
        }
    }
}

/**
 * Stateless typed confirmation; the account screen retains input and the destructive mutation scope.
 * Its title scrolls with the body (see [ProfileKeyWipeConfirmationBody]) so only the actions stay fixed.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ProfileKeyWipeConfirmationDialog(
    input: String,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val confirmKeyword = stringResource(R.string.sign_out_and_wipe_confirm_keyword)
    val wipeConfirmed = input.trim().equals(confirmKeyword, ignoreCase = true)
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.systemBarsPadding().imePadding(),
        text = { ProfileKeyWipeConfirmationBody(confirmKeyword, input, onInputChange) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = wipeConfirmed,
                modifier = Modifier.testTag("profile_keys.wipe_confirm"),
            ) {
                ProfileKeyActionLabel(
                    stringResource(R.string.wipe),
                    R.drawable.ic_delete,
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
                onClick = onDismiss,
            ) {
                ProfileKeyActionLabel(stringResource(R.string.cancel), R.drawable.ic_close)
            }
        },
    )
}

/**
 * The wipe confirmation's scrolling body: title, explanation, keyword instruction and the [confirmKeyword] field.
 *
 * The title scrolls here instead of sitting in the dialog's fixed title slot. At 200% text on a narrow phone the
 * icon-bearing actions stack onto two rows, and with the keyboard open a fixed title would leave this body shorter
 * than the keyword field, so the field could never be fully on screen. The title keeps the title slot's style,
 * colour and 16 dp gap, so the dialog looks the same whenever nothing needs to scroll.
 */
@Suppress("FunctionNaming")
@Composable
private fun ProfileKeyWipeConfirmationBody(
    confirmKeyword: String,
    input: String,
    onInputChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.sign_out_and_wipe_confirm_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(stringResource(R.string.sign_out_and_wipe_confirm_body))
        Text(
            stringResource(R.string.sign_out_and_wipe_confirm_instruction, confirmKeyword),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
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
}

/**
 * Blocks Back and outside interaction while the non-cancellable account teardown finishes, showing the themed
 * contained loading indicator over the window dim.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("FunctionNaming")
@Composable
internal fun SignOutProgressDialog() {
    Dialog(
        onDismissRequest = {},
        properties =
            DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Box(
            Modifier.fillMaxSize().testTag("settings.sign_out_progress").exposePerformanceTestTags(),
            contentAlignment = Alignment.Center,
        ) {
            // The contained indicator brings its own themed container, so it reads over the dim layer in any theme.
            ContainedLoadingIndicator()
        }
    }
}

/**
 * Runs the confirmed sign-out on the mutation scope and reports its outcome. The Settings hub and
 * the keys screen share this so both entry points tear the account down identically.
 */
internal fun signOutActiveAccount(
    appState: WhiteNoiseAppState,
    deleteKeyPackages: Boolean,
) {
    if (appState.signOutInProgress || appState.wipeInProgress) return
    appState.signOutInProgress = true
    // Mutation scope, not the screen scope: signOutActiveAccount()
    // flips activeAccountRef before its disk-media wipe finishes,
    // and the account-change nav reset pops this screen — a
    // screen-scoped job would be cancelled mid-teardown.
    appState.launchMutation {
        try {
            when (appState.signOutActiveAccount(deleteKeyPackages)) {
                SignOutCompletion.Complete -> appState.presentTransient(R.string.toast_signed_out)
                // Native local sign-out is confirmed, but its reply
                // was lost or reported relay cleanup failures. This
                // is informational and not copyable (#966); MDK
                // does not retain a retry queue for the deletions.
                SignOutCompletion.RelayCleanupIncomplete ->
                    appState.present(R.string.toast_signed_out_relay_cleanup_incomplete)
                // Local cleanup is incomplete or unconfirmed and the session is retained.
                SignOutCompletion.AccountCleanupIncomplete ->
                    appState.present(R.string.toast_couldnt_sign_out)
                // Native status confirmed the sign-out and the account is inactive, but
                // some of its local data could not be removed (#3626).
                SignOutCompletion.SignedOutLocalCleanupIncomplete ->
                    appState.present(R.string.toast_signed_out_local_cleanup_incomplete)
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
 * (default ON — passed through to the engine `sign_out` FFI) as the shared
 * settings switch row, then performs the actual sign-out via [onConfirm].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun SignOutSheet(
    onConfirm: (deleteKeyPackages: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val terminal = rememberSheetTerminalMotion(sheetState, sheetState)
    var deleteKeyPackages by remember { mutableStateOf(true) }
    WhiteNoiseModalBottomSheet(
        modifier = Modifier.exposePerformanceTestTags(),
        onDismissRequest = { terminal.close(onDismiss) },
        sheetState = sheetState,
    ) {
        WhiteNoiseSheetHeader(stringResource(R.string.sign_out_sheet_title))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSheetInset).padding(bottom = WhiteNoiseSheetInset),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
        ) {
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
            SettingsSwitch(
                context = settingsRowContext(0, 1, MaterialTheme.colorScheme.surfaceContainerLowest),
                title = stringResource(R.string.sign_out_delete_key_packages_label),
                subtitle = stringResource(R.string.sign_out_delete_key_packages_help),
                explanation = stringResource(R.string.explanation_account_keys_signout_invitation_keys),
                checked = deleteKeyPackages,
                onCheckedChange = { deleteKeyPackages = it },
                modifier = Modifier.testTag("profile_keys.signout_invitation_keys"),
                leading = null,
            )
            WhiteNoiseButton(
                onClick = { terminal.close(deleteKeyPackages, onConfirm) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(painterResource(R.drawable.ic_logout), contentDescription = null)
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val terminal = rememberSheetTerminalMotion(sheetState, sheetState)
    WhiteNoiseModalBottomSheet(
        onDismissRequest = { terminal.close(onDismiss) },
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState())) {
            WhiteNoiseSheetHeader(stringResource(R.string.sign_out_and_wipe_sheet_title))
            Column(
                Modifier.padding(horizontal = WhiteNoiseSheetInset).padding(bottom = WhiteNoiseSheetInset),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
            ) {
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
                    onClick = { terminal.close(onConfirm) },
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sign_out_and_wipe))
                }
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
 * Keeps admitted wipe errors distinct from confirmed native outcomes, without retrying destructive work.
 * Native cleanup can fail after destructive work; every non-cancellation failure has an unconfirmed outcome.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runAccountWipeMutation(
    onUnconfirmed: (Exception) -> Unit,
    onFinished: () -> Unit,
    mutation: suspend () -> Unit,
) {
    try {
        mutation()
    } catch (error: Exception) {
        rethrowIfCancellation(error)
        onUnconfirmed(error)
    } finally {
        onFinished()
    }
}
