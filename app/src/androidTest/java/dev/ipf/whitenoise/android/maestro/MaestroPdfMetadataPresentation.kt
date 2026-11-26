package dev.ipf.whitenoise.android.maestro

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataField
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataRefusal
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerPdfMetadataChoices
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerPdfMetadataDialog
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerPdfMetadataOptions
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerPdfState

/**
 * The composer's metadata options and send question, driven by the real choices for two fixture PDFs.
 */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroPdfMetadataPresentation(fixture: MaestroPresentationFixture) {
    val signed = remember { Uri.parse("content://maestro.fixture/signed-lease.pdf") }
    val invoice = remember { Uri.parse("content://maestro.fixture/invoice.pdf") }
    val choices =
        remember(fixture) {
            ComposerPdfMetadataChoices().apply {
                val refused = PdfMetadataResult.Refused(PdfMetadataRefusal.Signed)
                val removable = PdfMetadataResult.Removable(setOf(PdfMetadataField.Author))
                record(signed, ComposerPdfState("Signed lease.pdf", refused))
                record(invoice, ComposerPdfState("Invoice.pdf", removable))
            }
        }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center)) {
            InvoiceMetadataOptions(invoice, choices)
            Button(
                onClick = {
                    choices.confirmThen(listOf(signed), { fixture.record("cancel") }) {
                        check(it == setOf(signed)) { "Send as is must cover the signed PDF only" }
                        fixture.finish("send-as-is")
                    }
                },
            ) { Text("Send fixture PDF") }
        }
        ComposerPdfMetadataDialog(choices)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun InvoiceMetadataOptions(
    invoice: Uri,
    choices: ComposerPdfMetadataChoices,
) {
    var open by remember { mutableStateOf(false) }
    Button(onClick = { open = true }) { Text("Open fixture PDF options") }
    if (open) {
        ComposerPdfMetadataOptions(
            label = "Invoice.pdf",
            keepsMetadata = choices.keepsOriginal(invoice),
            onChange = {
                choices.toggleKeepMetadata(invoice)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}
