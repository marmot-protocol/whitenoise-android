package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.core.ProfileSanitizer
import java.util.Locale

internal enum class AttachmentIconCategory {
    AndroidPackage,
    Pdf,
    Archive,
    Document,
    Spreadsheet,
    Presentation,
    Text,
    Code,
    Audio,
    Video,
    Image,
    Generic,
}

/** Presentation-only metadata. It never changes the MIME used to open a file. */
internal data class AttachmentPresentation(
    val formatLabel: String?,
    val iconCategory: AttachmentIconCategory,
)

private val exactMimePresentations =
    mapOf(
        "application/vnd.android.package-archive" to format("APK", AttachmentIconCategory.AndroidPackage),
        "application/pdf" to format("PDF", AttachmentIconCategory.Pdf),
        "application/zip" to format("ZIP", AttachmentIconCategory.Archive),
        "application/x-zip-compressed" to format("ZIP", AttachmentIconCategory.Archive),
        "application/x-7z-compressed" to format("7Z", AttachmentIconCategory.Archive),
        "application/vnd.rar" to format("RAR", AttachmentIconCategory.Archive),
        "application/x-rar-compressed" to format("RAR", AttachmentIconCategory.Archive),
        "application/x-tar" to format("TAR", AttachmentIconCategory.Archive),
        "application/gzip" to format("GZ", AttachmentIconCategory.Archive),
        "application/x-gzip" to format("GZ", AttachmentIconCategory.Archive),
        "application/x-bzip2" to format("BZ2", AttachmentIconCategory.Archive),
        "application/x-xz" to format("XZ", AttachmentIconCategory.Archive),
        "application/msword" to format("DOC", AttachmentIconCategory.Document),
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to
            format("DOCX", AttachmentIconCategory.Document),
        "application/vnd.oasis.opendocument.text" to format("ODT", AttachmentIconCategory.Document),
        "application/vnd.ms-excel" to format("XLS", AttachmentIconCategory.Spreadsheet),
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to
            format("XLSX", AttachmentIconCategory.Spreadsheet),
        "application/vnd.oasis.opendocument.spreadsheet" to
            format("ODS", AttachmentIconCategory.Spreadsheet),
        "text/csv" to format("CSV", AttachmentIconCategory.Spreadsheet),
        "application/vnd.ms-powerpoint" to format("PPT", AttachmentIconCategory.Presentation),
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to
            format("PPTX", AttachmentIconCategory.Presentation),
        "application/vnd.oasis.opendocument.presentation" to
            format("ODP", AttachmentIconCategory.Presentation),
        "application/rtf" to format("RTF", AttachmentIconCategory.Text),
        "text/rtf" to format("RTF", AttachmentIconCategory.Text),
        "text/markdown" to format("Markdown", AttachmentIconCategory.Text),
        "application/json" to format("JSON", AttachmentIconCategory.Code),
        "application/ld+json" to format("JSON", AttachmentIconCategory.Code),
        "application/xml" to format("XML", AttachmentIconCategory.Code),
        "application/yaml" to format("YAML", AttachmentIconCategory.Code),
        "application/x-yaml" to format("YAML", AttachmentIconCategory.Code),
        "text/yaml" to format("YAML", AttachmentIconCategory.Code),
    )

private val fixedExtensionPresentations =
    mapOf(
        "apk" to format("APK", AttachmentIconCategory.AndroidPackage),
        "pdf" to format("PDF", AttachmentIconCategory.Pdf),
        "zip" to format("ZIP", AttachmentIconCategory.Archive),
        "7z" to format("7Z", AttachmentIconCategory.Archive),
        "rar" to format("RAR", AttachmentIconCategory.Archive),
        "tar" to format("TAR", AttachmentIconCategory.Archive),
        "gz" to format("GZ", AttachmentIconCategory.Archive),
        "bz2" to format("BZ2", AttachmentIconCategory.Archive),
        "xz" to format("XZ", AttachmentIconCategory.Archive),
        "tar.gz" to format("TAR.GZ", AttachmentIconCategory.Archive),
        "tar.bz2" to format("TAR.BZ2", AttachmentIconCategory.Archive),
        "tar.xz" to format("TAR.XZ", AttachmentIconCategory.Archive),
        "doc" to format("DOC", AttachmentIconCategory.Document),
        "docx" to format("DOCX", AttachmentIconCategory.Document),
        "odt" to format("ODT", AttachmentIconCategory.Document),
        "xls" to format("XLS", AttachmentIconCategory.Spreadsheet),
        "xlsx" to format("XLSX", AttachmentIconCategory.Spreadsheet),
        "ods" to format("ODS", AttachmentIconCategory.Spreadsheet),
        "csv" to format("CSV", AttachmentIconCategory.Spreadsheet),
        "ppt" to format("PPT", AttachmentIconCategory.Presentation),
        "pptx" to format("PPTX", AttachmentIconCategory.Presentation),
        "odp" to format("ODP", AttachmentIconCategory.Presentation),
        "txt" to format("TXT", AttachmentIconCategory.Text),
        "rtf" to format("RTF", AttachmentIconCategory.Text),
        "md" to format("Markdown", AttachmentIconCategory.Text),
        "markdown" to format("Markdown", AttachmentIconCategory.Text),
        "json" to format("JSON", AttachmentIconCategory.Code),
        "xml" to format("XML", AttachmentIconCategory.Code),
        "yaml" to format("YAML", AttachmentIconCategory.Code),
        "yml" to format("YAML", AttachmentIconCategory.Code),
        "jpg" to format("JPG", AttachmentIconCategory.Image),
        "jpeg" to format("JPG", AttachmentIconCategory.Image),
    )

private val codeExtensions =
    setOf("kt", "kts", "java", "swift", "rs", "py", "js", "jsx", "ts", "tsx", "html", "css", "sh", "sql", "toml")
private val imageExtensions = setOf("png", "gif", "webp", "heic", "avif", "svg")
private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac")
private val videoExtensions = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi")
private val compoundExtensions = listOf("tar.gz", "tar.bz2", "tar.xz")
private val safeSimpleExtension = Regex("^[a-z0-9]{1,8}$")

internal fun resolveAttachmentPresentation(
    mediaType: String,
    fileName: String,
): AttachmentPresentation {
    val normalizedMime =
        mediaType
            .substringBefore(';')
            .trim()
            .lowercase(Locale.ROOT)
    val extension = safeAttachmentExtension(fileName)
    val exact = exactMimePresentations[normalizedMime]

    if (exact != null) {
        val byExtension = extension?.let(::extensionPresentation)
        return byExtension?.takeIf { exact.formatLabel == null && it.iconCategory == exact.iconCategory } ?: exact
    }

    val family = mimeFamilyCategory(normalizedMime)
    val byExtension = extension?.let(::extensionPresentation)
    return family?.let { familyPresentation(byExtension, it) }
        ?: byExtension
        ?: AttachmentPresentation(extension?.uppercase(Locale.ROOT), AttachmentIconCategory.Generic)
}

/** A remote attachment name reduced to a non-spoofing, display-only basename. */
internal fun safeAttachmentDisplayName(fileName: String): String? =
    ProfileSanitizer
        .displayName(fileName.replace('\\', '/').substringAfterLast('/'))
        ?.takeIf { it != "." && it != ".." }

private fun mimeFamilyCategory(mime: String): AttachmentIconCategory? =
    when {
        mime.startsWith("image/") -> AttachmentIconCategory.Image
        mime.startsWith("audio/") -> AttachmentIconCategory.Audio
        mime.startsWith("video/") -> AttachmentIconCategory.Video
        mime.startsWith("text/") -> AttachmentIconCategory.Text
        else -> null
    }

private fun familyPresentation(
    byExtension: AttachmentPresentation?,
    category: AttachmentIconCategory,
): AttachmentPresentation =
    byExtension?.takeIf { it.iconCategory == category }
        ?: AttachmentPresentation(formatLabel = null, iconCategory = category)

private fun extensionPresentation(extension: String): AttachmentPresentation? =
    fixedExtensionPresentations[extension]
        ?: when (extension) {
            in codeExtensions -> format(extension.uppercase(Locale.ROOT), AttachmentIconCategory.Code)
            in imageExtensions -> format(extension.uppercase(Locale.ROOT), AttachmentIconCategory.Image)
            in audioExtensions -> format(extension.uppercase(Locale.ROOT), AttachmentIconCategory.Audio)
            in videoExtensions -> format(extension.uppercase(Locale.ROOT), AttachmentIconCategory.Video)
            else -> null
        }

private fun format(
    label: String,
    category: AttachmentIconCategory,
): AttachmentPresentation = AttachmentPresentation(label, category)

private fun safeAttachmentExtension(fileName: String): String? {
    val leaf = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
    val lowercase = leaf.lowercase(Locale.ROOT)
    val compound = compoundExtensions.firstOrNull { lowercase.endsWith(".$it") }
    val simple = lowercase.substringAfterLast('.', missingDelimiterValue = "")
    return when {
        leaf.isEmpty() || leaf.endsWith('.') -> null
        compound != null -> compound
        simple.matches(safeSimpleExtension) -> simple
        else -> null
    }
}
