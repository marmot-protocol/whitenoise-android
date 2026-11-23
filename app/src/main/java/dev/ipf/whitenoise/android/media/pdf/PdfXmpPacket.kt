package dev.ipf.whitenoise.android.media.pdf

// Bounds how far a property element's own attributes may run before its value.
private const val MAX_ELEMENT_ATTRIBUTES = 256

/** Matches [property] holding one [value] character, as an attribute or as an element with optional attributes. */
private fun identification(
    property: String,
    value: String,
): Regex = Regex("""$property(?:\s*=\s*["']|(?:\s[^>]{0,$MAX_ELEMENT_ATTRIBUTES})?>)\s*($value)\s*["'<]""")

private val PDFA_PART = identification("pdfaid:part", "[1-4]")
private val PDFA_CONFORMANCE = identification("pdfaid:conformance", "[ABUFabuf]")
private val PDFUA_PART = identification("pdfuaid:part", "[1-2]")
private const val PDFA_NAMESPACE = "http://www.aiim.org/pdfa/ns/id/"
private const val PDFUA_NAMESPACE = "http://www.aiim.org/pdfua/ns/id/"
private const val PACKET_TAIL = """<?xpacket end="w"?>"""

/**
 * The XMP packet that replaces a document's metadata: no properties beyond the PDF/A and PDF/UA identification the
 * original declared, which conforming readers need to keep treating the file as PDF/A or PDF/UA. Only those small,
 * validated values are carried over.
 */
internal class PdfXmpPacket private constructor(
    val head: ByteArray,
    val tail: ByteArray,
) {
    /** The packet padded with spaces to exactly [length] bytes, or only spaces when it does not fit. */
    fun fitted(length: Int): ByteArray {
        if (length < head.size + tail.size) return ByteArray(length) { ' '.code.toByte() }
        return head + ByteArray(length - head.size - tail.size) { ' '.code.toByte() } + tail
    }

    companion object {
        /** The replacement for [original], keeping only its PDF/A and PDF/UA identification. */
        fun replacing(original: ByteArray): PdfXmpPacket {
            val text = String(original, Charsets.ISO_8859_1)
            val identification =
                buildString {
                    PDFA_PART.find(text)?.let { part ->
                        append(""" xmlns:pdfaid="$PDFA_NAMESPACE" pdfaid:part="${part.groupValues[1]}"""")
                        PDFA_CONFORMANCE.find(text)?.let {
                            append(""" pdfaid:conformance="${it.groupValues[1].uppercase()}"""")
                        }
                    }
                    PDFUA_PART.find(text)?.let {
                        append(""" xmlns:pdfuaid="$PDFUA_NAMESPACE" pdfuaid:part="${it.groupValues[1]}"""")
                    }
                }
            val description =
                if (identification.isEmpty()) "" else """<rdf:Description rdf:about=""$identification/>"""
            val head =
                "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
                    "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
                    "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                    description +
                    "</rdf:RDF></x:xmpmeta>\n"
            return PdfXmpPacket(head.toByteArray(Charsets.UTF_8), ascii(PACKET_TAIL))
        }

        /** Whether [content] already is a replacement packet, padded or not, or holds only white space. */
        fun isReplacement(content: ByteArray): Boolean {
            if (content.all { isPdfWhitespace(it.toInt() and BYTE_MASK) }) return true
            val packet = replacing(content)
            return content.withoutWhitespace().contentEquals((packet.head + packet.tail).withoutWhitespace())
        }

        private fun ByteArray.withoutWhitespace(): ByteArray =
            filter { !isPdfWhitespace(it.toInt() and BYTE_MASK) }.toByteArray()
    }
}
