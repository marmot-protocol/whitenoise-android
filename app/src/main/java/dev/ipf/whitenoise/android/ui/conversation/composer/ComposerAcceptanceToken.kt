package dev.ipf.whitenoise.android.ui.conversation.composer

/** Opaque content generation and source-state identity captured before an asynchronous send. */
internal class ComposerAcceptanceToken(
    val text: String,
    private val source: ComposerTextState,
    private val contentRevision: Long,
) {
    /** Rejects callbacks transported into a replacement state even when text and revision alias. */
    fun isCurrentFor(
        state: ComposerTextState,
        text: String,
        contentRevision: Long,
    ): Boolean = source === state && this.text == text && this.contentRevision == contentRevision
}
