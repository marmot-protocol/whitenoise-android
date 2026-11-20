package dev.ipf.whitenoise.android.state

/** Recognizes the native eviction error until MDK exposes a typed use-after-eviction failure. */
internal fun Throwable.isUseAfterEviction(): Boolean {
    // Stopgap until Marmot exposes a typed UniFFI error/code for
    // GroupStateError::UseAfterEviction. Keep this in sync with the Rust
    // OpenMLS group-state error variant name.
    val text =
        generateSequence(this) { it.cause }
            .joinToString(separator = "\n") { error ->
                listOfNotNull(error.message, error.javaClass.simpleName).joinToString(" ")
            }
    return "UseAfterEviction" in text || ("GroupStateError" in text && "eviction" in text.lowercase())
}
