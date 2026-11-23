@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataRefusal
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog

internal const val COMPOSER_PDF_METADATA_DIALOG_TAG = "conversation.composer.pdf_metadata_dialog"
internal const val COMPOSER_PDF_METADATA_SEND_TAG = "conversation.composer.pdf_metadata_send_as_is"

/** Asks before PDFs whose metadata cannot be removed are sent as they are; dismissing keeps them on the shelf. */
@Composable
internal fun ComposerPdfMetadataDialog(choices: ComposerPdfMetadataChoices) {
    val decision = choices.decision ?: return
    ComposerPdfMetadataDialogContent(decision, onConfirm = choices::confirm, onCancel = choices::cancel)
}

@Composable
internal fun ComposerPdfMetadataDialogContent(
    decision: ComposerPdfDecision,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(COMPOSER_PDF_METADATA_DIALOG_TAG),
        title = { Text(stringResource(R.string.composer_pdf_metadata_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(composerPdfDecisionReason(decision))
                Text(stringResource(R.string.composer_pdf_metadata_dialog_consequence))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(COMPOSER_PDF_METADATA_SEND_TAG)) {
                Text(stringResource(R.string.composer_pdf_metadata_send_as_is))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun composerPdfDecisionReason(decision: ComposerPdfDecision): String {
    val name = decision.names.singleOrNull()
    return when {
        name == null ->
            pluralStringResource(
                R.plurals.composer_pdf_metadata_dialog_several,
                decision.names.size,
                decision.names.size,
                decision.names.joinToString(", "),
            )
        PdfMetadataRefusal.Signed in decision.reasons ->
            stringResource(R.string.composer_pdf_metadata_dialog_signed, name)
        PdfMetadataRefusal.Encrypted in decision.reasons ->
            stringResource(R.string.composer_pdf_metadata_dialog_encrypted, name)
        else -> stringResource(R.string.composer_pdf_metadata_dialog_unsupported, name)
    }
}
