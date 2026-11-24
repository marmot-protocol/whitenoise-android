package dev.ipf.whitenoise.android.media.pdf

/** A parsed PDF value and the byte range [start] until [end] it was read from. */
internal sealed class PdfValue(
    val start: Int,
    val end: Int,
)

internal class PdfDict(
    start: Int,
    end: Int,
    val entries: List<Pair<String, PdfValue>>,
) : PdfValue(start, end) {
    operator fun get(key: String): PdfValue? = entries.lastOrNull { it.first == key }?.second

    fun name(key: String): String? = (get(key) as? PdfName)?.name

    fun has(key: String): Boolean = entries.any { it.first == key }

    /** The names of a `/Filter` entry, whether it holds one name or an array of them. */
    fun filters(): List<String>? =
        when (val filter = get("Filter")) {
            null -> emptyList()
            is PdfName -> listOf(filter.name)
            is PdfArray -> filter.items.map { (it as? PdfName)?.name ?: return null }
            else -> null
        }
}

internal class PdfArray(
    start: Int,
    end: Int,
    val items: List<PdfValue>,
) : PdfValue(start, end)

/** A literal `(...)` or hexadecimal `<...>` string, delimiters included. */
internal class PdfString(
    start: Int,
    end: Int,
    val hex: Boolean,
) : PdfValue(start, end) {
    val isEmpty: Boolean get() = end - start <= 2
}

internal class PdfName(
    start: Int,
    end: Int,
    val name: String,
) : PdfValue(start, end)

internal class PdfNumber(
    start: Int,
    end: Int,
    val text: String,
) : PdfValue(start, end) {
    fun intOrNull(): Int? = text.toIntOrNull()
}

internal class PdfRef(
    start: Int,
    end: Int,
    val number: Int,
    val generation: Int,
) : PdfValue(start, end)

/** `true`, `false`, `null` or another bare word. */
internal class PdfKeyword(
    start: Int,
    end: Int,
    val word: String,
) : PdfValue(start, end)

/** A PDF that cannot be read the way its structure claims. Carries no stack: it is an expected outcome. */
internal class PdfSyntaxException : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

internal fun pdfSyntaxError(): Nothing = throw PdfSyntaxException()
