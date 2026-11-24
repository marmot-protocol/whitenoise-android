@file:Suppress("FunctionNaming") // Compose UI functions follow the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingPinnedActionPage

/** Account setup uses the shared onboarding page: scrolling reasons above, the decision's actions pinned below. */
@Composable
internal fun SetupPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    OnboardingPinnedActionPage(title = title, onBack = onBack, actions = actions, content = content)
}

/** The one part of setup the page shows; the content and the pinned actions both follow it. */
internal enum class SetupSurface { Editor, Ready, Progress, Decision }

/** Picks the editor, the finished state, a running check, or the current native decision. */
internal fun AccountSetupState.setupSurface(): SetupSurface {
    val step = currentStep
    val snapshot = snapshot
    val awaitingNativeResult =
        !error &&
            !disconnected &&
            !staleDecision &&
            (
                snapshot == null ||
                    optionalMetadataPending ||
                    (
                        step?.status == OnboardingStatusFfi.CHECKING &&
                            step.actions.isEmpty() &&
                            snapshot.proposal == null
                    )
            )
    return when {
        editor != null -> SetupSurface.Editor
        snapshot?.ready == true -> SetupSurface.Ready
        busy || awaitingNativeResult -> SetupSurface.Progress
        else -> SetupSurface.Decision
    }
}

/** Callbacks the pinned setup actions dispatch; each one is the same owner call the inline actions used. */
internal class SetupPinnedCallbacks(
    val onAction: (SetupRequest) -> Unit,
    val onEdit: (OnboardingStepFfi, OnboardingActionFfi, ULong) -> Unit,
    val onSaveEditor: (SetupEditor) -> Unit,
    val onDismissEditor: () -> Unit,
    val onReconnect: () -> Unit,
    val onOpenChats: () -> Unit,
    val onRemoveRelay: (OnboardingStepFfi, String, ULong, String?) -> Unit,
)

/**
 * The pinned actions for the current surface: Resume after a failure, the editor's save and cancel, Open chats once
 * setup is ready, or the native decision's direct removal and offered actions.
 */
@Composable
internal fun SetupPinnedActions(
    state: AccountSetupState,
    fields: SetupEditorFields?,
    callbacks: SetupPinnedCallbacks,
) {
    val surface = state.setupSurface()
    SetupResumeAction(state, primary = surface != SetupSurface.Editor, callbacks.onReconnect)
    when (surface) {
        SetupSurface.Editor -> {
            val editor = checkNotNull(state.editor)
            val current = checkNotNull(fields).current(editor)
            SetupEditorActions(
                editor,
                state.busy,
                editor.action != OnboardingActionFfi.EDIT_RELAYS || current.canReviewRelayEdit,
                onSave = { callbacks.onSaveEditor(fields.current(editor)) },
                onDismiss = callbacks.onDismissEditor,
            )
        }
        SetupSurface.Ready ->
            WhiteNoiseButton(
                onClick = callbacks.onOpenChats,
                enabled = state.snapshot?.cancellationPending == false && !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("setup-open-chats"),
            ) { Text(stringResource(R.string.setup_open_chats)) }
        SetupSurface.Progress -> Unit
        SetupSurface.Decision -> {
            val step = state.currentStep ?: return
            SetupDirectRelayRemovalAction(state, callbacks.onRemoveRelay)
            val blockedByFailure = state.error || state.staleDecision || state.optionalMetadataPending
            val reconnectOffered = OnboardingActionFfi.RECONNECT_SIGNER in step.actions
            if (!blockedByFailure && (!state.disconnected || reconnectOffered)) {
                SetupActionButtons(state, callbacks.onAction, callbacks.onEdit)
            }
        }
    }
}

/** Resume is the way forward after a failure or a lost connection until setup is ready. */
@Composable
private fun SetupResumeAction(
    state: AccountSetupState,
    primary: Boolean,
    onReconnect: () -> Unit,
) {
    if (!state.showsSetupFailure || state.snapshot?.ready == true) return
    val modifier = Modifier.fillMaxWidth().testTag("setup-resume-checks")
    val label: @Composable () -> Unit = { Text(stringResource(R.string.setup_resume_checks)) }
    if (primary) {
        WhiteNoiseButton(onClick = onReconnect, enabled = !state.busy, modifier = modifier) { label() }
    } else {
        WhiteNoiseOutlinedButton(onClick = onReconnect, enabled = !state.busy, modifier = modifier) { label() }
    }
}

/** Setup stopped, lost its connection before finishing, or the decision on screen went stale. */
internal val AccountSetupState.showsSetupFailure: Boolean
    get() = error || (disconnected && snapshot?.ready != true) || staleDecision

/** Removes the single exact endpoint named above; native review follows before anything is published. */
@Composable
private fun SetupDirectRelayRemovalAction(
    state: AccountSetupState,
    onRemoveRelay: (OnboardingStepFfi, String, ULong, String?) -> Unit,
) {
    val snapshot = state.snapshot
    val step = state.currentStep
    val endpoint = state.directRemovalEndpoint()
    if (snapshot == null || step == null || endpoint == null) return
    val revision = snapshot.revision
    val recoveryEpoch = snapshot.recoveryEpoch
    WhiteNoiseButton(
        onClick = { onRemoveRelay(step.step, endpoint, revision, recoveryEpoch) },
        enabled = !state.busy && !state.error && !state.disconnected && !state.staleDecision,
        modifier = Modifier.fillMaxWidth().testTag("setup-remove-relay"),
    ) {
        Icon(painterResource(R.drawable.ic_delete), null)
        SetupButtonIconGap()
        Text(stringResource(R.string.setup_remove_relay))
    }
}

/** The editor saves into native review or cancels back to the decision. */
@Composable
private fun SetupEditorActions(
    editor: SetupEditor,
    busy: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    WhiteNoiseButton(
        onClick = onSave,
        enabled = !busy && canSave,
        modifier = Modifier.fillMaxWidth().testTag("setup-editor-save"),
    ) {
        val label =
            if (editor.action == OnboardingActionFfi.EDIT_DISCOVERY_RELAYS) {
                R.string.setup_check_discovery
            } else {
                R.string.setup_review_changes
            }
        Text(stringResource(label))
    }
    WhiteNoiseOutlinedButton(
        onClick = onDismiss,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().testTag("setup-editor-cancel"),
    ) { Text(stringResource(R.string.cancel)) }
}
