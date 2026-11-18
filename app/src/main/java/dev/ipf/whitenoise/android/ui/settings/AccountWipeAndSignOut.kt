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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.SignOutCompletion
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.rethrowIfCancellation
import dev.ipf.whitenoise.android.state.wipeReport
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Delete
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

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
                appState.wipeInProgress = true
                appState.launchMutation {
                    runAccountWipeMutation(
                        onUnconfirmed = { error ->
                            Log.w("AccountWipe", "Wipe outcome could not be confirmed: ${error.javaClass.simpleName}")
                            appState.present(R.string.toast_wipe_outcome_unconfirmed, copyable = true)
                        },
                        onFinished = { appState.wipeInProgress = false },
                    ) {
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
                    }
                }
            },
        )
    }
}

/** Stateless typed confirmation; the account screen retains input and the destructive mutation scope. */
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
        title = { Text(stringResource(R.string.sign_out_and_wipe_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
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
        },
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

/** Blocks Back and outside interaction while the non-cancellable account teardown finishes. */
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
            // The full-window dim layer can cover either theme; white stays visible against that scrim.
            LoadingIndicator(color = Color.White)
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
        modifier = Modifier.exposePerformanceTestTags(),
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
                    .testTag("profile_keys.signout_invitation_keys")
                    .explainOnLongPress(
                        Explanation(
                            stringResource(R.string.sign_out_delete_key_packages_label),
                            stringResource(R.string.explanation_account_keys_signout_invitation_keys),
                        ),
                    ).toggleable(
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
    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()).padding(24.dp),
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
