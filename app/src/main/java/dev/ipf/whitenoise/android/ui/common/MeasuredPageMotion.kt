@file:Suppress("MatchingDeclarationName") // Native paint helpers and their per-page geometry share one boundary.

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/** Geometry only, owned by one retained native page, never by an account or an avatar. */
internal class NativePageBody {
    var bounds by mutableStateOf<Rect?>(null)
    var header by mutableStateOf<Rect?>(null)
    var footer by mutableStateOf<Rect?>(null)
    val nativeBounds: Rect?
        get() =
            bounds?.let {
                Rect(
                    it.left,
                    maxOf(it.top, header?.bottom ?: it.top),
                    it.right,
                    minOf(it.bottom, footer?.top ?: it.bottom),
                )
            }
}

internal val LocalNativePageBody = staticCompositionLocalOf<NativePageBody?> { null }

/** The actual content boundary reports geometry without changing constraints, insets or placement. */
@Composable
internal fun Modifier.nativePageBody(padding: PaddingValues = PaddingValues()): Modifier {
    val body = LocalNativePageBody.current ?: return this
    val view = LocalView.current
    val density = LocalDensity.current
    return onGloballyPositioned {
        val bounds = it.motionBoundsOnScreen(view)
        body.bounds =
            with(density) {
                Rect(
                    bounds.left,
                    bounds.top + padding.calculateTopPadding().toPx(),
                    bounds.right,
                    bounds.bottom - padding.calculateBottomPadding().toPx(),
                )
            }
    }
}

/** Custom layouts report their real fixed header/footer, rather than an assumed toolbar height. */
@Composable
internal fun Modifier.nativePageHeader(): Modifier {
    val body = LocalNativePageBody.current ?: return this
    val view = LocalView.current
    return onGloballyPositioned { body.header = it.motionBoundsOnScreen(view) }
}

@Composable
internal fun Modifier.nativePageFooter(): Modifier {
    val body = LocalNativePageBody.current ?: return this
    val view = LocalView.current
    return onGloballyPositioned { body.footer = it.motionBoundsOnScreen(view) }
}

/** Shared container geometry; native text never enters a container transform. */
internal fun measuredPageBounds(
    origin: Rect,
    host: Rect,
    presence: Float,
): Rect = lerp(origin, host, presence.coerceIn(0f, 1f))

/** A container backplate below native ink. It deliberately cannot accept a content callback. */
internal fun DrawScope.drawMeasuredPage(
    bounds: Rect,
    presence: Float,
    backplate: Color,
) {
    val corner = CornerRadius(20.dp.toPx() * (1f - presence.coerceIn(0f, 1f)))
    val path = Path().apply { addRoundRect(RoundRect(bounds, corner)) }
    clipPath(path) { drawRect(backplate, topLeft = bounds.topLeft, size = bounds.size) }
}

/** A measured header painted on its own fade and vertical offset instead of with the fixed chrome. */
internal class NativePageHeaderMotion(
    val bounds: Rect,
    val presence: Float,
    val offsetY: Float,
)

/** Disjoint native passes: requested chrome, an optional moving header, then one exclusive body. */
internal fun ContentDrawScope.drawNativePage(
    body: Rect?,
    chrome: Boolean,
    bodyPresence: Float,
    bodyLimit: Rect? = null,
    header: NativePageHeaderMotion? = null,
) {
    // A settled current header stays in the fixed chrome pass, so rest frames are unchanged.
    val moving = header?.takeUnless { it.bounds.isEmpty || (chrome && it.presence >= 1f && it.offsetY == 0f) }
    val measuredBody = body?.takeUnless { it.isEmpty }
    if (chrome) drawContentOutside(listOfNotNull(measuredBody, moving?.bounds))
    if (moving != null && moving.presence > 0f) {
        // The band keeps its slot; content settles upward into it while fading in.
        val slot = moving.bounds
        clipRect(slot.left, slot.top, slot.right, slot.bottom) {
            val canvas = drawContext.canvas
            canvas.saveLayer(slot, Paint().apply { alpha = moving.presence.coerceIn(0f, 1f) })
            translate(top = moving.offsetY) { this@drawNativePage.drawContent() }
            canvas.restore()
        }
    }
    if (measuredBody == null) return
    val visible = bodyLimit?.let { measuredBody.intersect(it) } ?: measuredBody
    if (!visible.isEmpty && bodyPresence > 0f) {
        clipRect(visible.left, visible.top, visible.right, visible.bottom) {
            val canvas = drawContext.canvas
            canvas.saveLayer(visible, Paint().apply { alpha = bodyPresence.coerceIn(0f, 1f) })
            this@drawNativePage.drawContent()
            canvas.restore()
        }
    }
}

private fun ContentDrawScope.drawContentOutside(excluded: List<Rect>) {
    val first = excluded.firstOrNull() ?: return drawContent()
    clipRect(first.left, first.top, first.right, first.bottom, clipOp = ClipOp.Difference) {
        this@drawContentOutside.drawContentOutside(excluded.drop(1))
    }
}

private const val BODY_HANDOFF_MIDPOINT = 0.5f

/** Intrinsic position keeps paint continuous when the existing lifetime reverses mid-leg. */
internal fun nativePageBodyPresence(
    visibility: Float,
    destination: Boolean,
): Float =
    if (destination) {
        ((visibility - BODY_HANDOFF_MIDPOINT) / BODY_HANDOFF_MIDPOINT).coerceIn(0f, 1f)
    } else {
        ((BODY_HANDOFF_MIDPOINT - visibility) / BODY_HANDOFF_MIDPOINT).coerceIn(0f, 1f)
    }
