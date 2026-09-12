@file:Suppress("FunctionNaming", "MatchingDeclarationName") // Groups editor fields and Composable editor content.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField

/** Text editing lives within one native editor revision; no private key or durable checkpoint is stored here. */
internal class SetupEditorFields(
    editor: SetupEditor,
) {
    val reads = TextFieldState(editor.reads)
    val writes = TextFieldState(editor.writes)
    val displayName = TextFieldState(editor.displayName)
    val about = TextFieldState(editor.about)

    /** Reads text synchronously on Save so the last IME edit cannot lag the controller's proposal request. */
    fun current(editor: SetupEditor): SetupEditor =
        editor.copy(
            reads = reads.text.toString(),
            writes = writes.text.toString(),
            displayName = displayName.text.toString(),
            about = about.text.toString(),
        )
}

/** Keeps the process-owned native editor updated between view recreations, without submitting any action. */
@Composable
internal fun rememberSetupEditorFields(
    owner: String?,
    editor: SetupEditor,
    onChange: (SetupEditor) -> Unit,
): SetupEditorFields {
    val fields = remember(owner, editor.revision, editor.step, editor.action) { SetupEditorFields(editor) }
    val currentEditor = rememberUpdatedState(editor)
    val currentOnChange = rememberUpdatedState(onChange)
    LaunchedEffect(fields) {
        snapshotFlow { fields.current(currentEditor.value) }.collect { updated ->
            if (updated != currentEditor.value) currentOnChange.value(updated)
        }
    }
    return fields
}

/** Prototype shared fields retain complete discovery/read/write capability lists and original metadata. */
@Composable
internal fun SetupEditorContent(
    editor: SetupEditor,
    fields: SetupEditorFields,
    busy: Boolean,
) {
    if (editor.action == OnboardingActionFfi.EDIT_PROFILE) {
        WhiteNoiseTextField(
            state = fields.displayName,
            enabled = !busy,
            lineLimits = TextFieldLineLimits.SingleLine,
            label = { Text(stringResource(R.string.setup_display_name)) },
            modifier = Modifier.fillMaxWidth(),
        )
        WhiteNoiseTextField(
            state = fields.about,
            enabled = !busy,
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 6),
            label = { Text(stringResource(R.string.setup_about)) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        val discovery = editor.action == OnboardingActionFfi.EDIT_DISCOVERY_RELAYS
        Text(stringResource(if (discovery) R.string.setup_discovery_help else R.string.setup_relays_help))
        WhiteNoiseTextField(
            state = fields.reads,
            enabled = !busy,
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
            label = {
                Text(
                    stringResource(
                        when {
                            discovery -> R.string.setup_discovery_list
                            editor.step == OnboardingStepFfi.INBOX_RELAYS -> R.string.setup_inbox_list
                            else -> R.string.setup_read_list
                        },
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (editor.action == OnboardingActionFfi.EDIT_RELAYS && editor.step == OnboardingStepFfi.RELAYS) {
            WhiteNoiseTextField(
                state = fields.writes,
                enabled = !busy,
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
                label = { Text(stringResource(R.string.setup_write_list)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
