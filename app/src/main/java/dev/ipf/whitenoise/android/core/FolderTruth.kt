package dev.ipf.whitenoise.android.core

/** Unknown evidence survives negation; it never grants automatic membership. */
internal enum class FolderTruth {
    TRUE,
    FALSE,
    UNKNOWN,
    ;

    fun inverted(): FolderTruth =
        when (this) {
            TRUE -> FALSE
            FALSE -> TRUE
            UNKNOWN -> UNKNOWN
        }
}
