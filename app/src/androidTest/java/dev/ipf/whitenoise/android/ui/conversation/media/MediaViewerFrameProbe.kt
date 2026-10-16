package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsNode
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import org.junit.Assert.assertEquals
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Deterministic edge-marked H.264 clips committed as Base64 under `app/src/androidTest/assets` — the assets README
 * records the ffmpeg commands. Every clip shows a yellow top, cyan bottom, red left and magenta right band, each
 * [EDGE_BAND_SOURCE_PX] source pixels thick, around a neutral grey body. [displayWidth] and [displayHeight] are the
 * frame as it must appear once rotation metadata is applied.
 */
internal enum class EdgeClip(
    val asset: String,
    val sha256: String,
    val displayWidth: Int,
    val displayHeight: Int,
) {
    Portrait(
        asset = "edge-portrait-180x320.mp4.b64",
        sha256 = "64c2e9ff4e5c5a5f2dc2f41e35b3143455c73ba30c32bf88f99d78d814aa67d0",
        displayWidth = 180,
        displayHeight = 320,
    ),
    Landscape(
        asset = "edge-landscape-320x180.mp4.b64",
        sha256 = "abc054441700fa1d742aea6e5778f8288ba7b5fb8e3039fa93e2a7bc743fe1ed",
        displayWidth = 320,
        displayHeight = 180,
    ),

    /** Stored 320×180 with a 90° clockwise display matrix, so it must play back as a 180×320 portrait frame. */
    RotationTagged(
        asset = "edge-rotated-320x180-r90.mp4.b64",
        sha256 = "0969fe4763b9c434b057f1cf50322f6622f70aeb567b9e7d8bd6cf3136f058d3",
        displayWidth = 180,
        displayHeight = 320,
    ),
    ;

    /** Builds the local-only attachment identity the viewer pages and the video cache key use for this clip. */
    fun reference(): MediaAttachmentReferenceFfi =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "e${ordinal + 1}".repeat(32),
            plaintextSha256 = sha256,
            nonceHex = "cc".repeat(12),
            fileName = asset.removeSuffix(".b64"),
            mediaType = "video/mp4",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 1uL,
            dim = null,
            thumbhash = null,
        )

    /** Decodes the committed Base64 asset and rejects any byte that differs from the reviewed clip. */
    fun readVerifiedBytes(): ByteArray {
        val encoded =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open(asset)
                .bufferedReader()
                .use { it.readText() }
        val decoded = Base64.decode(encoded, Base64.DEFAULT)
        val digest = MessageDigest.getInstance("SHA-256").digest(decoded)
        assertEquals("$asset was replaced or corrupted", sha256, digest.joinToString("") { "%02x".format(it) })
        return decoded
    }
}

/** Colour of one edge band of an [EdgeClip]. */
internal enum class EdgeHue { Yellow, Cyan, Red, Magenta }

/**
 * Classifies a screen pixel by which RGB channels dominate, not by absolute level, so a band stays recognisable under
 * Media3's translucent controller scrim and bottom bar. Grey, white, black and dim pixels classify as none.
 */
internal fun edgeHueOf(pixel: Int): EdgeHue? {
    val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
    val high = channels.max()
    // Each channel must be clearly on or clearly off; anything between is not a band colour.
    val pattern =
        channels.map { channel ->
            when {
                channel * 10 >= high * 6 -> true
                channel * 10 <= high * 3 -> false
                else -> null
            }
        }
    return EDGE_HUE_PATTERNS[pattern].takeIf { high >= MIN_HUE_LEVEL }
}

/** Which of red, green and blue are on for each band colour. */
private val EDGE_HUE_PATTERNS: Map<List<Boolean?>, EdgeHue> =
    mapOf(
        listOf(true, true, false) to EdgeHue.Yellow,
        listOf(false, true, true) to EdgeHue.Cyan,
        listOf(true, false, false) to EdgeHue.Red,
        listOf(true, false, true) to EdgeHue.Magenta,
    )

/** The outermost screen line of one band, inclusive, and how many consecutive lines inward still show its colour. */
internal data class EdgeBand(
    val outer: Int,
    val thickness: Int,
)

/** Walks [line] from the outside inwards and returns the first run of [hue], or null when it never appears. */
internal fun firstRun(
    hue: EdgeHue,
    line: IntProgression,
    pixelAt: (Int) -> Int,
): EdgeBand? {
    val start = line.firstOrNull { edgeHueOf(pixelAt(it)) == hue } ?: return null
    val run = line.dropWhile { it != start }.takeWhile { edgeHueOf(pixelAt(it)) == hue }.count()
    return EdgeBand(start, run)
}

/** The four bands found on screen, null for any colour that never appeared on its scan line. */
internal data class FrameEdges(
    val top: EdgeBand?,
    val bottom: EdgeBand?,
    val left: EdgeBand?,
    val right: EdgeBand?,
) {
    /** True once every band has been found. */
    val complete: Boolean get() = top != null && bottom != null && left != null && right != null
}

/**
 * Scans real screen pixels from the [window] edges towards the centre of the [video] rectangle: the centre column for
 * the top and bottom bands, and a row a quarter of the way down the frame — clear of Media3's centre controls — for
 * the side bands. Scanning from the window edge, not the media body, means a band hidden under the chrome is missed.
 */
internal fun Bitmap.frameEdges(
    window: Rect,
    video: Rect,
): FrameEdges {
    val area = Rect(window).apply { intersect(0, 0, width, height) }
    val column = video.centerX().coerceIn(area.left, area.right - 1)
    val row = (video.top + video.height() * SIDE_SCAN_FRACTION).roundToInt().coerceIn(area.top, area.bottom - 1)
    return FrameEdges(
        top = firstRun(EdgeHue.Yellow, area.top until video.centerY()) { getPixel(column, it) },
        bottom = firstRun(EdgeHue.Cyan, (area.bottom - 1) downTo video.centerY()) { getPixel(column, it) },
        left = firstRun(EdgeHue.Red, area.left until video.centerX()) { getPixel(it, row) },
        right = firstRun(EdgeHue.Magenta, (area.right - 1) downTo video.centerX()) { getPixel(it, row) },
    )
}

/** Captures the composited display — SurfaceView video included — as a readable bitmap. */
internal fun takeDisplayScreenshot(): Bitmap {
    val shot =
        requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "UiAutomation returned no screenshot"
        }
    return shot.copy(Bitmap.Config.ARGB_8888, false).also { shot.recycle() }
}

/** Saves [this] screenshot under the runner's additional test output directory, when one was supplied. */
internal fun Bitmap.saveEvidence(name: String) {
    val directory = InstrumentationRegistry.getArguments().getString(ADDITIONAL_OUTPUT_ARGUMENT) ?: return
    runCatching {
        File(directory).mkdirs()
        File(directory, "$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }
}

/** Screen bounds of a laid-out Compose node, including any window offset. */
internal fun SemanticsNode.screenRect(): Rect {
    val origin = positionOnScreen
    return Rect(
        origin.x.roundToInt(),
        origin.y.roundToInt(),
        (origin.x + size.width).roundToInt(),
        (origin.y + size.height).roundToInt(),
    )
}

/** Screen bounds of a laid-out Android view. */
internal fun View.screenRect(): Rect {
    val origin = IntArray(2)
    getLocationOnScreen(origin)
    return Rect(origin[0], origin[1], origin[0] + width, origin[1] + height)
}

/** Every Media3 PlayerView in this view tree, depth first. */
internal fun View.descendantPlayerViews(): List<PlayerView> =
    when (this) {
        is PlayerView -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).descendantPlayerViews() }
        else -> emptyList()
    }

/** [this] window rectangle less [insets] on every side. */
internal fun Rect.insetBy(insets: Insets): Rect {
    val inset = Rect(this)
    inset.inset(insets.left, insets.top, insets.right, insets.bottom)
    return inset
}

/**
 * A simulated window — status bar, navigation bar and display cutout, in pixels — dispatched to the viewer window in
 * place of the device's own, so cutout coverage does not depend on the emulator having one.
 */
internal data class SimulatedInsets(
    val statusBars: Insets,
    val navigationBars: Insets,
    val cutout: Insets,
) {
    /** The area clear of every simulated bar and cutout inside [window]. */
    fun safeArea(window: Rect): Rect = window.insetBy(Insets.max(Insets.max(statusBars, navigationBars), cutout))

    /** Platform-shaped insets carrying the bars and a cutout with one bounding rectangle per inset side. */
    fun toCompat(window: Rect): WindowInsetsCompat {
        val (width, height) = window.width() to window.height()
        val bounds =
            buildList {
                if (cutout.left > 0) add(Rect(0, height / 3, cutout.left, height * 2 / 3))
                if (cutout.top > 0) add(Rect(width / 3, 0, width * 2 / 3, cutout.top))
                if (cutout.right > 0) add(Rect(width - cutout.right, height / 3, width, height * 2 / 3))
                if (cutout.bottom > 0) add(Rect(width / 3, height - cutout.bottom, width * 2 / 3, height))
            }
        return WindowInsetsCompat
            .Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), statusBars)
            .setInsets(WindowInsetsCompat.Type.navigationBars(), navigationBars)
            .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout)
            .setDisplayCutout(DisplayCutoutCompat(Rect(cutout.left, cutout.top, cutout.right, cutout.bottom), bounds))
            .build()
    }
}

/** Injects one touch gesture through the platform input pipeline, so the real window dispatch path decides it. */
internal fun injectTouchPath(
    from: PointF,
    to: PointF,
    steps: Int,
    stepMs: Long = INPUT_STEP_MS,
) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val downTime = SystemClock.uptimeMillis()

    /** Sends one touchscreen event of [action] at ([x], [y]) and waits for the platform to accept it. */
    fun send(
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        check(automation.injectInputEvent(event, true)) { "Touch injection was refused" }
        event.recycle()
    }
    send(MotionEvent.ACTION_DOWN, from.x, from.y)
    for (step in 1..steps) {
        SystemClock.sleep(stepMs)
        val fraction = step.toFloat() / steps
        send(MotionEvent.ACTION_MOVE, from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction)
    }
    SystemClock.sleep(stepMs)
    send(MotionEvent.ACTION_UP, to.x, to.y)
}

/** Source pixels in each clip's edge bands. */
internal const val EDGE_BAND_SOURCE_PX = 16

/** Darkest channel maximum still read as a band colour, low enough for a band under Media3's bottom bar. */
private const val MIN_HUE_LEVEL = 18

/** Side bands are scanned this far down the frame, above Media3's centre controls. */
private const val SIDE_SCAN_FRACTION = 0.25f

/** Delay between injected touch samples, about one display frame. */
private const val INPUT_STEP_MS = 16L

private const val PNG_QUALITY = 100

/** AGP's instrumentation argument naming the directory it pulls as additional test output. */
private const val ADDITIONAL_OUTPUT_ARGUMENT = "additionalTestOutputDir"
