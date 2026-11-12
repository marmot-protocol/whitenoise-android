package dev.ipf.whitenoise.android.ui.design

import android.os.Build
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.ipf.whitenoise.android.ui.common.boundedIntrinsicWidth

/** One popup lifetime and growth owner; no Material DropdownMenu transition is nested inside it. */
@Composable
// Compose naming; popup focus, Back registration, and exit lifetime remain one surface owner.
@Suppress("FunctionNaming", "LongMethod")
internal fun AnimatedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    border: BorderStroke? = null,
    keyboardPreserving: Boolean = false,
    backRegistration: KeyboardSafePopupBackRegistration? = null,
    content: @Composable ColumnScope.(PopupSurfaceMotion) -> Unit,
) {
    val density = LocalDensity.current
    val position =
        remember(density, offset) {
            val dx = with(density) { offset.x.roundToPx() }
            val dy = with(density) { offset.y.roundToPx() }
            val margin = with(density) { 48.dp.roundToPx() }
            DropdownAnchorPositionProvider(dx, dy, margin)
        }

    val body: @Composable (PopupSurfaceMotion) -> Unit = { surface ->
        Surface(
            modifier = modifier.then(surface.inputModifier()),
            shape = shape,
            color = containerColor,
            tonalElevation = MenuDefaults.TonalElevation,
            shadowElevation = MenuDefaults.ShadowElevation,
            border = border,
        ) {
            Column(
                Modifier
                    .padding(vertical = 8.dp)
                    .boundedIntrinsicWidth(IntrinsicSize.Max)
                    .verticalScroll(scrollState),
            ) { content(surface) }
        }
    }
    val surface = rememberPopupSurfaceMotion(expanded, onDismissRequest)
    if (surface.visible) {
        if (keyboardPreserving) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                KeyboardSafePopupPlatformBackHandler(
                    priority = OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                    backRegistration = backRegistration,
                    onBack = surface.dismiss,
                )
            } else {
                BackHandler { surface.dismiss() }
            }
            Popup(
                onDismissRequest = surface.dismiss,
                properties =
                    PopupProperties(
                        focusable = false,
                        dismissOnClickOutside = false,
                        excludeFromSystemGesture = false,
                    ),
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().pointerInput(surface) {
                        detectTapGestures { surface.dismiss() }
                    },
                )
            }
        }
        Popup(
            position,
            onDismissRequest = surface.dismiss,
            properties =
                PopupProperties(
                    focusable = !keyboardPreserving,
                    dismissOnClickOutside = !keyboardPreserving,
                    excludeFromSystemGesture = !keyboardPreserving,
                ),
        ) {
            androidx.compose.foundation.layout
                .Box(surface.modifier()) { body(surface) }
        }
    }
}

/** Material3 1.5.0-beta01 DropdownMenu placement (Below anchor, zero horizontal margin). */
internal class DropdownAnchorPositionProvider(
    private val dx: Int,
    private val dy: Int,
    private val margin: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val offsetX = if (layoutDirection == LayoutDirection.Ltr) dx else -dx
        val left = anchorBounds.left + offsetX
        val right = anchorBounds.right - popupContentSize.width + offsetX
        val edge = windowSize.width - popupContentSize.width
        val fallback = (if (anchorBounds.center.x < windowSize.width / 2) 0 else edge) + offsetX
        val xs =
            if (layoutDirection == LayoutDirection.Ltr) {
                listOf(left, right, fallback)
            } else {
                listOf(right, left, fallback)
            }
        val x =
            xs.firstOrNull { it >= 0 && it + popupContentSize.width <= windowSize.width }
                ?: if (popupContentSize.width >= windowSize.width) {
                    Alignment.CenterHorizontally.align(popupContentSize.width, windowSize.width, layoutDirection)
                } else {
                    fallback.coerceIn(0, edge)
                }
        val below = anchorBounds.bottom + dy
        val above = anchorBounds.top - popupContentSize.height + dy
        val center = anchorBounds.top - Alignment.CenterVertically.align(0, popupContentSize.height) + dy
        val verticalEdge =
            (if (anchorBounds.center.y < windowSize.height / 2) 0 else windowSize.height - popupContentSize.height) + dy
        val bottom = windowSize.height - popupContentSize.height - margin
        val y =
            listOf(below, above, center, verticalEdge).firstOrNull {
                it >= margin && it + popupContentSize.height <= windowSize.height - margin
            } ?: if (popupContentSize.height >= windowSize.height - 2 * margin) {
                Alignment.CenterVertically.align(popupContentSize.height, windowSize.height)
            } else {
                verticalEdge.coerceIn(margin, bottom)
            }
        return IntOffset(x, y)
    }
}
