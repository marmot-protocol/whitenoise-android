package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.ui.common.LocalNativePageBody
import dev.ipf.whitenoise.android.ui.common.LocalTopNoticeScope
import dev.ipf.whitenoise.android.ui.common.NativePageBody
import dev.ipf.whitenoise.android.ui.common.drawMeasuredPage
import dev.ipf.whitenoise.android.ui.common.drawNativePage
import dev.ipf.whitenoise.android.ui.common.measuredPageBounds
import dev.ipf.whitenoise.android.ui.common.motionBoundsOnScreen
import dev.ipf.whitenoise.android.ui.common.nativePageBodyPresence
import dev.ipf.whitenoise.android.ui.design.PopupSurfaceMotion
import dev.ipf.whitenoise.android.ui.design.rememberPopupSurfaceMotion
import dev.ipf.whitenoise.android.ui.settings.LocalSettingsCardMorph
import dev.ipf.whitenoise.android.ui.settings.SettingsCardMorph
import dev.ipf.whitenoise.android.ui.settings.SettingsContour

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
    sourceContour: SettingsContour? = null,
    penMotion: MeasuredPenDialogMotion? = null,
    noticeScope: ConversationNoticeDestination? = LocalTopNoticeScope.current,
    content: @Composable PopupSurfaceMotion.() -> Unit,
) {
    val contourMorph =
        remember(ownerKey, sourceContour) {
            sourceContour?.let { SettingsCardMorph(fullScreen = true) }
        }
    val nativeBody = remember(ownerKey, origin) { NativePageBody() }
    var host by remember(ownerKey) { mutableStateOf(Rect.Zero) }
    var geometryValid by remember(ownerKey, origin) { mutableStateOf(origin != null && !origin.isEmpty) }
    val surface =
        rememberPopupSurfaceMotion(
            true,
            onDismissRequest,
            ownerKey = ownerKey,
            onDismissStart = onDismissStart,
            dismissEnabled = dismissEnabled,
            presentationReady = penMotion == null || !host.isEmpty,
        )
    if (!surface.visible) return
    PlaybackDialog(
        onDismissRequest = surface.dismiss,
        properties = properties,
        noticeScope = noticeScope,
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
            measuredDialogPageModifier(
                origin.takeIf { geometryValid },
                host,
                surface,
                nativeBody,
                sourceContour.takeIf { geometryValid },
                contourMorph,
                penMotion,
            ) { next ->
                // Once established, a moved/resized window retires the old launch rectangle.
                if (measuredOriginRetired(origin, host, next)) geometryValid = false
                host = next
            }
        },
    ) {
        CompositionLocalProvider(
            LocalNativePageBody provides nativeBody,
            LocalMeasuredPenDialog provides penMotion,
            LocalSettingsCardMorph provides (contourMorph ?: LocalSettingsCardMorph.current),
        ) { content(surface) }
    }
}

/** Coordinates are read inside the real Dialog window, rather than from the launching view. */
@Composable
@Suppress("LongParameterList")
private fun measuredDialogPageModifier(
    origin: Rect?,
    host: Rect,
    surface: PopupSurfaceMotion,
    nativeBody: NativePageBody,
    sourceContour: SettingsContour?,
    contourMorph: SettingsCardMorph?,
    penMotion: MeasuredPenDialogMotion?,
    onHost: (Rect) -> Unit,
): Modifier {
    val view = LocalView.current
    val background = androidx.compose.material3.MaterialTheme.colorScheme.background
    val placement = Modifier.onGloballyPositioned { onHost(it.motionBoundsOnScreen(view)) }
    if (penMotion != null) {
        val penHost = host.takeIf { measuredOriginUsable(origin, host) }
        return placement.measuredPenOutline(penMotion, penHost, surface).then(
            dev.ipf.whitenoise.android.ui.common.fullScreenSurfaceMotion(surface) {
                penHost?.let(penMotion::bubble) == null
            },
        )
    }
    return placement
        .drawWithContent {
            if (sourceContour != null && contourMorph?.page != null && !host.isEmpty) {
                with(contourMorph.page) { drawExternal(sourceContour, host, surface.presence.value, background) }
            } else if (!measuredOriginUsable(origin, host)) {
                drawContent()
            } else {
                val presence = surface.presence.value
                val bounds = measuredPageBounds(checkNotNull(origin), host, presence).translate(-host.topLeft)
                drawMeasuredPage(bounds, presence, background)
                drawNativePage(
                    nativeBody.nativeBounds?.translate(-host.topLeft),
                    chrome = true,
                    bodyPresence = nativePageBodyPresence(presence, destination = true),
                )
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
