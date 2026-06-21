package dev.ipf.darkmatter.core

object ClipboardPasteAffordance {
    const val TEXT_MIME_TYPE_PATTERN = "text/*"

    private const val TEXT_MIME_TYPE_PREFIX = "text/"

    fun canOfferPaste(mimeTypes: Iterable<String>): Boolean =
        mimeTypes.any { mimeType ->
            mimeType.startsWith(TEXT_MIME_TYPE_PREFIX, ignoreCase = true)
        }

    fun pasteValue(
        rawClipboardText: String?,
        allowHexPublicKey: Boolean = true,
    ): String? = RecipientReference.plausibleClipboardInput(rawClipboardText, allowHexPublicKey)
}
