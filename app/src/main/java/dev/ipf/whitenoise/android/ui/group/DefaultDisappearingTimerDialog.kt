package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDialogChoiceRow
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/**
 * The account's default disappearing timer in Settings. It is a short, familiar Settings list, so it is the radio
 * choice dialog from docs/ui-patterns.md, not the chat's sheet: Off and every preset are radio rows, a tap applies
 * the duration and closes, and Cancel or Back keeps the current default. Custom swaps the rows for the shared
 * custom-duration controls in the same dialog, where Set applies and Back returns to the list, so no second dialog
 * opens. New chats take the default, so nothing already sent changes and no consequence warning is needed.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun DefaultDisappearingTimerDialog(
    currentSecs: Long,
    explainer: String,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    var custom by rememberSaveable(currentSecs) { mutableStateOf(false) }
    val customState = rememberDisappearingCustomState(currentSecs.takeIf { it > 0L } ?: DEFAULT_CUSTOM_START_SECS)
    val isCustom = currentSecs !in disappearingPresetSecs
    WhiteNoiseAlertDialog(
        onDismissRequest = { if (custom) custom = false else onDismiss() },
        modifier = Modifier.testTag(DEFAULT_DISAPPEARING_DIALOG_TAG),
        title = {
            Text(stringResource(if (custom) R.string.disappearing_custom else R.string.disappearing_messages))
        },
        text = {
            Column(
                Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                if (custom) {
                    Column(Modifier.testTag(DISAPPEARING_CUSTOM_PAGE_TAG)) { DisappearingCustomControls(customState) }
                } else {
                    Text(
                        explainer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.fillMaxWidth().selectableGroup()) {
                        (listOf(0L) + disappearingGridSecs).forEach { secs ->
                            WhiteNoiseDialogChoiceRow(
                                title =
                                    if (secs == 0L) {
                                        stringResource(R.string.disappearing_off)
                                    } else {
                                        disappearingMessagesLabel(secs)
                                    },
                                selected = currentSecs == secs,
                                onClick = { close { if (secs != currentSecs) onPick(secs) } },
                                enabled = acceptsInput,
                                modifier = Modifier.testTag(disappearingPresetTag(secs)),
                            )
                        }
                        WhiteNoiseDialogChoiceRow(
                            title =
                                if (isCustom) {
                                    disappearingMessagesLabel(currentSecs)
                                } else {
                                    stringResource(R.string.disappearing_custom)
                                },
                            subtitle = if (isCustom) stringResource(R.string.disappearing_custom) else null,
                            selected = isCustom,
                            onClick = { custom = true },
                            enabled = acceptsInput,
                            modifier = Modifier.testTag(DISAPPEARING_CUSTOM_ACTION_TAG),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (custom) {
                TextButton(
                    onClick = { close { if (customState.secs != currentSecs) onPick(customState.secs) } },
                    enabled = acceptsInput,
                    modifier = Modifier.testTag(DISAPPEARING_CUSTOM_SET_TAG),
                ) { Text(stringResource(R.string.disappearing_set)) }
            } else {
                TextButton(onClick = dismiss, enabled = acceptsInput) { Text(stringResource(R.string.cancel)) }
            }
        },
        dismissButton =
            if (custom) {
                {
                    TextButton(onClick = { custom = false }, enabled = acceptsInput) {
                        Text(stringResource(R.string.back))
                    }
                }
            } else {
                null
            },
    )
}

/** Test tag on the default-timer dialog. */
internal const val DEFAULT_DISAPPEARING_DIALOG_TAG = "privacy.default_disappearing.dialog"

/** Custom starts at one minute when the default is Off, as the chat sheet's custom page does. */
private const val DEFAULT_CUSTOM_START_SECS = 60L
