package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.ui.common.drawMeasuredPage
import dev.ipf.whitenoise.android.ui.common.measuredPageBounds
import dev.ipf.whitenoise.android.ui.design.PopupSurfaceMotion
import dev.ipf.whitenoise.android.ui.design.rememberPopupSurfaceMotion
import dev.ipf.whitenoise.android.ui.settings.LocalSettingsCardMorph
import dev.ipf.whitenoise.android.ui.settings.settingsMorphBounds

/** Opt-in page presentation; thumbnail viewers keep their existing gesture owner. No page is captured. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun MeasuredPlaybackDialog(
    origin: Rect?,
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    onSourceDismiss: (() -> Unit)? = onDismissRequest,
    ownerKey: Any? = LocalSettingsCardMorph.current,
    onDismissStart: () -> Unit = {},
    dismissEnabled: () -> Boolean = { true },
    content: @Composable PopupSurfaceMotion.() -> Unit,
) {
    var host by remember(ownerKey) { mutableStateOf(Rect.Zero) }
    var geometryValid by remember(ownerKey, origin) { mutableStateOf(origin != null && !origin.isEmpty) }
    val surface =
        rememberPopupSurfaceMotion(
            true,
            onDismissRequest,
            ownerKey = ownerKey,
            onDismissStart = onDismissStart,
            dismissEnabled = dismissEnabled,
        )
    if (!surface.visible) return
    PlaybackDialog(
        onDismissRequest = surface.dismiss,
        properties = properties,
        onSourceDismiss = onSourceDismiss,
        onSourceExit =
            onSourceDismiss?.let { dismiss ->
                { next ->
                    if (dismissEnabled()) {
                        onDismissStart()
                        surface.close {
                            dismiss()
                            next()
                        }
                    }
                }
            },
        rootModifier = {
            measuredDialogPageModifier(origin.takeIf { geometryValid }, host, surface) { next ->
                // Once established, a moved/resized window retires the old launch rectangle.
                if (measuredOriginRetired(origin, host, next)) geometryValid = false
                host = next
            }
        },
    ) {
        content(surface)
    }
}

/** Coordinates are read inside the real Dialog window, rather than from the launching view. */
@Composable
private fun measuredDialogPageModifier(
    origin: Rect?,
    host: Rect,
    surface: PopupSurfaceMotion,
    onHost: (Rect) -> Unit,
): Modifier {
    val view = LocalView.current
    return Modifier
        .onGloballyPositioned { onHost(it.settingsMorphBounds(view)) }
        .drawWithContent {
            if (!measuredOriginUsable(origin, host)) {
                drawContent()
            } else {
                val presence = surface.presence.value
                val bounds = measuredPageBounds(checkNotNull(origin), host, presence).translate(-host.topLeft)
                val destination = this
                drawMeasuredPage(bounds, presence) { destination.drawContent() }
            }
        }.then(
            dev.ipf.whitenoise.android.ui.common.fullScreenSurfaceMotion(surface) {
                !measuredOriginUsable(origin, host)
            },
        )
}

/** The same geometry predicate governs both the measured draw and the fullscreen fallback. */
private fun measuredOriginUsable(
    origin: Rect?,
    host: Rect,
): Boolean {
    if (origin == null || origin.isEmpty || host.isEmpty) return false
    return origin.overlaps(host)
}

private fun measuredOriginRetired(
    origin: Rect?,
    previous: Rect,
    next: Rect,
): Boolean {
    val hostMoved = !previous.isEmpty && previous != next
    val originLost = origin == null || !origin.overlaps(next)
    return hostMoved || originLost
}
