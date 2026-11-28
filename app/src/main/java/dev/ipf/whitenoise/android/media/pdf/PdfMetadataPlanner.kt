package dev.ipf.whitenoise.android.media.pdf

private val PDF_HEADER = ascii("%PDF-")
private const val HEADER_WINDOW = 1024

/** The first non-whitespace bytes must be the PDF header, even for a mislabeled file. */
internal fun looksLikePdf(bytes: PdfBytes): Boolean {
    val end = minOf(bytes.size, HEADER_WINDOW)
    var start = 0
    while (start < end && isPdfWhitespace(bytes[start])) start++
    return start + PDF_HEADER.size <= end && bytes.matches(start, PDF_HEADER)
}

/**
 * Plans the same-length edits that remove a PDF's metadata, across every revision an incremental update kept:
 * - every document-information dictionary a trailer or cross-reference stream names, stored plainly or inside an
 *   object stream, earlier ones nothing names any more, and the strings they refer to when nothing else uses them;
 * - every XMP packet, in metadata streams and in unfiltered streams that hold nothing else;
 * - camera, location and editing segments of embedded JPEG photos.
 * A signed or encrypted PDF is refused before anything is planned, and planning stops at the first refusal. So is
 * one whose stream ends the walk had to guess and cannot confirm, because what followed may have been stream data.
 */
internal fun planPdfMetadataRemoval(input: PdfBytes): PdfMetadataPlan {
    val bytes = BudgetedPdfBytes(input)
    val plan = PdfMetadataPlan()
    val layout = PdfObjectWalk(bytes).walk()
    if (containsName(bytes, "ByteRange")) plan.refuse(PdfMetadataRefusal.Signed)
    if (containsName(bytes, "PieceInfo")) plan.refuse(PdfMetadataRefusal.Unsupported)
    if (layout.trailers.any { it.has("Encrypt") }) plan.refuse(PdfMetadataRefusal.Encrypted)
    val objectStreams = if (plan.refusal == null) decodeObjectStreams(bytes, layout, plan) else emptyList()
    val sources = PdfObjectSources(bytes, layout, objectStreams, plan::replace)
    if (plan.refusal == null && !layout.guessed.all(sources::confirms)) plan.refuse(PdfMetadataRefusal.Unsupported)
    if (plan.refusal == null) planInfoDictionaries(sources, plan)
    if (plan.refusal == null) {
        encodeObjectStreams(objectStreams, plan)
        val covered = planMetadataStreams(bytes, layout, plan)
        val photos = planEmbeddedPhotos(bytes, layout, plan)
        if (plan.refusal == null) planLoosePackets(bytes, layout.plainStreams, covered + photos, plan)
    }
    return plan
}

/** Where one object can be stored: plainly at file positions, or compressed in object streams. */
private class PdfObjectSources(
    val bytes: PdfBytes,
    val layout: PdfLayout,
    val objectStreams: List<PdfObjectStream>,
    private val editFile: (Int, ByteArray) -> Unit,
) {
    private val fileParser = PdfValueParser(bytes)
    private val compressed = HashMap<Int, MutableList<Pair<PdfObjectStream, Int>>>()
    private val resolved = mutableMapOf<Pair<Int, Int>, List<Pair<PdfValue, (Int, ByteArray) -> Unit>>>()

    init {
        objectStreams.forEach { stream ->
            stream.offsets.forEach { (number, offset) ->
                compressed.getOrPut(number) { mutableListOf() } += stream to offset
            }
        }
    }

    /** Every stored value of [reference], each with the edit callback that overwrites inside it. */
    fun values(reference: Pair<Int, Int>): List<Pair<PdfValue, (Int, ByteArray) -> Unit>>? =
        if (reference.first in layout.unreadable) null else resolved.getOrPut(reference) { parseValues(reference) }

    private fun parseValues(reference: Pair<Int, Int>): List<Pair<PdfValue, (Int, ByteArray) -> Unit>> {
        val (number, generation) = reference
        val plain = layout.positions(number, generation).map { position -> fileParser.parse(position) to editFile }
        val packed = if (generation == 0) compressed[number].orEmpty() else emptyList()
        return plain + packed.map { (stream, offset) -> stream.valueAt(offset) to stream.editor() }
    }

    /** Numbers of compressed dictionaries shaped like document information. */
    fun compressedInfoLike(): List<Pair<Int, Int>> =
        compressed
            .filterValues { members -> members.map { (stream, offset) -> isInfoLikeAt(stream, offset) }.any { it } }
            .keys
            .map { it to 0 }

    private fun isInfoLikeAt(
        stream: PdfObjectStream,
        offset: Int,
    ): Boolean =
        try {
            val value = stream.valueAt(offset)
            value.collectStringRanges(stream.strings)
            (value as? PdfDict)?.let(::isInfoLike) == true
        } catch (_: PdfSyntaxException) {
            false
        }

    /**
     * Whether [stream]'s declared `/Length`, stored once, ends its data exactly where the walk found its `endstream`.
     * A length stored in several revisions is ambiguous, so it confirms nothing.
     */
    fun confirms(stream: PdfGuessedStream): Boolean {
        val reference = stream.length as? PdfRef
        val declared = reference?.let { values(it.number to it.generation) }?.singleOrNull()?.first as? PdfNumber
        val end = declared?.intOrNull()?.let { stream.dataStart.toLong() + it }
        return stream.endKeyword >= 0 && end != null && end <= bytes.size && skipsTo(end.toInt(), stream.endKeyword)
    }

    private fun skipsTo(
        from: Int,
        keyword: Int,
    ): Boolean = bytes.skipSpace(from) == keyword

    /** How often each of [targets] is referred to anywhere: in the file and in object streams it compresses. */
    fun referenceCounts(targets: Set<Pair<Int, Int>>): Map<Pair<Int, Int>, Int> {
        if (targets.isEmpty()) return emptyMap()
        val plainStrings =
            layout.strings +
                objectStreams.filterNot { it.data.compressed }.flatMap { stream ->
                    val start = stream.data.stream.dataStart
                    stream.strings.map { it.first + start..it.last + start }
                }
        val counts = countReferences(bytes, targets, plainStrings.sortedBy { it.first }).toMutableMap()
        // An unfiltered object stream's references are already in the file's own bytes.
        objectStreams.filter { it.data.compressed }.forEach { stream ->
            countReferences(stream.bytes, targets, stream.strings.sortedBy { it.first }).forEach { (key, count) ->
                counts[key] = (counts[key] ?: 0) + count
            }
        }
        return counts
    }
}

/**
 * Empties every document-information dictionary a trailer names, the copies pdfTeX embeds, earlier ones nothing
 * names any more, and the strings they refer to. Refuses when one cannot be read, when a version of a named object
 * is not document information, such as an object number an update reused for a form field, and when an indirect
 * string is also used by other content, because emptying either would change that content.
 */
private fun planInfoDictionaries(
    sources: PdfObjectSources,
    plan: PdfMetadataPlan,
) {
    val layout = sources.layout
    val references = mutableListOf<PdfRef>()
    val named = mutableSetOf<Pair<Int, Int>>()
    val stringTargets = mutableMapOf<Pair<Int, Int>, Boolean>()
    (layout.trailers.mapNotNull { it["Info"] } + layout.embeddedInfo).forEach { info ->
        if (info is PdfRef) {
            val target = info.number to info.generation
            val isString =
                stringTargets.getOrPut(target) {
                    val values = sources.values(target)
                    !values.isNullOrEmpty() && values.all { it.first is PdfString }
                }
            // A string named indirectly, such as pdfTeX's file name, is emptied only when nothing else uses it.
            if (isString) references += info else named += target
        } else if (!blankInfoValue(info, plan.fields, references, plan::replace)) {
            plan.refuse(PdfMetadataRefusal.Unsupported)
            return
        }
    }
    val orphans = (layout.infoLike + sources.compressedInfoLike()).toSet() - named
    val unreferenced = orphans - sources.referenceCounts(orphans).keys
    val blanked =
        (named + unreferenced).all { reference ->
            val values = sources.values(reference)
            !values.isNullOrEmpty() &&
                values.all { (value, edit) -> value is PdfDict && blankInfoValue(value, plan.fields, references, edit) }
        }
    if (!blanked || !blankReferencedStrings(references, sources, plan)) {
        plan.refuse(PdfMetadataRefusal.Unsupported)
    }
}

/**
 * Empties the strings of one stored document-information [value]: a dictionary, or a string named directly. Returns
 * false for a dictionary that marks other content, which a reused object number can make it.
 */
private fun blankInfoValue(
    value: PdfValue,
    fields: MutableSet<PdfMetadataField>,
    references: MutableList<PdfRef>,
    edit: (Int, ByteArray) -> Unit,
): Boolean {
    when (value) {
        is PdfDict -> if (!hasContentKeys(value)) blankInfoStrings(value, fields, references, edit)
        is PdfString -> blankInfoStrings(PdfDict(value.start, value.end, listOf("" to value)), fields, references, edit)
        else -> Unit
    }
    return value !is PdfDict || !hasContentKeys(value)
}

/** Empties each string [references] name, returning false when one is missing or shared with other content. */
private fun blankReferencedStrings(
    references: List<PdfRef>,
    sources: PdfObjectSources,
    plan: PdfMetadataPlan,
): Boolean {
    val uses = references.groupingBy { it.number to it.generation }.eachCount()
    val counts = sources.referenceCounts(uses.keys)
    return uses.all { (reference, infoUses) ->
        val values = sources.values(reference)
        val owned = !values.isNullOrEmpty() && (counts[reference] ?: 0) <= infoUses
        if (owned) {
            values.orEmpty().forEach { (value, edit) ->
                if (value is PdfString && !value.isEmpty) {
                    plan.fields += PdfMetadataField.Other
                    edit(value.start, blankString(value))
                }
            }
        }
        owned
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
