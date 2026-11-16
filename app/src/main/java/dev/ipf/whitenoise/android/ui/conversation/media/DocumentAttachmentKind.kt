package dev.ipf.whitenoise.android.ui.conversation.media

import java.util.Locale

internal enum class DocumentAttachmentKind { Pdf, Html }

/** Exclusions precede explicit document types, which precede filename hints. */
internal fun documentAttachmentKind(
    mediaType: String,
    fileName: String,
): DocumentAttachmentKind? {
    val mime = mediaType.substringBefore(';').trim().lowercase(Locale.ROOT)
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (isUnsupportedXml(mime) ||
        extension in
        setOf(
            "apk",
            "svg",
            "svgz",
            "xml",
            "xsl",
            "xslt",
            "js",
            "mjs",
            "mht",
            "mhtml",
            "shtml",
            "xht",
            "hta",
        ) ||
        mime in
        setOf(
            "application/vnd.android.package-archive",
            "image/svg+xml",
            "application/xml",
            "text/xml",
            "text/javascript",
            "application/javascript",
            "application/x-javascript",
            "application/ecmascript",
            "text/ecmascript",
            "message/rfc822",
            "multipart/related",
            "application/x-mimearchive",
        )
    ) {
        return null
    }
    return when {
        mime == "application/pdf" -> DocumentAttachmentKind.Pdf
        mime == "text/html" || mime == "application/xhtml+xml" -> DocumentAttachmentKind.Html
        extension == "pdf" -> DocumentAttachmentKind.Pdf
        extension in setOf("htm", "html", "xhtml") -> DocumentAttachmentKind.Html
        else -> null
    }
}

private fun isUnsupportedXml(mime: String): Boolean = mime.endsWith("+xml") && mime != "application/xhtml+xml"
