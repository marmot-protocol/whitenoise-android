package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.PlaybackDialog
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentAttachmentKind
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentLinkConfirmation
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentLoading
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentSource
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentViewerError
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentViewerFrame
import dev.ipf.whitenoise.android.ui.conversation.media.LocalHtmlPolicy
import dev.ipf.whitenoise.android.ui.conversation.media.LocalHtmlView
import dev.ipf.whitenoise.android.ui.conversation.media.PdfPageImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class DocumentAttachmentViewerScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pdfLoadingLight() =
        render(
            "pdf_loading_light",
            DocumentAttachmentKind.Pdf,
            dark = false,
        ) {
            DocumentLoading()
        }

    @Test fun pdfPagesDark() =
        render("pdf_pages_dark", DocumentAttachmentKind.Pdf) {
            Text("3 / 12", modifier = Modifier.padding(16.dp))
            val bitmap =
                remember {
                    Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.WHITE)
                        val paint =
                            Paint().apply {
                                color = android.graphics.Color.BLACK
                                textSize = 18f
                                isAntiAlias = true
                            }
                        Canvas(this).apply {
                            drawText("Community meeting notes", 16f, 42f, paint)
                            paint.textSize = 12f
                            drawText("Saturday · Local library", 16f, 72f, paint)
                        }
                    }
                }
            PdfPageImage(bitmap, "Page 3 of 12")
        }

    @Test fun pdfErrorLargeRtl() =
        render("pdf_error_large_rtl", DocumentAttachmentKind.Pdf, rtl = true, font = 1.5f) {
            DocumentViewerError(R.string.document_pdf_unavailable, {}, {})
        }

    @Test fun htmlRenderedNotice() =
        render("html_rendered_notice", DocumentAttachmentKind.Html) {
            Text(
                stringResource(R.string.document_html_omitted),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            // Robolectric covers native chrome, not Chromium layout or document-directed networking.
            val document =
                remember {
                    runBlocking {
                        LocalHtmlPolicy.sanitize("<h1>Meeting notes</h1><p>Bring a book.</p><img src=x>")
                    }
                }

            LocalHtmlView(document, Modifier.fillMaxSize(), {}, {})
        }

    @Test fun htmlSourceLight() =
        render("html_source_light", DocumentAttachmentKind.Html, source = true, dark = false) {
            DocumentSource("<h1>Meeting notes</h1>\n<p>Bring a book.</p>", "meeting.html")
        }

    @Test fun htmlSourceLargeRtl() =
        render("html_source_large_rtl", DocumentAttachmentKind.Html, source = true, rtl = true, font = 1.5f) {
            DocumentSource("<h1>Meeting notes</h1>\n<p>Bring a book.</p>", "meeting.html")
        }

    @Test fun htmlError() =
        render(
            "html_error",
            DocumentAttachmentKind.Html,
        ) {
            DocumentViewerError(
                R.string.document_html_unavailable,
                {
                },
                {
                },
            )
        }

    @Test fun linkConfirmation() {
        compose.setContent { WhiteNoiseTheme { DocumentLinkConfirmation("https://example.org/meeting-notes", {}, {}) } }
        compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/document_link_confirm.png")
    }

    @Test fun stagedPdfLoadingLight() =
        render(
            "staged_pdf_loading_light",
            DocumentAttachmentKind.Pdf,
            dark = false,
            staged = true,
        ) { DocumentLoading() }

    @Test fun stagedHtmlSourceAmoledLargeRtl() =
        render(
            "staged_html_source_large_rtl",
            DocumentAttachmentKind.Html,
            source = true,
            rtl = true,
            font = 2f,
            staged = true,
            amoled = true,
        ) { DocumentSource("<h1>Meeting notes</h1>", "meeting.html") }

    @Test fun stagedPdfErrorDark() =
        render(
            "staged_pdf_error_dark",
            DocumentAttachmentKind.Pdf,
            staged = true,
        ) { DocumentViewerError(R.string.document_pdf_unavailable, {}) }

    private fun render(
        name: String,
        kind: DocumentAttachmentKind,
        source: Boolean = false,
        dark: Boolean = true,
        rtl: Boolean = false,
        font: Float = 1f,
        staged: Boolean = false,
        amoled: Boolean = false,
        body: @Composable ColumnScope.() -> Unit,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, font),
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    val extension = if (kind == DocumentAttachmentKind.Pdf) "pdf" else "html"
                    PlaybackDialog(
                        {
                        },
                        properties =
                            DialogProperties(
                                usePlatformDefaultWidth = false,
                                decorFitsSystemWindows = false,
                            ),
                    ) {
                        Box(Modifier.fillMaxSize().testTag("document-viewer")) {
                            DocumentViewerFrame(
                                "community-meeting-notes-with-a-long-filename.$extension",
                                kind,
                                source,
                                false,
                                {},
                                if (staged) null else ({}),
                                if (staged) null else ({}),
                                if (staged) null else ({}),
                                {},
                                body,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (staged) {
            compose.onNodeWithContentDescription("Share").assertDoesNotExist()
            compose.onNodeWithContentDescription("More options").assertDoesNotExist()
            compose.onNodeWithText("Open in another app").assertDoesNotExist()
        }
        val extension = if (kind == DocumentAttachmentKind.Pdf) "pdf" else "html"
        compose.onNodeWithText("community-meeting-notes-with-a-long-filename.$extension").assertIsDisplayed()
        compose.onNodeWithTag("document-viewer").captureRoboImage("src/test/snapshots/document_$name.png")
    }
}
