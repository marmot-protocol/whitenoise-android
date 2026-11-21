package dev.ipf.whitenoise.android.notifications

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.PathParser
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keeps the status-bar small icons as per-density PNGs rendered from their vector artwork
 * ([ARTWORK]). The PNGs avoid SystemUI's vector cache. Android can still resample the
 * bitmap when the display density falls between buckets and when drawing it in the status bar.
 *
 * After editing the artwork, regenerate the PNGs from the repository root:
 * ```
 * WN_RECORD_NOTIFICATION_SMALL_ICONS=true ./gradlew :app:testDevZapstoreDebugUnitTest --rerun \
 *   --tests '*NotificationSmallIconBitmapTest.smallIconPngsMatchTheirArtwork'
 * ```
 *
 * Record Roborazzi screenshots in a separate Gradle invocation so Android merges the new resources
 * before the screenshot tests start.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class NotificationSmallIconBitmapTest {
    /** The shipped PNGs are the artwork rendered at each density, so the logo itself never drifts. */
    @Test
    fun smallIconPngsMatchTheirArtwork() {
        val record = System.getenv("WN_RECORD_NOTIFICATION_SMALL_ICONS") == "true"
        for ((icon, artwork) in ARTWORK) {
            for ((bucket, scale) in DENSITIES) {
                val size = (ICON_DP * scale).roundToInt()
                val expected = renderArtwork(artwork, size)
                val file = File("src/main/res/drawable-$bucket/$icon.png")
                if (record) {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { expected.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    continue
                }
                val shipped = checkNotNull(BitmapFactory.decodeFile(file.path)) { "Missing $file" }
                assertEquals("$file width", size, shipped.width)
                assertEquals("$file height", size, shipped.height)
                for (y in 0 until size) {
                    for (x in 0 until size) {
                        val pixel = shipped.getPixel(x, y)
                        val drift = abs(Color.alpha(pixel) - Color.alpha(expected.getPixel(x, y)))
                        if (Color.alpha(pixel) != 0) {
                            val darkest = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                            assertTrue("$file pixel ($x, $y) must be white", darkest >= WHITE_MIN_CHANNEL)
                        }
                        assertTrue("$file pixel ($x, $y) alpha differs by $drift", drift <= ALPHA_TOLERANCE)
                    }
                }
            }
        }
    }

    /** Every density resolves the small icons to a bitmap drawn at 24dp, never to a vector. */
    @Test
    fun smallIconsResolveToBitmapsAtEveryDensity() {
        for ((bucket, scale) in DENSITIES) {
            RuntimeEnvironment.setQualifiers(bucket)
            for (icon in listOf(R.drawable.ic_stat_whitenoise, R.drawable.ic_stat_dictation)) {
                val drawable = checkNotNull(RuntimeEnvironment.getApplication().getDrawable(icon))
                assertTrue("$bucket resolves ${drawable.javaClass.simpleName}", drawable is BitmapDrawable)
                assertEquals("$bucket intrinsic width", (ICON_DP * scale).roundToInt(), drawable.intrinsicWidth)
            }
        }
    }

    /** Draws a small-icon vector's clip paths and filled paths, in document order, as white alpha. */
    private fun renderArtwork(
        artwork: String,
        size: Int,
    ): Bitmap {
        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        val vector = parser.parse(File(artwork)).documentElement
        check(vector.tagName == "vector")
        vector.checkAttributes("width", "height", "viewportWidth", "viewportHeight")
        check(vector.attr("width") == "${ICON_DP}dp" && vector.attr("height") == "${ICON_DP}dp")
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / vector.attr("viewportWidth").toFloat(), size / vector.attr("viewportHeight").toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        drawChildren(vector, canvas, paint)
        return bitmap
    }

    private fun drawChildren(
        parent: Element,
        canvas: Canvas,
        paint: Paint,
    ) {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            when (child.tagName) {
                "group" -> {
                    child.checkAttributes()
                    canvas.save()
                    drawChildren(child, canvas, paint)
                    canvas.restore()
                }
                "clip-path" -> {
                    child.checkAttributes("pathData")
                    canvas.clipPath(PathParser.createPathFromPathData(child.attr("pathData")))
                }
                "path" -> {
                    child.checkAttributes("pathData", "fillType", "fillColor")
                    check(child.attr("fillColor") == "@android:color/white")
                    val path = PathParser.createPathFromPathData(child.attr("pathData"))
                    path.fillType =
                        when (val fillType = child.attr("fillType")) {
                            "", "nonZero" -> Path.FillType.WINDING
                            "evenOdd" -> Path.FillType.EVEN_ODD
                            else -> error("Unsupported small-icon fill type $fillType")
                        }
                    canvas.drawPath(path, paint)
                }
                else -> error("Unsupported small-icon element <${child.tagName}>")
            }
        }
    }

    private fun Element.checkAttributes(vararg allowed: String) {
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            val androidAttribute = attribute.namespaceURI == ANDROID_NS && attribute.localName in allowed
            val namespaceDeclaration = attribute.namespaceURI == javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI
            check(androidAttribute || namespaceDeclaration) { "Unsupported attribute ${attribute.nodeName} on <$tagName>" }
        }
    }

    private fun Element.attr(name: String): String = getAttributeNS(ANDROID_NS, name)

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** Shipped PNG name to its vector artwork, relative to the app module. */
        val ARTWORK =
            mapOf(
                "ic_stat_whitenoise" to "src/main/res/drawable/ic_conversation_shortcut_mark.xml",
                "ic_stat_dictation" to "src/test/resources/notification-small-icons/ic_stat_dictation.xml",
            )
        const val ICON_DP = 24

        /** Resource density buckets and their scale; Android picks the nearest bucket at or above the device. */
        val DENSITIES =
            listOf(
                "mdpi" to 1f,
                "hdpi" to 1.5f,
                "xhdpi" to 2f,
                "xxhdpi" to 3f,
                "xxxhdpi" to 4f,
            )

        /** PNG round trips are exact; this only absorbs renderer rounding between Robolectric versions. */
        const val ALPHA_TOLERANCE = 2
        const val WHITE_MIN_CHANNEL = 250
    }
}
