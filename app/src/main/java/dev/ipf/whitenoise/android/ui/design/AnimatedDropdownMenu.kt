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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

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
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    border: BorderStroke? = null,
    keyboardPreserving: Boolean = false,
    backRegistration: KeyboardSafePopupBackRegistration? = null,
    content: @Composable ColumnScope.(PopupSurfaceMotion) -> Unit,
) {
    val density = LocalDensity.current
    val windowHeight =
        with(density) {
            LocalWindowInfo.current.containerSize.height
                .toDp()
        }
    val maxHeight = (windowHeight - 96.dp).coerceAtLeast(0.dp)
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
            border = border,
        ) {
            Column(
                Modifier
                    .heightIn(max = maxHeight)
                    .width(IntrinsicSize.Max)
                    .verticalScroll(scrollState)
                    .padding(vertical = 8.dp),
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

/** Material menu candidate order, including its edge and centered fallbacks. */
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
        val xs =
            if (layoutDirection == LayoutDirection.Ltr) {
                listOf(left, right, if (anchorBounds.left >= 0) edge else 0)
            } else {
                listOf(right, left, if (anchorBounds.right <= windowSize.width) 0 else edge)
            }
        val x = xs.firstOrNull { it >= 0 && it + popupContentSize.width <= windowSize.width } ?: right
        val below = maxOf(anchorBounds.bottom + dy, margin)
        val above = anchorBounds.top - popupContentSize.height + dy
        val center = anchorBounds.top - popupContentSize.height / 2 + dy
        val bottom = windowSize.height - popupContentSize.height - margin
        val y =
            listOf(below, above, center, bottom).firstOrNull {
                it >= margin && it + popupContentSize.height <= windowSize.height - margin
            } ?: above
        return IntOffset(x, y)
    }
}
