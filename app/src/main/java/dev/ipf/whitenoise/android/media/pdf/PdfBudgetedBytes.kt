package dev.ipf.whitenoise.android.media.pdf

// Reads allowed per input byte while walking; honest files need about two, hostile ones are cut off early.
private const val READS_PER_BYTE = 16L
private const val BASE_READS = 1L shl 20
private const val MAX_READS = 384L * 1024 * 1024

/** Parsing ran past its work budget: the input is hostile or far outside what this app reads. */
internal class PdfLimitException : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

/** [delegate] with a bound on how many bytes may be read, so malformed input cannot make the walk quadratic. */
internal class BudgetedPdfBytes(
    private val delegate: PdfBytes,
) : PdfBytes by delegate {
    private var remaining = minOf(MAX_READS, BASE_READS + READS_PER_BYTE * delegate.size)

    override fun get(index: Int): Int {
        if (--remaining < 0) throw PdfLimitException()
        return delegate[index]
    }

    override fun copy(
        from: Int,
        until: Int,
    ): ByteArray {
        remaining -= (until - from)
        if (remaining < 0) throw PdfLimitException()
        return delegate.copy(from, until)
    }
}
