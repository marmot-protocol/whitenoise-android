package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Picture
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import dev.ipf.whitenoise.android.ui.common.AppMotionCloseEasing
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import dev.ipf.whitenoise.android.ui.common.drawMeasuredPage
import dev.ipf.whitenoise.android.ui.common.measuredPageBounds

/** Geometry and inert pixels of a full-screen transition, owned by the route's existing morph host. */
internal class SettingsPageMorph {
    var pendingOrigin: Rect? = null
    private val origins = mutableMapOf<Any?, Rect>()
    private var target: Any? = null
    private var origin: Rect? = null
    internal var captureInputs: (() -> Any?)? = null
    private var settledInputs: Any? = null
    private var settled: Picture? = null
    private var leaving: Picture? = null
    private var source = Rect.Zero
    var opening = true
        private set

    val active: Boolean get() = origin != null
    val keptPictures: Int get() = if (leaving != null) 1 else 0
    internal val hasSettledPicture: Boolean get() = settled != null

    fun begin(
        next: Any?,
        forward: Boolean,
        animate: Boolean,
        host: Rect,
    ): Boolean {
        opening = forward
        val selected = if (forward) pendingOrigin else origins.remove(target)
        if (forward) {
            // Re-entry without a measured activation must retire this route's previous launch.
            if (selected != null) origins[next] = selected else origins.remove(next)
        }
        origin = selected?.takeIf { animate && !it.isEmpty && !host.isEmpty }
        // An input change without a completed draw has no faithful outgoing frame.
        leaving = settled.takeIf { origin != null && settledInputs == captureInputs?.invoke() }
        // A protected page or an interrupted entrance has no safe outgoing frame. Settle directly
        // instead of covering the restored parent with a blank full-screen surface.
        if (!forward && leaving == null) origin = null
        settled = null
        source = host
        target = next
        pendingOrigin = null
        return active
    }

    fun finish() {
        origin = null
        leaving = null
    }

    fun clearPictures() {
        settled = null
        leaving = null
        // A live protection change removes the outgoing frame and its overlay together.
        if (!opening) origin = null
    }

    fun bounds(
        host: Rect,
        time: Float,
    ): Rect? {
        val control = origin ?: return null
        val easing = if (opening) AppMotionOpenEasing else AppMotionCloseEasing
        val shape = easing.transform(time.coerceIn(0f, 1f))
        return measuredPageBounds(control, host, if (opening) shape else 1f - shape)
    }

    // Opening, closing, and settled drawing operate on the same retained pictures and capture lifetime.
    @Suppress("CyclomaticComplexMethod")
    fun ContentDrawScope.draw(
        host: Rect,
        time: Float,
        recording: Boolean,
        backplate: Color,
        captureContent: Boolean = true,
    ) {
        if (!recording) clearPictures()
        val bubble = bounds(host, time)?.translate(-host.topLeft)
        if (bubble == null || time >= 1f) {
            if (recording && captureContent) {
                val picture = settled ?: Picture().also { settled = it }
                val inputs = captureInputs?.invoke()
                drawAndRecord(picture)
                settledInputs = inputs
            } else {
                settled = null
                drawContent()
            }
            return
        }
        if (opening) {
            drawRect(backplate)
            leaving?.let { picture -> drawIntoCanvas { it.nativeCanvas.drawPicture(picture) } }
        } else {
            drawContent()
        }
        val easing = if (opening) AppMotionOpenEasing else AppMotionCloseEasing
        val shaped = easing.transform(time.coerceIn(0f, 1f))
        val presence = if (opening) shaped else 1f - shaped
        val destination = this
        drawMeasuredPage(bubble, presence, backplate, if (opening) size.width else source.width) {
            if (opening) {
                destination.drawContent()
            } else {
                leaving?.let { picture -> drawIntoCanvas { it.nativeCanvas.drawPicture(picture) } }
            }
        }
    }
}

/** Retain just a control rectangle, never pixels or a long-lived navigation registry. */
@Composable
internal fun rememberMeasuredControlClick(onClick: (Rect?) -> Unit): Pair<Modifier, () -> Unit> {
    val view = LocalView.current
    val bounds = remember { arrayOf(Rect.Zero) }
    return Modifier.onGloballyPositioned { bounds[0] = it.settingsMorphBounds(view) } to {
        onClick(bounds[0].takeUnless { it.isEmpty })
    }
}

/** Capture the actual activating row, including its scrolled position, before navigation changes it. */
@Composable
internal fun rememberSettingsRouteClick(onClick: () -> Unit): Pair<Modifier, () -> Unit> {
    val page = LocalSettingsCardMorph.current?.page ?: return Modifier to onClick
    val view = LocalView.current
    val bounds = remember(page) { arrayOf(Rect.Zero) }
    return Modifier.onGloballyPositioned { bounds[0] = it.settingsMorphBounds(view) } to {
        page.pendingOrigin = bounds[0].takeUnless { it.isEmpty }
        onClick()
    }
}

/** Controls inside popup windows and their destination host share screen coordinates. */
internal fun LayoutCoordinates.settingsMorphBounds(view: View): Rect {
    val screen = IntArray(2)
    val window = IntArray(2)
    view.getLocationOnScreen(screen)
    view.getLocationInWindow(window)
    return boundsInWindow().translate(Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat()))
}
