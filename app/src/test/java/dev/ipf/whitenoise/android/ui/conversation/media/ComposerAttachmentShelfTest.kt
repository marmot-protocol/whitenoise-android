package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real staged-slot shelf coverage: local decoding, mixed card geometry, and independent removal/preview actions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerAttachmentShelfTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Visual shelf uses prototype card geometry and independent actions. */
    @Test
    fun visualShelfUsesPrototypeCardGeometryAndIndependentActions() {
        val image = File(context.cacheDir, "shelf-landscape.png")
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(64, 128, 144))
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val slot = PendingMediaSlot("landscape-slot", Uri.fromFile(image))
        var preview = -1
        var removed: PendingMediaSlot? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp)) {
                    ComposerAttachmentShelf(
                        listOf(slot),
                        emptyList(),
                        emptyMap(),
                        onPreview = { preview = it },
                        onRemoveMedia = { removed = it },
                        onRemoveDocument = {},
                    )
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule
                .onNodeWithTag("conversation.composer.attachment.0")
                .fetchSemanticsNode()
                .size.width == 179
        }
        composeRule.onNodeWithTag("conversation.composer.attachments").assertHeightIsEqualTo(128.dp)
        composeRule
            .onNodeWithTag("conversation.composer.attachment.0")
            .assertHeightIsEqualTo(112.dp)
            .assertWidthIsEqualTo(179.dp)
        composeRule
            .onNodeWithTag("conversation.composer.attachments")
            .captureRoboImage("src/test/snapshots/composer_attachment_shelf_visual.png")
        composeRule.onNodeWithTag("conversation.composer.attachment.0").performClick()
        assertEquals(0, preview)
        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.media_attachment_remove) + ": " + image.name,
            ).performClick()
        assertEquals(slot, removed)
        assertEquals(0, preview)
    }

    /** Utility shelf keeps filename and removal at large text in rtl. */
    @Test
    fun utilityShelfKeepsFilenameAndRemovalAtLargeTextInRtl() {
        val name = "quarterly-report-final.pdf"
        var removed = -1
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, 2f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    Surface(Modifier.width(240.dp)) {
                        ComposerAttachmentShelf(
                            emptyList(),
                            listOf(Uri.parse("file:///private/$name")),
                            emptyMap(),
                            onPreview = {},
                            onRemoveMedia = {},
                            onRemoveDocument = { removed = it },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("conversation.composer.attachments").assertHeightIsEqualTo(88.dp)
        composeRule
            .onNodeWithTag("conversation.composer.attachment.0")
            .assertHeightIsEqualTo(72.dp)
            .assertWidthIsEqualTo(160.dp)
        composeRule
            .onNodeWithTag("conversation.composer.attachments")
            .captureRoboImage("src/test/snapshots/composer_attachment_shelf_utility_dark_large_rtl.png")
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.media_attachment_remove) + ": " + name)
            .performClick()
        assertEquals(0, removed)
    }

    /** Sizing and filename suffix remain bounded. */
    @Test
    fun sizingAndFilenameSuffixRemainBounded() {
        assertEquals(68, composerVisualAttachmentWidth(0.1f))
        assertEquals(200, composerVisualAttachmentWidth(9f))
        assertEquals(149, composerVisualAttachmentWidth(Float.NaN))
        assertEquals("annual-rep" to "ort.pdf", composerFilenameParts("annual-report.pdf"))
    }

    /** Pending native acceptance keeps captured document occurrences immutable while appends remain possible. */
    @Test
    fun pendingShelfCannotRemoveOrPreviewACapturedOccurrence() {
        var previews = 0
        var removals = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerAttachmentShelfCard(
                    label = "A.pdf",
                    visual = false,
                    video = false,
                    bitmap = null,
                    onPreview = { previews++ },
                    onRemove = { removals++ },
                    enabled = false,
                    modifier = Modifier.testTag("pending.card"),
                )
            }
        }
        composeRule.onNodeWithTag("pending.card").assertIsNotEnabled()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.media_attachment_remove) + ": A.pdf")
            .assertIsNotEnabled()
        assertEquals(0, previews)
        assertEquals(0, removals)
    }
}
