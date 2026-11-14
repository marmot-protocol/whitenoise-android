package dev.ipf.whitenoise.android.ui.common

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.ipf.whitenoise.android.ui.design.PopupSurfaceMotion
import dev.ipf.whitenoise.android.ui.design.rememberPopupSurfaceMotion
import dev.ipf.whitenoise.android.ui.settings.LocalSettingsCardMorph
import dev.ipf.whitenoise.android.ui.settings.SettingsRouteEndpoint
import dev.ipf.whitenoise.android.ui.settings.drawRouteContour
import dev.ipf.whitenoise.android.ui.settings.settingsMorphBounds
import dev.ipf.whitenoise.android.ui.theme.ConnectedRowShape
import dev.ipf.whitenoise.android.ui.theme.amoledRowSeamColor
import androidx.compose.ui.graphics.lerp as lerpColor

/** Material keeps its native window, focus and layout; one lifetime owns growth and terminal actions. */
@Composable
// One native body's measurement and presentation feed the existing popup lifetime.
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
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
    source: PopupSourceActivation? = null,
    border: BorderStroke? = null,
) {
    val density = LocalDensity.current
    val measured = source != null && rememberSystemAnimationsEnabled()
    var nativeWidth by remember(source) { mutableStateOf<Dp?>(null) }
    var destination by remember(source) { mutableStateOf(Rect.Zero) }
    var dialogView by remember(source) { mutableStateOf<View?>(null) }
    var windowReady by remember(source) { mutableStateOf(false) }
    var sourceCurrent by remember(source) { mutableStateOf(true) }
    val presentationReady = !measured || !sourceCurrent || windowReady
    val surface =
        rememberPopupSurfaceMotion(
            true,
            onDismissRequest,
            ownerKey = source?.ownerKey ?: ownerKey,
            dismissEnabled = dismissEnabled,
            onDismissStart = onDismissStart,
            presentationReady = presentationReady,
            animate = !measured || sourceCurrent,
        )
    if (!surface.visible) return
    val morphing = measured && sourceCurrent && surface.presence.value < 1f
    DisposableEffect(source, measured, surface.presence) {
        // Read the same readiness and clock as the renderer, including preparation and fallback.
        source?.let { activation ->
            activation.contourOwnership = {
                measured && windowReady && sourceCurrent && activation.isCurrent() && surface.presence.value < 1f
            }
        }
        onDispose { source?.contourOwnership = null }
    }
    LaunchedEffect(source, surface.presence) {
        if (source != null) {
            snapshotFlow { surface.presence.value }.collect {
                if (sourceCurrent && !source.isCurrent()) {
                    sourceCurrent = false
                    surface.invalidateSource()
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = surface.dismiss,
        confirmButton = {
            val view = LocalView.current
            androidx.compose.runtime.SideEffect { dialogView = view }
            DialogWindowMotion(surface.presence)
            val window = (view.parent as? DialogWindowProvider)?.window
            val nativeWindowSize = remember(window) { window?.attributes?.let { it.width to it.height } }
            LaunchedEffect(window, nativeWidth, measured, sourceCurrent) {
                val sourceEligible = measured && sourceCurrent
                if (sourceEligible && window != null && nativeWidth != null) {
                    // Retain Material's ordinary measured width; only its native drawing window expands.
                    destination = Rect.Zero
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    withFrameNanos { }
                    withFrameNanos { }
                    windowReady = !destination.isEmpty
                    if (!windowReady) {
                        // A missing native measurement must not leave a hidden, focusable modal mounted.
                        sourceCurrent = false
                        surface.invalidateSource()
                    }
                } else if ((!measured || !sourceCurrent) && nativeWindowSize != null) {
                    window?.setLayout(nativeWindowSize.first, nativeWindowSize.second)
                }
            }
            confirmButton(surface)
        },
        modifier =
            (if (measured) Modifier.graphicsLayer { alpha = if (presentationReady) 1f else 0f } else surface.modifier())
                .then(surface.inputModifier())
                .then(
                    if (measured && sourceCurrent && nativeWidth != null) {
                        Modifier.requiredWidth(nativeWidth!!)
                    } else {
                        Modifier
                    },
                ).onGloballyPositioned { coordinates ->
                    if (measured) {
                        if (nativeWidth == null) nativeWidth = with(density) { coordinates.size.width.toDp() }
                        dialogView?.let { destination = coordinates.settingsMorphBounds(it) }
                        if (sourceCurrent && source != null && !source.isCurrent()) {
                            sourceCurrent = false
                            surface.invalidateSource()
                        }
                    }
                }.then(
                    if (morphing && source != null) {
                        Modifier.popupSourceReveal(source, destination, surface.presence, shape, containerColor, border)
                    } else {
                        Modifier
                    },
                ).then(if (!morphing && border != null) Modifier.border(border, shape) else Modifier)
                .then(modifier),
        dismissButton = dismissButton?.let { slot -> { slot(surface) } },
        icon = icon,
        title = title,
        text = text?.let { slot -> { slot(surface) } },
        shape = shape,
        containerColor = if (morphing) Color.Transparent else containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = if (morphing) Dp(0f) else tonalElevation,
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

/** A frozen real activation; geometry belongs to its source view and current native window. */
internal class PopupSourceActivation private constructor(
    val ownerKey: Any,
    val endpoint: SettingsRouteEndpoint,
    private val current: () -> Boolean,
) {
    fun isCurrent(): Boolean = current()

    // A live view of the dialog's presentation owner, released with that renderer.
    internal var contourOwnership by mutableStateOf<(() -> Boolean)?>(null)
    val ownsContour: Boolean get() = contourOwnership?.invoke() == true

    companion object {
        // Keep each invalid source rejection visible at the capture boundary.
        @Suppress("LongParameterList", "ReturnCount")
        fun capture(
            ownerKey: Any,
            coordinates: LayoutCoordinates?,
            view: View,
            shape: Shape,
            fill: Color,
            border: BorderStroke?,
            density: Density,
            direction: LayoutDirection,
        ): PopupSourceActivation? {
            if (coordinates == null || !coordinates.isAttached) return null
            val bounds = coordinates.settingsMorphBounds(view)
            if (bounds.isEmpty ||
                !listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() }
            ) {
                return null
            }
            val corners = shape.popupCorners(bounds.size, direction, density) ?: return null
            val windowToken = view.windowToken
            val windowBounds = android.graphics.Rect().also { view.getWindowVisibleDisplayFrame(it) }
            val outline = (border?.brush as? SolidColor)?.value ?: Color.Transparent
            val endpoint =
                SettingsRouteEndpoint(
                    ownerKey,
                    ownerKey,
                    bounds,
                    corners,
                    fill,
                    outline,
                    (shape as? ConnectedRowShape)?.first ?: true,
                    (shape as? ConnectedRowShape)?.last ?: true,
                    amoledRowSeamColor(outline),
                )
            return PopupSourceActivation(ownerKey, endpoint) {
                sourceWindowIsCurrent(view, coordinates, windowToken, bounds, windowBounds)
            }
        }
    }
}

private fun Shape.popupCorners(
    size: androidx.compose.ui.geometry.Size,
    direction: LayoutDirection,
    density: Density,
): RoundRect? =
    when (val outline = createOutline(size, direction, density)) {
        is Outline.Rounded -> outline.roundRect
        is Outline.Rectangle -> RoundRect(outline.rect)
        is Outline.Generic -> null
    }

/** Only the contour moves; Material's original content is revealed at its native, unscaled position. */
private fun Modifier.popupSourceReveal(
    source: PopupSourceActivation,
    destination: Rect,
    presence: State<Float>,
    shape: Shape,
    fill: Color,
    border: BorderStroke?,
): Modifier =
    drawWithContent {
        if (destination.isEmpty) return@drawWithContent
        val p = presence.value.coerceIn(0f, 1f)
        val bounds = lerp(source.endpoint.bounds, destination, p).translate(-destination.topLeft)
        val target = shape.popupCorners(size, layoutDirection, this) ?: return@drawWithContent

        fun corner(
            a: CornerRadius,
            b: CornerRadius,
        ) = CornerRadius(a.x + (b.x - a.x) * p, a.y + (b.y - a.y) * p)
        val contour =
            RoundRect(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                corner(source.endpoint.corners.topLeftCornerRadius, target.topLeftCornerRadius),
                corner(source.endpoint.corners.topRightCornerRadius, target.topRightCornerRadius),
                corner(source.endpoint.corners.bottomRightCornerRadius, target.bottomRightCornerRadius),
                corner(source.endpoint.corners.bottomLeftCornerRadius, target.bottomLeftCornerRadius),
            )
        val path = Path().apply { addRoundRect(contour) }
        drawPath(path, lerpColor(source.endpoint.fill, fill, p))
        val content = this
        clipPath(path) {
            // No scale or relocated final hit boxes. The input gate remains closed until settlement.
            content.drawContent()
        }
        val targetColor = (border?.brush as? SolidColor)?.value ?: Color.Transparent
        val stroke = lerpColor(source.endpoint.outline, targetColor, p)
        drawRouteContour(bounds, contour, path, source.endpoint.copy(outline = stroke), p, stroke)
    }

/** Compare against the original activation snapshot; never recapture the source. */
private fun sourceWindowIsCurrent(
    view: View,
    coordinates: LayoutCoordinates,
    windowToken: android.os.IBinder?,
    bounds: Rect,
    windowBounds: android.graphics.Rect,
): Boolean =
    view.isAttachedToWindow &&
        view.windowToken == windowToken &&
        coordinates.isAttached &&
        coordinates.settingsMorphBounds(view) == bounds &&
        android.graphics.Rect().also { view.getWindowVisibleDisplayFrame(it) } == windowBounds
