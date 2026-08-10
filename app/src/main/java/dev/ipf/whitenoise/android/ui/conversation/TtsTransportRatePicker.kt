package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsRatePreferences
import dev.ipf.whitenoise.android.ui.settings.TtsCustomRateEditor
import dev.ipf.whitenoise.android.ui.settings.isTtsCustomRate
import dev.ipf.whitenoise.android.ui.settings.ttsRateLabel

@Suppress("FunctionNaming")
@Composable
internal fun TtsTransportRatePicker(
    rateOverride: Float?,
    activeRate: Float,
    onRateSelected: (Float?) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var customEditorOpen by remember { mutableStateOf(false) }
    val rateLabel = ttsRateLabel(activeRate)
    val controlDescription = stringResource(R.string.tts_bar_rate_control, rateLabel)

    Box {
        TextButton(
            onClick = { menuOpen = true },
            modifier =
                Modifier.semantics {
                    contentDescription = controlDescription
                    role = Role.Button
                    stateDescription = rateLabel
                },
        ) {
            Text(rateLabel)
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = {
                menuOpen = false
                customEditorOpen = false
            },
        ) {
            TransportRateMenuContent(
                rateOverride = rateOverride,
                activeRate = activeRate,
                customEditorOpen = customEditorOpen,
                onCustomEditorOpenChange = { customEditorOpen = it },
                onRateSelected = { rate ->
                    customEditorOpen = false
                    menuOpen = false
                    onRateSelected(rate)
                },
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun TransportRateMenuContent(
    rateOverride: Float?,
    activeRate: Float,
    customEditorOpen: Boolean,
    onCustomEditorOpenChange: (Boolean) -> Unit,
    onRateSelected: (Float?) -> Unit,
) {
    if (customEditorOpen) {
        TtsCustomRateEditor(
            initialRate = activeRate,
            onDismiss = { onCustomEditorOpenChange(false) },
            onRateSelected = onRateSelected,
        )
    } else {
        TransportRateMenuItem(
            label = stringResource(R.string.tts_settings_rate_system),
            selected = rateOverride == null,
            onClick = { onRateSelected(null) },
        )
        TtsRatePreferences.PRESET_RATES.forEach { rate ->
            TransportRateMenuItem(
                label = ttsRateLabel(rate),
                selected = rateOverride == rate,
                onClick = { onRateSelected(rate) },
            )
        }
        TransportRateMenuItem(
            label = stringResource(R.string.tts_rate_custom),
            selected = isTtsCustomRate(rateOverride),
            onClick = { onCustomEditorOpenChange(true) },
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun TransportRateMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon =
            if (selected) {
                { Icon(Icons.Default.Check, contentDescription = null) }
            } else {
                null
            },
        modifier =
            Modifier.semantics {
                this.selected = selected
                role = Role.RadioButton
            },
    )
}
