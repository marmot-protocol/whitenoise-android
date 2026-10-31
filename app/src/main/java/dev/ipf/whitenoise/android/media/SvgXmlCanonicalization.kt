package dev.ipf.whitenoise.android.media

/** Both parsers receive the same strict UTF-8 characters with no byte-decoder declaration left to reinterpret. */
internal fun canonicalSvgXml(xml: String): String {
    val canonical =
        if (xml.startsWith("<?xml")) {
            val end = xml.indexOf("?>")
            require(end >= 0)
            val declaration = xml.substring(0, end + 2)
            require(SVG_XML_DECLARATION.matches(declaration))
            xml.substring(end + 2)
        } else {
            xml
        }
    // Reject malformed/duplicate declarations and every alternate '<?xm' autodetection prefix, even in comments.
    require(!canonical.contains("<?xm", ignoreCase = true))
    return canonical
}

private val SVG_XML_DECLARATION =
    Regex(
        """<\?xml[ \t\r\n]+version[ \t\r\n]*=[ \t\r\n]*(?:"1\.[01]"|'1\.[01]')(?:[ \t\r\n]+encoding[ \t\r\n]*=[ \t\r\n]*(?:"(?i:UTF-8)"|'(?i:UTF-8)'))?(?:[ \t\r\n]+standalone[ \t\r\n]*=[ \t\r\n]*(?:"(?:yes|no)"|'(?:yes|no)'))?[ \t\r\n]*\?>""",
    )
