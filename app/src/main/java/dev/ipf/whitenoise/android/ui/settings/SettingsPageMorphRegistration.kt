package dev.ipf.whitenoise.android.ui.settings

import android.view.View
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphCard
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.common.motionBoundsOnScreen
import dev.ipf.whitenoise.android.ui.theme.ConnectedRowShape
import dev.ipf.whitenoise.android.ui.theme.amoledRowSeamColor
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import kotlin.math.ceil

/**
 * Compatibility facade for existing rectangle-only launchers. [originInset] shrinks the recorded
 * origin to the visible control inside a larger touch target, such as an avatar in its IconButton.
 */
@Composable
internal fun rememberSettingsRouteClick(onClick: () -> Unit) = rememberSettingsRouteClick(null, null, onClick)

@Composable
internal fun rememberSettingsRouteClick(
    onClick: () -> Unit,
    originInset: Dp,
) = rememberSettingsRouteClick(null, null, onClick, originInset)

/** Capture the actual activating row, including its scrolled position, before navigation changes it. */
@Composable
// Reject missing route owners before installing the attached-origin callback.
@Suppress("ReturnCount", "CyclomaticComplexMethod")
internal fun rememberSettingsRouteClick(
    context: SettingsRowContext?,
    routeKey: Any?,
    onClick: () -> Unit,
    originInset: Dp = 0.dp,
): Pair<Modifier, () -> Unit> {
    if (context != null && routeKey == null) return Modifier to onClick
    val morph = LocalSettingsCardMorph.current ?: return Modifier to onClick
    val page = morph.page ?: return Modifier to onClick
    val view = LocalView.current
    val bounds = remember(page) { arrayOf(Rect.Zero) }
    val token = remember(page, routeKey) { Any() }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val endpoint = remember(page, token) { arrayOfNulls<SettingsRouteEndpoint>(1) }
    val attached = remember(page, token) { booleanArrayOf(true) }
    DisposableEffect(page, token) {
        attached[0] = true
        onDispose { attached[0] = false }
    }
    return Modifier
        .onGloballyPositioned {
            val rect = it.fullSettingsMorphBounds(view)
            val visible = it.motionBoundsOnScreen(view)
            val inset = with(density) { originInset.toPx() }
            bounds[0] = rect.takeUnless { visible.isEmpty }?.deflate(inset)?.takeUnless { it.isEmpty } ?: Rect.Zero
            if (context != null && !rect.isEmpty) {
                val destination = checkNotNull(routeKey)
                val shape = context.shapes.shape
                val corners =
                    when (val outline = shape.createOutline(rect.size, direction, density)) {
                        is Outline.Rounded -> outline.roundRect
                        else -> RoundRect(Rect(Offset.Zero, rect.size))
                    }
                val measured =
                    SettingsRouteEndpoint(
                        destination,
                        destination,
                        rect,
                        corners,
                        context.containerColor,
                        context.borderColor,
                        (shape as? ConnectedRowShape)?.first ?: true,
                        (shape as? ConnectedRowShape)?.last ?: true,
                        if (context.borderColor.isSpecified) {
                            amoledRowSeamColor(
                                context.borderColor,
                            )
                        } else {
                            context.borderColor
                        },
                        visibleBounds = visible,
                    )
                endpoint[0] = measured
            }
        } to {
        if (attached[0]) {
            page.pendingOrigin = bounds[0].takeUnless { it.isEmpty }
            page.pendingEndpoint = endpoint[0]
            onClick()
        }
    }
}

/** A caller declares the actual native group; absent outlines never acquire synthetic chrome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
internal fun rememberSettingsNativeSurface(
    destination: Any?,
    fill: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    surface: Any = destination ?: Unit,
    shape: Shape = WhiteNoiseListItemDefaults.segmentedShapes(0, 1).shape,
    horizontalInset: Dp = 0.dp,
    registrationToken: Any? = null,
    strokeColor: Color = if (isAmoledSurfaceTheme()) MaterialTheme.colorScheme.outline else Color.Unspecified,
    onMeasured: ((SurfaceMorphCard?) -> Unit)? = null,
): Pair<Modifier, () -> Boolean> {
    val morph = LocalSettingsCardMorph.current
    val page = morph?.page
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val outline = strokeColor
    val token = registrationToken ?: remember(page, destination, surface) { Any() }
    val viewport = LocalSettingsNativeViewport.current
    val identity = LocalSettingsSearchItemKey.current to surface
    val width = with(density) { ceil(1.dp.toPx()) }
    DisposableEffect(page, token, onMeasured) {
        onDispose {
            page?.removeDestination(token)
            onMeasured?.invoke(null)
        }
    }
    if (page == null && onMeasured == null) return Modifier to { false }
    val modifier =
        Modifier.onGloballyPositioned { coordinates ->
            // boundsInWindow clips tall content; layout size and local origin retain the true perimeter.
            val clipped = coordinates.motionBoundsOnScreen(view)
            val local = coordinates.localToWindow(Offset.Zero)
            val windowBounds = coordinates.boundsInWindow()
            val offset = clipped.topLeft - windowBounds.topLeft
            val inset = with(density) { horizontalInset.toPx() }
            val measured =
                Rect(
                    local + offset,
                    androidx.compose.ui.geometry
                        .Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
                )
            val rect = Rect(measured.left + inset, measured.top, measured.right - inset, measured.bottom)
            val corners =
                when (val native = shape.createOutline(rect.size, direction, density)) {
                    is Outline.Rounded -> native.roundRect
                    else -> RoundRect(Rect(Offset.Zero, rect.size))
                }
            val endpoint =
                SettingsRouteEndpoint(
                    identity,
                    destination ?: token,
                    rect,
                    corners,
                    fill,
                    outline,
                    true,
                    true,
                    visibleBounds = rect.intersect(clipped),
                    strokeWidth = width,
                    viewportToken = viewport,
                    rtl = direction == androidx.compose.ui.unit.LayoutDirection.Rtl,
                )
            page?.reportDestination(token, endpoint)
            onMeasured?.invoke(endpoint.bubbleCard())
        }
    return modifier to { false }
}

/** Row reports belong to the existing group instance and carry only native stroke geometry. */
@Composable
internal fun rememberSettingsNativeRow(
    group: Any,
    key: String,
    context: SettingsRowContext,
): Modifier {
    val page = LocalSettingsCardMorph.current?.page ?: return Modifier
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val token = remember(page, group, key) { Any() }
    DisposableEffect(page, group, token) { onDispose { page.removeRow(group, token) } }
    return Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.fullSettingsMorphBounds(view)
        val shape = context.shapes.shape
        val corners =
            when (val outline = shape.createOutline(bounds.size, direction, density)) {
                is Outline.Rounded -> outline.roundRect
                else -> RoundRect(Rect(Offset.Zero, bounds.size))
            }
        page.reportRow(
            group,
            token,
            SettingsRouteEndpoint(
                token,
                token,
                bounds,
                corners,
                context.containerColor,
                context.borderColor,
                (shape as? ConnectedRowShape)?.first ?: true,
                (shape as? ConnectedRowShape)?.last ?: true,
                if (context.borderColor.isSpecified) amoledRowSeamColor(context.borderColor) else context.borderColor,
                strokeWidth = with(density) { ceil(1.dp.toPx()) },
            ),
        )
    }
}

/** Layout origin and size retain a partially clipped row's actual bottom and seam. */
private fun LayoutCoordinates.fullSettingsMorphBounds(view: View): Rect {
    val clipped = motionBoundsOnScreen(view)
    val offset = clipped.topLeft - boundsInWindow().topLeft
    return Rect(
        localToWindow(Offset.Zero) + offset,
        androidx.compose.ui.geometry
            .Size(size.width.toFloat(), size.height.toFloat()),
    )
}
