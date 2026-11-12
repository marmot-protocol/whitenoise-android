package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.ipf.whitenoise.android.ui.design.PopupSurfaceMotion
import dev.ipf.whitenoise.android.ui.design.rememberPopupSurfaceMotion
import dev.ipf.whitenoise.android.ui.settings.LocalSettingsCardMorph

/** Material keeps its native window, focus and layout; one lifetime owns growth and terminal actions. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun AnimatedAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable PopupSurfaceMotion.() -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable PopupSurfaceMotion.() -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable PopupSurfaceMotion.() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
    dismissEnabled: () -> Boolean = { true },
    onDismissStart: () -> Unit = {},
    ownerKey: Any? = LocalSettingsCardMorph.current,
) {
    val surface =
        rememberPopupSurfaceMotion(
            true,
            onDismissRequest,
            ownerKey = ownerKey,
            dismissEnabled = dismissEnabled,
            onDismissStart = onDismissStart,
        )
    if (!surface.visible) return
    AlertDialog(
        onDismissRequest = surface.dismiss,
        confirmButton = {
            DialogWindowMotion(surface.presence)
            confirmButton(surface)
        },
        modifier = surface.modifier().then(surface.inputModifier()).then(modifier),
        dismissButton = dismissButton?.let { slot -> { slot(surface) } },
        icon = icon,
        title = title,
        text = text?.let { slot -> { slot(surface) } },
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

/** Native window fading/dimming must follow the same clock as its surface, including the last frame. */
@Composable
@Suppress("FunctionNaming")
internal fun DialogWindowMotion(
    presence: State<Float>,
    dimAmount: Float? = null,
) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    val dim = remember(window, dimAmount) { dimAmount ?: window.attributes.dimAmount }
    // This window belongs to the Dialog and is destroyed with it. Restoring native exit
    // animation or dim on disposal would flash after our final transparent frame.
    SideEffect {
        window.setWindowAnimations(0)
        window.setDimAmount(dim * presence.value)
    }
    LaunchedEffect(window, presence) {
        snapshotFlow { presence.value }.collect { window.setDimAmount(dim * it) }
    }
}

/** Custom Material dialog bodies use the same window lifetime without changing submission semantics. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming")
internal fun AnimatedBasicAlertDialog(
    onDismissRequest: () -> Unit,
    ownerKey: Any? = LocalSettingsCardMorph.current,
    dismissEnabled: () -> Boolean = { true },
    content: @Composable PopupSurfaceMotion.() -> Unit,
) {
    val surface =
        rememberPopupSurfaceMotion(true, onDismissRequest, ownerKey = ownerKey, dismissEnabled = dismissEnabled)
    if (!surface.visible) return
    androidx.compose.material3.BasicAlertDialog(
        onDismissRequest = surface.dismiss,
        modifier = surface.modifier().then(surface.inputModifier()),
    ) {
        DialogWindowMotion(surface.presence)
        content(surface)
    }
}
