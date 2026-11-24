package dev.ipf.whitenoise.android.media.pdf

private const val SLASH = '/'.code
private const val NAME_ESCAPE = '#'.code
private const val ESCAPE_WIDTH = 3
private const val HEX_RADIX = 16

/**
 * Whether the name `/[name]` appears anywhere in [bytes], however its characters are escaped with `#xx`, inside an
 * object that parses or one that does not. It reads syntax and binary data alike; a false match only refuses, which
 * leaves the file unchanged.
 */
internal fun containsName(
    bytes: PdfBytes,
    name: String,
): Boolean {
    val target = ascii(name)
    var position = 0
    var found = false
    while (!found && position < bytes.size) {
        found = bytes[position] == SLASH && nameAt(bytes, position + 1, target)
        position++
    }
    return found
}

/** Whether the name whose characters start at [from] decodes to exactly [target]. */
private fun nameAt(
    bytes: PdfBytes,
    from: Int,
    target: ByteArray,
): Boolean {
    var position = from
    var matched = 0
    var matches = true
    while (matches && position < bytes.size && isPdfRegular(bytes[position])) {
        val escaped = escapedAt(bytes, position)
        val byte = escaped ?: bytes[position]
        matches = matched < target.size && target[matched].toInt() == byte
        matched++
        position += if (escaped != null) ESCAPE_WIDTH else 1
    }
    return matches && matched == target.size
}

/** The byte a `#xx` escape at [position] stands for, or null when there is none. */
private fun escapedAt(
    bytes: PdfBytes,
    position: Int,
): Int? {
    if (bytes[position] != NAME_ESCAPE || position + 2 >= bytes.size) return null
    val high = Character.digit(bytes[position + 1], HEX_RADIX)
    val low = Character.digit(bytes[position + 2], HEX_RADIX)
    return if (high < 0 || low < 0) null else high * HEX_RADIX + low
}
