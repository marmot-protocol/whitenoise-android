package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.painterResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphCard
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphColors
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphGeometry
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphSource
import dev.ipf.whitenoise.android.ui.common.drawSurfaceArrival
import dev.ipf.whitenoise.android.ui.common.surfaceMorphUnderlayCover
import dev.ipf.whitenoise.android.ui.design.PopupSurfaceMotion

/** Geometry shared by Home and its dialog. The popup still owns all timing and dismissal. */
internal class MeasuredPenDialogMotion(
    private val source: SurfaceMorphSource,
    private val isSourceCurrent: () -> Boolean = { true },
) {
    val origin get() = source.bounds
    private var landing by mutableStateOf<SurfaceMorphCard?>(null)
    private var cachedHost = Rect.Zero
    private var cachedLanding: SurfaceMorphCard? = null
    private var cachedBubble: SurfaceMorphGeometry? = null
    internal var drawsSource: () -> Boolean by mutableStateOf({ false })

    fun reportLanding(card: SurfaceMorphCard?) {
        landing = card
    }

    fun bubble(host: Rect): SurfaceMorphGeometry? {
        if (!isSourceCurrent()) return null
        val measured = landing
        if (cachedHost != host || cachedLanding !== measured) {
            cachedHost = host
            cachedLanding = measured
            cachedBubble = source.bubbleTo(measured, host)
        }
        return cachedBubble
    }
}

internal val LocalMeasuredPenDialog = staticCompositionLocalOf<MeasuredPenDialogMotion?> { null }

/** One measured outline replaces the generic measured page background for this dialog. */
@Composable
internal fun Modifier.measuredPenOutline(
    pen: MeasuredPenDialogMotion,
    host: Rect?,
    surface: PopupSurfaceMotion,
): Modifier {
    val latestHost by rememberUpdatedState(host)
    val presence = surface.presence
    DisposableEffect(pen, presence) {
        pen.drawsSource = { presence.value < 1f && latestHost?.let(pen::bubble) != null }
        onDispose { pen.drawsSource = { false } }
    }
    val scheme = MaterialTheme.colorScheme
    val colors =
        SurfaceMorphColors(
            scheme.surfaceContainerLow,
            scheme.outlineVariant,
            painterResource(R.drawable.ic_edit),
            scheme.onSurface,
        )
    return drawWithContent {
        val bubble = host?.let(pen::bubble)
        val progress = presence.value
        if (bubble == null || progress >= 1f) {
            drawContent()
        } else {
            drawRect(colors.backplate, alpha = surfaceMorphUnderlayCover(progress))
            drawSurfaceArrival(bubble, progress, colors, ink = 1f) { this@drawWithContent.drawContent() }
        }
    }
}
