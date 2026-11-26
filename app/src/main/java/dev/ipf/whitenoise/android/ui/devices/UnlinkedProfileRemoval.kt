@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AccountWipeCompletion
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.removeUnlinkedAccountForOutcome
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.settings.runAccountWipeMutation

internal const val UNLINKED_PROFILE_REMOVAL_TAG = "unlinked_profile.removal"
internal const val UNLINKED_PROFILE_REMOVAL_CONFIRM_TAG = "unlinked_profile.removal.confirm"

/**
 * The saved profile the user picked to remove after another device unlinked it here. A profile in that state
 * cannot sign in again, so picking it anywhere asks to remove it instead of trying. The pick outlives the sheet it
 * was made in, which closes first so the question is not drawn behind it.
 */
internal object UnlinkedProfileRemoval {
    var askedFor by mutableStateOf<String?>(null)
        private set

    /** Asks to remove [label]. */
    fun ask(label: String) {
        askedFor = label
    }

    /** Withdraws the question, answered or not. */
    fun close() {
        askedFor = null
    }
}

/** Whether picking [label] should ask to remove it: it is saved on this device and [unlinked] lists it. */
internal fun asksToRemoveUnlinkedProfile(
    label: String,
    unlinked: Collection<String>,
): Boolean = label in unlinked

/**
 * Asks to remove the unlinked profile picked while another profile is in view, and removes it on confirmation. It
 * is withdrawn when that profile is no longer saved as unlinked, and waits while the app is [blocked].
 */
@Composable
internal fun UnlinkedProfileRemovalHost(
    appState: WhiteNoiseAppState,
    blocked: Boolean,
) {
    val label = UnlinkedProfileRemoval.askedFor ?: return
    val stillUnlinked = appState.accounts.any { it.label == label && it.unlinked }
    LaunchedEffect(stillUnlinked) { if (!stillUnlinked) UnlinkedProfileRemoval.close() }
    if (!stillUnlinked || blocked || appState.wipeInProgress) return
    val epoch = appState.runtimeGeneration
    UnlinkedProfileRemovalDialog(
        onRemove = {
            UnlinkedProfileRemoval.close()
            launchUnlinkedProfileRemoval(appState, label, epoch)
        },
        onCancel = UnlinkedProfileRemoval::close,
    )
}

/** Runs the confirmed profile's removal on the app scope even when its prompting screen is dismissed. */
internal fun launchUnlinkedProfileRemoval(
    appState: WhiteNoiseAppState,
    label: String,
    expectedRuntime: Int,
    requireInView: Boolean = false,
) {
    if (appState.wipeInProgress) return
    appState.wipeInProgress = true
    appState.launchMutation {
        runAccountWipeMutation(
            onUnconfirmed = { appState.present(R.string.toast_wipe_outcome_unconfirmed, copyable = true) },
            onFinished = { appState.wipeInProgress = false },
        ) {
            val outcome = appState.removeUnlinkedAccountForOutcome(label, expectedRuntime, requireInView)
            reportUnlinkedProfileRemoval(appState, outcome)
        }
    }
}

/**
 * Says how the removal went, as removing the profile in view does: a short confirmation when every stage finished,
 * the list of unfinished stages when some did not, and a failure when the wipe never started.
 */
private fun reportUnlinkedProfileRemoval(
    appState: WhiteNoiseAppState,
    completion: AccountWipeCompletion?,
) {
    val report = completion?.report
    when {
        report == null -> appState.present(R.string.unlinked_account_remove_failed)
        report.clean -> appState.presentTransient(R.string.unlinked_account_removed)
        else -> appState.pendingWipeReport = report
    }
}

/** The question itself: what removing deletes, with the removal as its confirming action. */
@Composable
internal fun UnlinkedProfileRemovalDialog(
    onRemove: () -> Unit,
    onCancel: () -> Unit,
    signingIn: Boolean = false,
) {
    val detail = if (signingIn) R.string.unlinked_remove_sign_in_detail else R.string.unlinked_account_remove_detail
    val action = if (signingIn) R.string.unlinked_remove_sign_in else R.string.unlinked_account_remove_confirm
    ConfirmDialog(
        modifier = Modifier.testTag(UNLINKED_PROFILE_REMOVAL_TAG),
        title = stringResource(R.string.device_unlinked_title),
        message = stringResource(detail),
        confirmLabel = stringResource(action),
        onConfirm = onRemove,
        onDismiss = onCancel,
        // Removing the profile deletes its chats and data from this device.
        destructive = true,
        confirmTag = UNLINKED_PROFILE_REMOVAL_CONFIRM_TAG,
    )
}
