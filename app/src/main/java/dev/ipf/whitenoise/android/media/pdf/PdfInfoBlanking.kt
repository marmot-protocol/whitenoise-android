package dev.ipf.whitenoise.android.media.pdf

private val EMPTY_LITERAL = ascii("()")
private val EMPTY_HEX = ascii("<>")

/** The metadata kind of one document-information dictionary [key]. */
internal fun infoField(key: String): PdfMetadataField =
    when (key) {
        "Author" -> PdfMetadataField.Author
        "Title", "Subject", "Keywords" -> PdfMetadataField.Description
        "Creator", "Producer" -> PdfMetadataField.Software
        "CreationDate", "ModDate" -> PdfMetadataField.Dates
        else -> PdfMetadataField.Other
    }

/**
 * Empties every string in a document-information dictionary [dict], however deeply nested, by overwriting it with
 * `()` or `<>` and spaces. Keys, names such as `/Trapped` and the dictionary's length stay. [edit] receives each
 * overwrite; each indirect reference is added to [references], once per use, for the caller to empty.
 */
internal fun blankInfoStrings(
    dict: PdfDict,
    fields: MutableSet<PdfMetadataField>,
    references: MutableList<PdfRef>,
    edit: (Int, ByteArray) -> Unit,
) {
    dict.entries.forEach { (key, value) -> blankValue(value, infoField(key), fields, references, edit) }
}

private fun blankValue(
    value: PdfValue,
    field: PdfMetadataField,
    fields: MutableSet<PdfMetadataField>,
    references: MutableList<PdfRef>,
    edit: (Int, ByteArray) -> Unit,
) {
    when (value) {
        is PdfString ->
            if (!value.isEmpty) {
                fields += field
                edit(value.start, blankString(value))
            }
        is PdfRef -> references += value
        is PdfArray -> value.items.forEach { blankValue(it, field, fields, references, edit) }
        is PdfDict -> value.entries.forEach { (_, nested) -> blankValue(nested, field, fields, references, edit) }
        else -> Unit
    }
}

/** An empty string of the same kind as [value], padded with spaces to its length. */
internal fun blankString(value: PdfString): ByteArray {
    val empty = if (value.hex) EMPTY_HEX else EMPTY_LITERAL
    return empty + ByteArray(value.end - value.start - empty.size) { ' '.code.toByte() }
}
