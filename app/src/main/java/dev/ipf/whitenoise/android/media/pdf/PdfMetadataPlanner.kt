package dev.ipf.whitenoise.android.media.pdf

private val PDF_HEADER = ascii("%PDF-")
private const val HEADER_WINDOW = 1024

/** Whether [bytes] start like a PDF: `%PDF-` within the first kilobyte, as readers accept. */
internal fun looksLikePdf(bytes: PdfBytes): Boolean =
    bytes.indexOf(PDF_HEADER, 0, minOf(bytes.size, HEADER_WINDOW)) >= 0

/**
 * Plans the same-length edits that remove a PDF's metadata, across every revision an incremental update kept:
 * - every document-information dictionary a trailer or cross-reference stream names, stored plainly or inside an
 *   object stream, and the strings it refers to;
 * - every XMP packet, in metadata streams and stored plainly anywhere else;
 * - camera, location and editing segments of embedded JPEG photos.
 * A signed or encrypted PDF is refused before anything is planned.
 */
internal fun planPdfMetadataRemoval(bytes: PdfBytes): PdfMetadataPlan {
    val plan = PdfMetadataPlan()
    val layout = PdfObjectWalk(bytes).walk()
    if (layout.signed) plan.refuse(PdfMetadataRefusal.Signed)
    if (layout.trailers.any { it.has("Encrypt") }) plan.refuse(PdfMetadataRefusal.Encrypted)
    if (plan.refusal != null) return plan
    val objectStreams = decodeObjectStreams(bytes, layout, plan)
    if (plan.refusal != null) return plan
    planInfoDictionaries(bytes, layout, objectStreams, plan)
    encodeObjectStreams(objectStreams, plan)
    val covered = planMetadataStreams(bytes, layout, plan)
    val photos = planEmbeddedPhotos(bytes, layout, plan)
    planLoosePackets(bytes, covered + photos, plan)
    return plan
}

private fun planInfoDictionaries(
    bytes: PdfBytes,
    layout: PdfLayout,
    objectStreams: List<PdfObjectStream>,
    plan: PdfMetadataPlan,
) {
    val infoNumbers = mutableSetOf<Int>()
    val references = mutableSetOf<Int>()
    val fileEdit = { position: Int, replacement: ByteArray -> plan.replace(position, replacement) }
    layout.trailers.forEach { trailer ->
        when (val info = trailer["Info"]) {
            is PdfRef -> infoNumbers += info.number
            is PdfDict -> blankInfoStrings(info, plan.fields, references, fileEdit)
            else -> Unit
        }
    }
    val fileParser = PdfValueParser(bytes)
    infoNumbers.forEach { number ->
        layout.positions(number).forEach { position ->
            (fileParser.parse(position) as? PdfDict)?.let { blankInfoStrings(it, plan.fields, references, fileEdit) }
        }
        objectStreams.forEach { stream ->
            val position = stream.offsets[number] ?: return@forEach
            val dict = PdfValueParser(stream.bytes).parse(position) as? PdfDict ?: return@forEach
            blankInfoStrings(dict, plan.fields, references, stream.editor())
        }
    }
    references.forEach { number -> blankReferencedString(number, fileParser, layout, objectStreams, plan) }
}

/** Empties the string object [number], wherever it is stored, that a document-information entry refers to. */
private fun blankReferencedString(
    number: Int,
    fileParser: PdfValueParser,
    layout: PdfLayout,
    objectStreams: List<PdfObjectStream>,
    plan: PdfMetadataPlan,
) {
    layout.positions(number).forEach { position ->
        val value = fileParser.parse(position) as? PdfString
        if (value != null && !value.isEmpty) {
            plan.fields += PdfMetadataField.Other
            plan.replace(value.start, blankString(value))
        }
    }
    objectStreams.forEach { stream ->
        val position = stream.offsets[number] ?: return@forEach
        val value = PdfValueParser(stream.bytes).parse(position) as? PdfString ?: return@forEach
        if (!value.isEmpty) {
            plan.fields += PdfMetadataField.Other
            stream.editor()(value.start, blankString(value))
        }
    }
}

/** An edit callback that overwrites this object stream's decoded data and marks it for re-encoding. */
private fun PdfObjectStream.editor(): (Int, ByteArray) -> Unit =
    { position, replacement ->
        replacement.copyInto(data.data, position)
        changed = true
    }

/** Plans zeroing for every embedded JPEG photo, refusing photos wrapped in another filter. Returns their ranges. */
private fun planEmbeddedPhotos(
    bytes: PdfBytes,
    layout: PdfLayout,
    plan: PdfMetadataPlan,
): List<IntRange> =
    layout.streams.filter { it.dict.name("Subtype") == "Image" }.map { stream ->
        val filters = stream.dict.filters()
        val plain = filters == listOf("DCTDecode") || filters == listOf("DCT")
        if (!plain || !PdfJpegMetadata.plan(bytes, stream.dataStart, stream.dataEnd, plan)) {
            plan.refuse(PdfMetadataRefusal.Unsupported)
        }
        stream.dataStart until stream.dataEnd
    }
