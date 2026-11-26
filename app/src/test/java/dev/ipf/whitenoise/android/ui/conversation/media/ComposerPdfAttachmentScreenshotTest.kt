package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataRefusal
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Staged PDFs on the composer shelf (#3377): first page, page count and what happens to their metadata, plus the
 * question asked before PDFs that keep their metadata are sent, across light, AMOLED and large RTL text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerPdfAttachmentScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Cleaned, signed, chosen-as-is, protected and still-preparing PDFs on a light shelf. */
    @Test fun cardsLight() = captureCards("light", dark = false, large = false)

    /** The same states keep their outline, page and warning colours on black. */
    @Test fun cardsAmoled() = captureCards("amoled", dark = true, large = false)

    /** At 200% RTL text the filename keeps its extension and the state line stays on one line. */
    @Test fun cardsLargeRtl() = captureCards("large_rtl", dark = true, large = true)

    /** The question for one signed PDF names it and says what stays visible. */
    @Test fun signedQuestionLight() = captureDialog("signed_light", dark = false, large = false, names = listOf(SIGNED))

    /** Several PDFs are listed by name; both answers stay reachable at large RTL text. */
    @Test
    fun severalQuestionLargeRtl() {
        captureDialog("several_large_rtl", dark = true, large = true, names = listOf(SIGNED, "Scan 2024-03.pdf"))
    }

    /** Confirming sends once and records the choice; cancelling keeps the PDF waiting. */
    @Test
    fun answersReachOnlyTheirOwnOutcome() {
        val choices = ComposerPdfMetadataChoices()
        val uri = Uri.parse("content://fixture/signed")
        choices.record(uri, ComposerPdfState(SIGNED, PdfMetadataResult.Refused(PdfMetadataRefusal.Signed)))
        var sent = 0
        var approved = emptySet<Uri>()
        var cancelled = 0
        composeRule.setContent { WhiteNoiseTheme { ComposerPdfMetadataDialog(choices) } }

        choices.confirmThen(listOf(uri), { cancelled++ }) {
            approved = it
            sent++
        }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        assertEquals(0 to 1, sent to cancelled)

        choices.confirmThen(listOf(uri), { cancelled++ }) {
            approved = it
            sent++
        }
        composeRule.onNodeWithTag(COMPOSER_PDF_METADATA_SEND_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(1 to 1, sent to cancelled)
        assertEquals(setOf(uri), approved)
    }

    /** Per-file metadata choice after opening the card options. */
    @Test
    fun keepMetadataOptions() {
        composeRule.setContent {
            Themed(dark = true, large = false) {
                ComposerPdfMetadataOptions("Invoice.pdf", keepsMetadata = false, onChange = {}, onDismiss = {})
            }
        }
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/composer_pdf_metadata_options.png")
    }

    private fun captureCards(
        name: String,
        dark: Boolean,
        large: Boolean,
    ) {
        val page = samplePage()
        composeRule.setContent {
            Themed(dark, large) {
                Surface(Modifier.width(if (large) 320.dp else 360.dp).testTag("pdf.cards")) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Card("Quarterly report final.pdf", 12, page, ComposerPdfPrivacy.Removed)
                        Card(SIGNED, 3, page, ComposerPdfPrivacy.Signed)
                        Card(SIGNED, 3, page, ComposerPdfPrivacy.SendingAsIs)
                        Card("Draft notes.pdf", 2, page, ComposerPdfPrivacy.RemovedOnSend, onMetadataOptions = {})
                        Card("Bank statement.pdf", null, null, ComposerPdfPrivacy.Encrypted)
                        Card("Form export.pdf", 1, null, ComposerPdfPrivacy.Unsupported)
                        Card("Scan 2024-03.pdf", null, null, null)
                    }
                }
            }
        }
        composeRule.onNodeWithTag("pdf.cards").captureRoboImage("src/test/snapshots/composer_pdf_cards_$name.png")
    }

    private fun captureDialog(
        name: String,
        dark: Boolean,
        large: Boolean,
        names: List<String>,
    ) {
        val decision = ComposerPdfDecision(names, setOf(PdfMetadataRefusal.Signed), emptyList(), {}, {})
        composeRule.setContent {
            Themed(dark, large) { ComposerPdfMetadataDialogContent(decision, onConfirm = {}, onCancel = {}) }
        }
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/composer_pdf_metadata_dialog_$name.png")
    }

    @Composable
    @Suppress("FunctionNaming")
    private fun Themed(
        dark: Boolean,
        large: Boolean,
        content: @Composable () -> Unit,
    ) {
        val direction = if (large) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            WhiteNoiseTheme(darkTheme = dark, amoled = dark, fontScale = if (large) 2f else 1f, content = content)
        }
    }

    @Composable
    @Suppress("FunctionNaming")
    private fun Card(
        label: String,
        pages: Int?,
        page: ImageBitmap?,
        privacy: ComposerPdfPrivacy?,
        onMetadataOptions: (() -> Unit)? = null,
    ) {
        ComposerPdfAttachmentCard(label, pages, page, privacy, onPreview = {}, onRemove = {}, onMetadataOptions = onMetadataOptions)
    }

    /** A plain document page: a heading bar and lines of text, as a first-page render would show. */
    private fun samplePage(): ImageBitmap {
        val bitmap = Bitmap.createBitmap(168, 224, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint().apply { color = 0xFF1F3A5F.toInt() }
        canvas.drawRect(16f, 18f, 120f, 34f, paint)
        paint.color = 0xFF9AA3AD.toInt()
        for (line in 0 until 12) {
            val top = 50f + line * 13f
            canvas.drawRect(16f, top, if (line % 4 == 3) 96f else 152f, top + 5f, paint)
        }
        return bitmap.asImageBitmap()
    }

    private companion object {
        const val SIGNED = "Signed lease agreement.pdf"
    }
}
