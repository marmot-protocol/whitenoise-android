package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
@Composable
internal fun TtsAutoReadPickerSheet(
    globalDefaultEnabled: Boolean,
    selectedOverride: TtsAutoReadOverride?,
    onDismiss: () -> Unit,
    onSelect: (TtsAutoReadOverride?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        TtsAutoReadPickerContent(
            globalDefaultEnabled = globalDefaultEnabled,
            selectedOverride = selectedOverride,
            onSelect = { override ->
                onSelect(override)
                onDismiss()
            },
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}
