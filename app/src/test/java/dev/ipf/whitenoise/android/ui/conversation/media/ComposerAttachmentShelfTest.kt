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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.TimelineReplyDisplay
import dev.ipf.whitenoise.android.state.timelineAppMessage
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
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
        val image = createLandscapePhoto("shelf-landscape.png")
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

    /** A staged photo's divider retains an 8 dp gap above the AMOLED reply outline. */
    @Test
    fun composerReplyAmoledKeepsGapAfterStagedPhoto() {
        val slot = PendingMediaSlot("reply-photo", Uri.fromFile(createLandscapePhoto("reply-staged-photo.png")))
        val parent = timelineAppMessage("parent")
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Surface(Modifier.width(360.dp)) {
                    ComposerBar(
                        replyingTo = parent,
                        replyingToDisplay = TimelineReplyDisplay(sender = "alice", body = "Parent message"),
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        initialDraft = TextFieldValue("Reply draft"),
                        hasPendingAttachments = true,
                        attachmentContent = {
                            ComposerAttachmentShelf(
                                mediaSlots = listOf(slot),
                                documentUris = emptyList(),
                                prepared = emptyMap(),
                                onPreview = {},
                                onRemoveMedia = {},
                                onRemoveDocument = {},
                            )
                        },
                    )
                }
            }
        }

        val shelf = composeRule.onNodeWithTag("conversation.composer.attachments")
        val dismiss = composeRule.onNodeWithContentDescription(context.getString(R.string.cancel_reply))
        // The dismiss target starts at the reply's top edge, one divider pixel plus 8 dp after the shelf.
        assertEquals(9.dp, dismiss.getUnclippedBoundsInRoot().top - shelf.getUnclippedBoundsInRoot().bottom)
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
                    kind = ComposerAttachmentCardKind.File,
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

    /** Only visual cards open the preview; file and contact cards carry no click action at all. */
    @Test
    fun visualCardsKeepOpeningThePreviewAndUnsupportedCardsStayInert() {
        var previews = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp)) {
                    androidx.compose.foundation.layout.Row {
                        ComposerAttachmentShelfCard(
                            label = "photo.jpg",
                            kind = ComposerAttachmentCardKind.Visual,
                            video = false,
                            bitmap = null,
                            onPreview = { previews++ },
                            onRemove = {},
                            modifier = Modifier.testTag("visual.card"),
                        )
                        ComposerAttachmentShelfCard(
                            label = "card.vcf",
                            kind = ComposerAttachmentCardKind.Contact,
                            video = false,
                            bitmap = null,
                            onPreview = null,
                            onRemove = {},
                            modifier = Modifier.testTag("contact.card"),
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("contact.card").assertHasNoClickAction()
        composeRule
            .onNodeWithTag("contact.card")
            .assertHeightIsEqualTo(72.dp)
            .assertWidthIsEqualTo(104.dp)
        composeRule.onNodeWithTag("visual.card").performClick()
        assertEquals(1, previews)
    }

    @Test fun supportedDocumentsOpenAndRemovalDoesNotOpen() {
        val uri = Uri.parse("file:///private/notes.pdf")
        var opened = 0
        var removed = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerAttachmentShelf(
                    mediaSlots = emptyList(),
                    documentUris = listOf(uri),
                    prepared = emptyMap(),
                    preparedDocuments =
                        mapOf(uri to ComposerDocumentReaderTest.prepared("application/pdf", "notes.pdf")),
                    onPreview = { error("must use document callback") },
                    onOpenDocument = {
                        assertEquals(uri, it)
                        opened++
                    },
                    onRemoveMedia = {},
                    onRemoveDocument = { removed++ },
                )
            }
        }
        composeRule.onNodeWithTag("conversation.composer.attachment.0").assertHasClickAction().performClick()
        assertEquals(1, opened)
        val semantics = composeRule.onNodeWithTag("conversation.composer.attachment.0").fetchSemanticsNode().config
        assertEquals(Role.Button, semantics[SemanticsProperties.Role])
        assertEquals(context.getString(R.string.composer_preview_attachment), semantics[SemanticsActions.OnClick].label)
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.media_attachment_remove) + ": notes.pdf")
            .performClick()
        assertEquals(1, removed)
        assertEquals(1, opened)
        composeRule
            .onNodeWithTag("conversation.composer.attachments")
            .captureRoboImage("src/test/snapshots/composer_attachment_shelf_document.png")
    }

    @Test fun unpreparedAndUnsupportedDocumentsAreInert() {
        val uri = Uri.parse("file:///private/archive.zip")
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerAttachmentShelf(
                    emptyList(),
                    listOf(uri),
                    emptyMap(),
                    onPreview = {},
                    onRemoveMedia = {},
                    onRemoveDocument = {},
                    preparedDocuments =
                        mapOf(uri to ComposerDocumentReaderTest.prepared("application/zip", "archive.zip")),
                    onOpenDocument = { error("unsupported") },
                )
            }
        }
        composeRule.onNodeWithTag("conversation.composer.attachment.0").assertHasNoClickAction()
    }

    @Test fun preparingDocumentsAreInertButRemovable() {
        val uri = Uri.parse("file:///private/notes.txt")
        var removed = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerAttachmentShelf(
                    emptyList(),
                    listOf(uri),
                    emptyMap(),
                    onPreview = {},
                    onRemoveMedia = {},
                    onRemoveDocument = { removed++ },
                    preparing = true,
                    preparedDocuments = mapOf(uri to ComposerDocumentReaderTest.prepared("text/plain", "notes.txt")),
                    onOpenDocument = { error("preparing") },
                )
            }
        }
        composeRule.onNodeWithTag("conversation.composer.attachment.0").assertHasNoClickAction()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.media_attachment_remove) + ": notes.txt")
            .performClick()
        assertEquals(1, removed)
    }

    /** Card geometry and the shelf height follow the prototype table for every kind. */
    @Test
    fun cardGeometryFollowsThePrototypeTable() {
        assertEquals(149 to 112, composerAttachmentCardSize(ComposerAttachmentCardKind.Visual))
        assertEquals(104 to 72, composerAttachmentCardSize(ComposerAttachmentCardKind.Contact))
        assertEquals(160 to 72, composerAttachmentCardSize(ComposerAttachmentCardKind.File))
        assertEquals(128, composerShelfHeight(hasVisualAttachment = true))
        assertEquals(88, composerShelfHeight(hasVisualAttachment = false))
    }

    private fun createLandscapePhoto(fileName: String): File {
        val image = File(context.cacheDir, fileName)
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(64, 128, 144))
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return image
    }
}
