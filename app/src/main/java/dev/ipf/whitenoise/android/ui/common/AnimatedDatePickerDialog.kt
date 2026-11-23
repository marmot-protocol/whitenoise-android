package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.ui.design.PopupSurfaceMotion
import dev.ipf.whitenoise.android.ui.design.rememberPopupSurfaceMotion
import dev.ipf.whitenoise.android.ui.settings.LocalSettingsCardMorph

/** Calendar layout stays Material-owned; growth and terminal actions share the app dialog lifetime. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming")
internal fun AnimatedDatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable PopupSurfaceMotion.() -> Unit,
    dismissButton: @Composable PopupSurfaceMotion.() -> Unit,
    ownerKey: Any? = LocalSettingsCardMorph.current,
    content: @Composable () -> Unit,
) {
    val surface = rememberPopupSurfaceMotion(true, onDismissRequest, ownerKey = ownerKey)
    if (!surface.visible) return
    DatePickerDialog(
        onDismissRequest = surface.dismiss,
        modifier = surface.modifier().then(surface.inputModifier()),
        confirmButton = {
            DialogWindowMotion(surface.presence)
            confirmButton(surface)
        },
        dismissButton = { dismissButton(surface) },
    ) { content() }
}
