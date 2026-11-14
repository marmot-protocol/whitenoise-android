package dev.ipf.whitenoise.android.ui.group

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride
import dev.ipf.whitenoise.android.ui.common.PopupSourceActivation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog

/** The per-chat read-aloud choice as the prototype's choice dialog: pick a row, or Cancel. */
@Suppress("FunctionNaming")
@Composable
internal fun TtsAutoReadPickerDialog(
    globalDefaultEnabled: Boolean,
    selectedOverride: TtsAutoReadOverride?,
    onDismiss: () -> Unit,
    onSelect: (TtsAutoReadOverride?) -> Unit,
    source: PopupSourceActivation? = null,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        source = source,
        title = { Text(stringResource(R.string.tts_auto_read_title)) },
        text = {
            TtsAutoReadPickerContent(
                globalDefaultEnabled = globalDefaultEnabled,
                selectedOverride = selectedOverride,
                enabled = acceptsInput,
                onSelect = { override ->
                    close {
                        onSelect(override)
                        onDismiss()
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = dismiss, enabled = acceptsInput) { Text(stringResource(R.string.cancel)) }
        },
    )
}
