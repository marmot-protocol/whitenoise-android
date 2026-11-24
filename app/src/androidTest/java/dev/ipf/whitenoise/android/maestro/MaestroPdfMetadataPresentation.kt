package dev.ipf.whitenoise.android.maestro

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataRefusal
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerPdfMetadataChoices
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerPdfMetadataDialog
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerPdfState

/**
 * The composer's question before sending a PDF whose metadata stays (#3377), from the real choices and dialog. The
 * signed PDF is a fixture state; no file is read and nothing is sent.
 */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroPdfMetadataPresentation(fixture: MaestroPresentationFixture) {
    val signed = remember { Uri.parse("content://maestro.fixture/signed-lease.pdf") }
    val choices =
        remember(fixture) {
            ComposerPdfMetadataChoices().apply {
                val refused = PdfMetadataResult.Refused(PdfMetadataRefusal.Signed)
                record(signed, ComposerPdfState("Signed lease.pdf", refused))
            }
        }
    Box(Modifier.fillMaxSize()) {
        Button(
            onClick = {
                choices.confirmThen(listOf(signed), { fixture.record("cancel") }) {
                    check(choices.takeConsent() == setOf(signed)) { "Send as is must cover the signed PDF only" }
                    fixture.finish("send-as-is")
                }
            },
            modifier = Modifier.align(Alignment.Center),
        ) { Text("Send fixture PDF") }
        ComposerPdfMetadataDialog(choices)
    }
}
