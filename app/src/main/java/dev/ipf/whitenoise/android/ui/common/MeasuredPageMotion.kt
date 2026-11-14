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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.settings.settingsMorphBounds

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
        val bounds = it.settingsMorphBounds(view)
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
    return onGloballyPositioned { body.header = it.settingsMorphBounds(view) }
}

@Composable
internal fun Modifier.nativePageFooter(): Modifier {
    val body = LocalNativePageBody.current ?: return this
    val view = LocalView.current
    return onGloballyPositioned { body.footer = it.settingsMorphBounds(view) }
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

/** Disjoint native passes: requested chrome, then one exclusive body at its native coordinates. */
internal fun ContentDrawScope.drawNativePage(
    body: Rect?,
    chrome: Boolean,
    bodyPresence: Float,
    bodyLimit: Rect? = null,
) {
    if (body == null || body.isEmpty) {
        // Unmeasured content is never replayed as an outgoing full-screen opaque sibling.
        if (chrome) drawContent()
        return
    }
    if (chrome) {
        clipRect(body.left, body.top, body.right, body.bottom, clipOp = ClipOp.Difference) {
            this@drawNativePage.drawContent()
        }
    }
    val visible = bodyLimit?.let { body.intersect(it) } ?: body
    if (!visible.isEmpty && bodyPresence > 0f) {
        clipRect(visible.left, visible.top, visible.right, visible.bottom) {
            val canvas = drawContext.canvas
            canvas.saveLayer(visible, Paint().apply { alpha = bodyPresence.coerceIn(0f, 1f) })
            this@drawNativePage.drawContent()
            canvas.restore()
        }
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
