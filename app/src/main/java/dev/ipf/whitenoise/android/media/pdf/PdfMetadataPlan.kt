package dev.ipf.whitenoise.android.media.pdf

private const val MAX_PDF_EDIT_BYTES = 32 * 1024 * 1024

/** Kinds of identifying metadata a PDF can carry, as people would describe them. */
internal enum class PdfMetadataField {
    /** `/Author`. */
    Author,

    /** `/Title`, `/Subject` and `/Keywords`. */
    Description,

    /** `/Creator` and `/Producer`: the apps that made the file. */
    Software,

    /** `/CreationDate` and `/ModDate`. */
    Dates,

    /** Any other document-information entry, such as a company or a custom property. */
    Other,

    /** An XMP metadata packet, which repeats the above and can hold history, IDs and more. */
    Xmp,

    /** Camera, location and editing records inside an embedded JPEG photo. */
    PhotoData,
}

/** Why a PDF keeps its metadata: changing it is unsafe or beyond what this app can rewrite exactly. */
internal enum class PdfMetadataRefusal {
    /** Digitally signed or certified: any change invalidates the signature. */
    Signed,

    /** Encrypted or password protected: its metadata cannot be read or changed without the key. */
    Encrypted,

    /** Malformed, or metadata stored in a way this app cannot rewrite without changing the file's layout. */
    Unsupported,
}

/** One same-length overwrite. */
internal class PdfEdit(
    val position: Int,
    val bytes: ByteArray,
)

/** The edits that remove one PDF's metadata, what they remove, or why it must stay. */
internal class PdfMetadataPlan {
    private var editBytes = 0
    val edits = mutableListOf<PdfEdit>()
    val fields = mutableSetOf<PdfMetadataField>()
    var refusal: PdfMetadataRefusal? = null
        private set

    fun refuse(reason: PdfMetadataRefusal) {
        if (refusal == null) refusal = reason
    }

    fun replace(
        position: Int,
        bytes: ByteArray,
    ) {
        if (bytes.size > MAX_PDF_EDIT_BYTES - editBytes) throw PdfLimitException()
        editBytes += bytes.size
        edits += PdfEdit(position, bytes)
    }

    /** Zeroes [length] bytes at [position] unless they already are zero, recording embedded photo data. */
    fun zero(
        source: PdfBytes,
        position: Int,
        length: Int,
    ) {
        if ((position until position + length).all { source[it] == 0 }) return
        fields += PdfMetadataField.PhotoData
        replace(position, ByteArray(length))
    }
}

/** What reading or removing one file's PDF metadata found. */
internal sealed interface PdfMetadataResult {
    /** Not a PDF: nothing here applies. */
    data object NotPdf : PdfMetadataResult

    /** A PDF with no metadata left to remove. */
    data object Clean : PdfMetadataResult

    /** A PDF whose [fields] can be removed. Only an inspection reports this. */
    data class Removable(
        val fields: Set<PdfMetadataField>,
    ) : PdfMetadataResult

    /** A PDF whose [fields] were removed in place. */
    data class Scrubbed(
        val fields: Set<PdfMetadataField>,
    ) : PdfMetadataResult

    /** A PDF whose metadata stays, for [reason]. Its bytes were not changed. */
    data class Refused(
        val reason: PdfMetadataRefusal,
    ) : PdfMetadataResult
}
