package dev.ipf.whitenoise.android.media.pdf

private const val INDEX_SHIFT = 32
private const val LOW_BITS = 0xFFFFFFFFL
private const val MAX_OBJECTS = 250_000
private const val INITIAL_OBJECTS = 256

/** A stream the metadata plan reads: its object, its dictionary and its data range. */
internal class PdfStream(
    val objectNumber: Int,
    val dict: PdfDict,
    val dataStart: Int,
    val dataEnd: Int,
    /** Whether a direct `/Length` confirmed [dataEnd]; otherwise it was found from `endstream`. */
    val exact: Boolean,
)

/**
 * A stream whose end was found by searching for `endstream`, because its `/Length` was indirect or wrong: [length]
 * is its declared `/Length` and [endKeyword] where the search stopped, or -1 when no `endstream` followed.
 */
internal class PdfGuessedStream(
    val length: PdfValue?,
    val dataStart: Int,
    val endKeyword: Int,
)

/** What one walk over an uncompressed PDF file found. */
internal class PdfLayout(
    private val objects: PdfObjectIndex,
    val trailers: List<PdfDict>,
    val streams: List<PdfStream>,
    /** Data ranges of streams stored without a filter. */
    val plainStreams: List<IntRange>,
    /** Numbers of objects whose header was found but whose value could not be read. */
    val unreadable: Set<Int>,
    /** Plain dictionary objects shaped like document information, which a trailer may no longer name. */
    val infoLike: List<Pair<Int, Int>>,
    /** Values of pdfTeX's private `/PTEX.*` entries: an embedded figure's file name and document information. */
    val embeddedInfo: List<PdfValue>,
    /** Streams whose end the walk had to guess; the plan confirms each before trusting what followed it. */
    val guessed: List<PdfGuessedStream>,
    val strings: List<IntRange>,
) {
    /** Every uncompressed object numbered [number] with [generation], oldest revision first. */
    fun positions(
        number: Int,
        generation: Int,
    ): List<Int> = objects.find(number, generation)
}

/** Uncompressed objects by number, built during the walk and sorted once. */
internal class PdfObjectIndex {
    private var keys = LongArray(INITIAL_OBJECTS)
    private var positions = IntArray(INITIAL_OBJECTS)
    private var generations = IntArray(INITIAL_OBJECTS)
    private var count = 0
    private var sorted: LongArray? = null
    private val positionsByNumber = mutableMapOf<Int, Map<Int, List<Int>>>()

    fun add(
        number: Int,
        generation: Int,
        position: Int,
    ) {
        if (count == MAX_OBJECTS) pdfSyntaxError()
        if (count == keys.size) {
            keys = keys.copyOf(count * 2)
            positions = positions.copyOf(count * 2)
            generations = generations.copyOf(count * 2)
        }
        keys[count] = (number.toLong() shl INDEX_SHIFT) or count.toLong()
        positions[count] = position
        generations[count] = generation
        count++
    }

    /** Resolves a number's generations once, preserving file order within each generation. */
    fun find(
        number: Int,
        generation: Int,
    ): List<Int> = positionsByNumber.getOrPut(number) { positionsFor(number) }[generation].orEmpty()

    private fun positionsFor(number: Int): Map<Int, List<Int>> {
        val index = sorted ?: keys.copyOf(count).also { it.sort() }.also { sorted = it }
        var low = 0
        var high = index.size
        val key = number.toLong() shl INDEX_SHIFT
        while (low < high) {
            val middle = (low + high) ushr 1
            if (index[middle] < key) low = middle + 1 else high = middle
        }
        val found = mutableMapOf<Int, MutableList<Int>>()
        while (low < index.size && (index[low] ushr INDEX_SHIFT) == number.toLong()) {
            val record = (index[low] and LOW_BITS).toInt()
            found.getOrPut(generations[record]) { mutableListOf() } += positions[record]
            low++
        }
        return found
    }
}

private val INFO_KEYS = setOf("Author", "Creator", "Producer", "CreationDate", "ModDate")
private val CONTENT_KEYS = setOf("Type", "Subtype", "Parent", "Rect", "P", "FT", "Kids", "Contents")

/**
 * Whether [dict] looks like a document-information dictionary: it has an author, app or date entry and none of the
 * keys that mark page content, annotations, form fields or other typed objects.
 */
internal fun isInfoLike(dict: PdfDict): Boolean = dict.entries.any { it.first in INFO_KEYS } && !hasContentKeys(dict)

/** Whether [dict] has a key that marks page content, an annotation, a form field or another typed object. */
internal fun hasContentKeys(dict: PdfDict): Boolean = dict.entries.any { it.first in CONTENT_KEYS }
