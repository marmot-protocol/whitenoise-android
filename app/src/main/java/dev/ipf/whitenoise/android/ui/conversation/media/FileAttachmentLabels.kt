@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

/** Localizes category fallbacks while stable format abbreviations stay concise. */
@Composable
internal fun attachmentTypeLabel(presentation: AttachmentPresentation): String =
    presentation.formatLabel
        ?: when (presentation.iconCategory) {
            AttachmentIconCategory.AndroidPackage -> stringResource(R.string.attachment_type_android_package)
            AttachmentIconCategory.Pdf -> stringResource(R.string.attachment_type_pdf)
            AttachmentIconCategory.Archive -> stringResource(R.string.attachment_type_archive)
            AttachmentIconCategory.Document -> stringResource(R.string.attachment_type_document)
            AttachmentIconCategory.Spreadsheet -> stringResource(R.string.attachment_type_spreadsheet)
            AttachmentIconCategory.Presentation -> stringResource(R.string.attachment_type_presentation)
            AttachmentIconCategory.Text -> stringResource(R.string.attachment_type_text)
            AttachmentIconCategory.Code -> stringResource(R.string.attachment_type_code)
            AttachmentIconCategory.Audio -> stringResource(R.string.attachment_type_audio)
            AttachmentIconCategory.Video -> stringResource(R.string.attachment_type_video)
            AttachmentIconCategory.Image -> stringResource(R.string.attachment_type_image)
            AttachmentIconCategory.Generic -> stringResource(R.string.attachment_type_file)
        }

@Composable
internal fun attachmentTypeDescription(category: AttachmentIconCategory): String =
    when (category) {
        AttachmentIconCategory.AndroidPackage -> stringResource(R.string.attachment_type_android_package_description)
        AttachmentIconCategory.Pdf -> stringResource(R.string.attachment_type_pdf_description)
        AttachmentIconCategory.Archive -> stringResource(R.string.attachment_type_archive)
        AttachmentIconCategory.Document -> stringResource(R.string.attachment_type_document)
        AttachmentIconCategory.Spreadsheet -> stringResource(R.string.attachment_type_spreadsheet)
        AttachmentIconCategory.Presentation -> stringResource(R.string.attachment_type_presentation)
        AttachmentIconCategory.Text -> stringResource(R.string.attachment_type_text)
        AttachmentIconCategory.Code -> stringResource(R.string.attachment_type_code_description)
        AttachmentIconCategory.Audio -> stringResource(R.string.attachment_type_audio)
        AttachmentIconCategory.Video -> stringResource(R.string.attachment_type_video)
        AttachmentIconCategory.Image -> stringResource(R.string.attachment_type_image)
        AttachmentIconCategory.Generic -> stringResource(R.string.attachment_type_file)
    }

internal fun fileIconFor(category: AttachmentIconCategory): ImageVector =
    when (category) {
        AttachmentIconCategory.AndroidPackage -> Icons.Default.Android
        AttachmentIconCategory.Pdf -> Icons.Default.PictureAsPdf
        AttachmentIconCategory.Archive -> Icons.Default.Archive
        AttachmentIconCategory.Document -> Icons.Default.Description
        AttachmentIconCategory.Spreadsheet -> Icons.Default.TableChart
        AttachmentIconCategory.Presentation -> Icons.Default.Slideshow
        AttachmentIconCategory.Text -> Icons.AutoMirrored.Filled.TextSnippet
        AttachmentIconCategory.Code -> Icons.Default.Code
        AttachmentIconCategory.Audio -> Icons.Default.Audiotrack
        AttachmentIconCategory.Video -> Icons.Default.Movie
        AttachmentIconCategory.Image -> Icons.Default.Image
        AttachmentIconCategory.Generic -> Icons.Default.Description
    }
