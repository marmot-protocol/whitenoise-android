package dev.ipf.whitenoise.android.media.pdf

private val PDF_HEADER = ascii("%PDF-")
private const val HEADER_WINDOW = 1024

/** Whether [bytes] start like a PDF: `%PDF-` within the first kilobyte, as readers accept. */
internal fun looksLikePdf(bytes: PdfBytes): Boolean =
    bytes.indexOf(PDF_HEADER, 0, minOf(bytes.size, HEADER_WINDOW)) >= 0

/**
 * Plans the same-length edits that remove a PDF's metadata, across every revision an incremental update kept:
 * - every document-information dictionary a trailer or cross-reference stream names, stored plainly or inside an
 *   object stream, and the strings it refers to when nothing else uses them;
 * - every XMP packet, in metadata streams and stored plainly in other unfiltered streams;
 * - camera, location and editing segments of embedded JPEG photos.
 * A signed or encrypted PDF is refused before anything is planned, and planning stops at the first refusal.
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
    if (plan.refusal != null) return plan
    encodeObjectStreams(objectStreams, plan)
    val covered = planMetadataStreams(bytes, layout, plan)
    val photos = planEmbeddedPhotos(bytes, layout, plan)
    if (plan.refusal == null) planLoosePackets(bytes, layout.plainStreams, covered + photos, plan)
    return plan
}

/** Where one object can be stored: plainly at file positions, or compressed in object streams. */
private class PdfObjectSources(
    val bytes: PdfBytes,
    val layout: PdfLayout,
    val objectStreams: List<PdfObjectStream>,
) {
    private val fileParser = PdfValueParser(bytes)

    /** Every stored value of [reference], each with the edit callback that overwrites inside it. */
    fun values(reference: Pair<Int, Int>): List<Pair<PdfValue, (Int, ByteArray) -> Unit>>? {
        val (number, generation) = reference
        if (number in layout.unreadable) return null
        val plain =
            layout.positions(number, generation).map { position ->
                fileParser.parse(position) to { at: Int, replacement: ByteArray -> plainEdits += at to replacement }
            }
        val compressed =
            if (generation != 0) {
                emptyList()
            } else {
                objectStreams.mapNotNull { stream ->
                    stream.offsets[number]?.let { PdfValueParser(stream.bytes).parse(it) to stream.editor() }
                }
            }
        return plain + compressed
    }

    val plainEdits = mutableListOf<Pair<Int, ByteArray>>()
}

/**
 * Empties every document-information dictionary and the strings it refers to. Refuses when a named dictionary or
 * string cannot be read, and when an indirect string is also used by something other than document information, such
 * as a form field, because emptying it would change that content.
 */
private fun planInfoDictionaries(
    bytes: PdfBytes,
    layout: PdfLayout,
    objectStreams: List<PdfObjectStream>,
    plan: PdfMetadataPlan,
) {
    val sources = PdfObjectSources(bytes, layout, objectStreams)
    val references = mutableListOf<PdfRef>()
    val fileEdit = { position: Int, replacement: ByteArray -> plan.replace(position, replacement) }
    val infoReferences = mutableSetOf<Pair<Int, Int>>()
    val named = layout.trailers.mapNotNull { it["Info"] } + layout.embeddedInfo
    named.forEach { info ->
        when (info) {
            is PdfRef -> infoReferences += info.number to info.generation
            is PdfDict -> blankInfoStrings(info, plan.fields, references, fileEdit)
            is PdfString -> {
                val holder = PdfDict(info.start, info.end, listOf("" to info))
                blankInfoStrings(holder, plan.fields, references, fileEdit)
            }
            else -> Unit
        }
    }
    infoReferences.forEach { reference ->
        val values = sources.values(reference)
        if (values == null || values.isEmpty()) return plan.refuse(PdfMetadataRefusal.Unsupported)
        values.forEach { (value, edit) -> if (value is PdfDict) blankInfoStrings(value, plan.fields, references, edit) }
    }
    if (references.isNotEmpty() && !blankReferencedStrings(references, sources, plan)) {
        return plan.refuse(PdfMetadataRefusal.Unsupported)
    }
    blankOrphanedInfo(layout.infoLike.filter { it !in infoReferences }, sources, plan)
    sources.plainEdits.forEach { (position, replacement) -> plan.replace(position, replacement) }
}

/**
 * Empties the strings of [candidates], earlier document-information dictionaries that editors leave behind when they
 * write a new one, once nothing in the file refers to them any more. Referenced ones are left alone.
 */
private fun blankOrphanedInfo(
    candidates: List<Pair<Int, Int>>,
    sources: PdfObjectSources,
    plan: PdfMetadataPlan,
) {
    if (candidates.isEmpty()) return
    val targets = candidates.toSet()
    val referenced = countReferences(sources.bytes, targets).keys.toMutableSet()
    sources.objectStreams.forEach { referenced += countReferences(it.bytes, targets).keys }
    val ignored = mutableListOf<PdfRef>()
    candidates.filter { it !in referenced }.forEach { (number, generation) ->
        sources.layout.positions(number, generation).forEach { position ->
            val dict = PdfValueParser(sources.bytes).parse(position) as? PdfDict ?: return@forEach
            blankInfoStrings(dict, plan.fields, ignored) { at, replacement -> plan.replace(at, replacement) }
        }
    }
}

/** Empties each string [references] name, returning false when one is missing or shared with other content. */
private fun blankReferencedStrings(
    references: List<PdfRef>,
    sources: PdfObjectSources,
    plan: PdfMetadataPlan,
): Boolean {
    val uses = references.groupingBy { it.number to it.generation }.eachCount()
    val counts = countReferences(sources.bytes, uses.keys).toMutableMap()
    sources.objectStreams.forEach { stream ->
        countReferences(stream.bytes, uses.keys).forEach { (key, count) -> counts[key] = (counts[key] ?: 0) + count }
    }
    return uses.all { (reference, infoUses) ->
        val values = sources.values(reference)
        if (values.isNullOrEmpty() || (counts[reference] ?: 0) > infoUses) return@all false
        values.forEach { (value, edit) ->
            if (value is PdfString && !value.isEmpty) {
                plan.fields += PdfMetadataField.Other
                edit(value.start, blankString(value))
            }
        }
        true
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
