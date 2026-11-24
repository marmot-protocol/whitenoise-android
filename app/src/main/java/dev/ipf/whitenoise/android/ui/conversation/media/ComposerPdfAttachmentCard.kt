@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataRefusal
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerAccessoryRemoveButton
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.PictureAsPdf
import dev.ipf.whitenoise.android.ui.icons.filled.Shield
import dev.ipf.whitenoise.android.ui.icons.filled.WarningAmber
import dev.ipf.whitenoise.android.ui.theme.Radii
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.Typography as BaseTypography

private const val PDF_CARD_WIDTH = 288
private const val PDF_CARD_HEIGHT = 72
private const val PAGE_WIDTH = 42
private const val PAGE_HEIGHT = 56

// From this text scale, system and in-app together, the card keeps one line: the metadata state's icon and the name.
private const val COMPACT_FONT_SCALE = 1.5f
private val PDF_CARD_SHAPE =
    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
private val PAGE_SHAPE = RoundedCornerShape(Radii.sm)

/** What a staged PDF's card says about its metadata. */
internal enum class ComposerPdfPrivacy {
    Removed,
    RemovedOnSend,
    Signed,
    Encrypted,
    Unsupported,
    SendingAsIs,
}

/** The card state for a PDF whose prepared bytes read as [metadata], or null while they are still being read. */
internal fun composerPdfPrivacy(
    metadata: PdfMetadataResult?,
    keepsOriginal: Boolean,
): ComposerPdfPrivacy? =
    when (metadata) {
        null, PdfMetadataResult.NotPdf -> null
        PdfMetadataResult.Clean, is PdfMetadataResult.Scrubbed -> ComposerPdfPrivacy.Removed
        // A draft saved before PDFs were cleaned still carries its metadata; the send cleans it first.
        is PdfMetadataResult.Removable -> ComposerPdfPrivacy.RemovedOnSend
        is PdfMetadataResult.Refused ->
            when {
                keepsOriginal -> ComposerPdfPrivacy.SendingAsIs
                metadata.reason == PdfMetadataRefusal.Signed -> ComposerPdfPrivacy.Signed
                metadata.reason == PdfMetadataRefusal.Encrypted -> ComposerPdfPrivacy.Encrypted
                else -> ComposerPdfPrivacy.Unsupported
            }
    }

/**
 * A staged PDF: its first page, its name, its page count and whether its metadata goes with it (#3377). Pure
 * presentation; [ComposerPdfShelfItem] reads the PDF.
 */
@Composable
@Suppress("LongParameterList")
internal fun ComposerPdfAttachmentCard(
    label: String,
    pages: Int?,
    firstPage: ImageBitmap?,
    privacy: ComposerPdfPrivacy?,
    onPreview: (() -> Unit)?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val summary = composerPdfSummary(pages)
    val privacyText = privacy?.let { stringResource(it.labelRes) }
    val previewLabel = stringResource(R.string.composer_preview_attachment)
    Surface(
        modifier =
            modifier
                .width(PDF_CARD_WIDTH.dp)
                .height(PDF_CARD_HEIGHT.dp)
                .then(
                    if (onPreview != null) {
                        // Clipped first, so the press highlight keeps the card's rounded corners.
                        Modifier.clip(PDF_CARD_SHAPE).clickable(
                            enabled = enabled,
                            onClickLabel = previewLabel,
                            role = Role.Button,
                            onClick = onPreview,
                        )
                    } else {
                        Modifier
                    },
                ).semantics { contentDescription = listOfNotNull(label, summary, privacyText).joinToString(", ") },
        shape = PDF_CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = amoledOutlineBorder(),
    ) {
        Box {
            Row(
                Modifier.fillMaxSize().padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComposerPdfFirstPage(firstPage)
                Spacer(Modifier.width(10.dp))
                if (composerTextScale() >= COMPACT_FONT_SCALE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (privacy != null) ComposerPdfPrivacyIcon(privacy, Modifier.padding(end = 6.dp).size(20.dp))
                        ComposerPdfFilename(label)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ComposerPdfFilename(label)
                        Text(
                            summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        if (privacy != null && privacyText != null) ComposerPdfPrivacyLine(privacy, privacyText)
                    }
                }
            }
            ComposerAccessoryRemoveButton(
                onClick = onRemove,
                enabled = enabled,
                description = stringResource(R.string.media_attachment_remove) + ": " + label,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

@Composable
private fun composerPdfSummary(pages: Int?): String =
    if (pages != null && pages > 0) pluralStringResource(R.plurals.composer_pdf_pages, pages, pages) else "PDF"

/** The first page on white, as paper, or the PDF icon until it renders or when it cannot. */
@Composable
private fun ComposerPdfFirstPage(firstPage: ImageBitmap?) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .size(PAGE_WIDTH.dp, PAGE_HEIGHT.dp)
            .clip(PAGE_SHAPE)
            .background(if (firstPage != null) Color.White else MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, outline, PAGE_SHAPE)
            .testTag("conversation.composer.pdf.page"),
        contentAlignment = Alignment.Center,
    ) {
        if (firstPage != null) {
            Image(
                firstPage,
                null,
                Modifier.fillMaxSize(),
                alignment = Alignment.TopCenter,
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Default.PictureAsPdf, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error)
        }
    }
}

/** Filename direction remains LTR even in RTL chats so its extension stays at the physical suffix. */
@Composable
private fun ComposerPdfFilename(label: String) {
    val (leading, suffix) = remember(label) { composerFilenameParts(label) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row {
            if (leading.isNotEmpty()) {
                Text(
                    leading,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(suffix, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** The system font scale times the in-app text size step, which the theme applies to its typography. */
@Composable
private fun composerTextScale(): Float {
    val appScale = MaterialTheme.typography.labelLarge.fontSize.value / BaseTypography.labelLarge.fontSize.value
    return LocalDensity.current.fontScale * appScale
}

@Composable
private fun ComposerPdfPrivacyLine(
    privacy: ComposerPdfPrivacy,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ComposerPdfPrivacyIcon(privacy, Modifier.padding(end = 4.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = privacy.color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ComposerPdfPrivacyIcon(
    privacy: ComposerPdfPrivacy,
    modifier: Modifier = Modifier,
) {
    val removed = privacy == ComposerPdfPrivacy.Removed || privacy == ComposerPdfPrivacy.RemovedOnSend
    val icon: ImageVector = if (removed) Icons.Default.Shield else Icons.Default.WarningAmber
    Icon(icon, null, modifier.size(12.dp), tint = privacy.color())
}

@Composable
private fun ComposerPdfPrivacy.color(): Color =
    when (this) {
        ComposerPdfPrivacy.Removed, ComposerPdfPrivacy.RemovedOnSend -> MaterialTheme.colorScheme.primary
        ComposerPdfPrivacy.SendingAsIs -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }

private val ComposerPdfPrivacy.labelRes: Int
    get() =
        when (this) {
            ComposerPdfPrivacy.Removed -> R.string.composer_pdf_metadata_removed
            ComposerPdfPrivacy.RemovedOnSend -> R.string.composer_pdf_metadata_removed_on_send
            ComposerPdfPrivacy.Signed -> R.string.composer_pdf_metadata_signed
            ComposerPdfPrivacy.Encrypted -> R.string.composer_pdf_metadata_encrypted
            ComposerPdfPrivacy.Unsupported -> R.string.composer_pdf_metadata_unsupported
            ComposerPdfPrivacy.SendingAsIs -> R.string.composer_pdf_metadata_sending_as_is
        }
