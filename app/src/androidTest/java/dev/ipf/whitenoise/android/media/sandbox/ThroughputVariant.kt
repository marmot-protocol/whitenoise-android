package dev.ipf.whitenoise.android.media.sandbox

/**
 * The transport variants the throughput harness compares, the one list of their names. [key] is the
 * `throughput_variants` argument and the stable report key, so every name the harness accepts, defaults to or
 * reports comes from here.
 */
internal enum class ThroughputVariant(
    val key: String,
) {
    /** The pre-#3373 lane: one reusable worker across inputs, whatever their domain. */
    REUSE("reuse"),

    /** The per-input lane with two spares: every request single-use, as before domains. */
    SINGLE_USE("single_use"),

    /** Domain-bound generations with the production spare depth and live limit. */
    DOMAIN("domain"),
    ;

    companion object {
        /** The ABBA order run when no variants are requested: the reusable baseline around the production lane. */
        val DEFAULT_ORDER: List<ThroughputVariant> = listOf(REUSE, DOMAIN, DOMAIN, REUSE)

        /**
         * The variants a comma-separated [argument] names, in order, or [DEFAULT_ORDER] when it names none. Every
         * name is checked before any pass runs, so a misspelt lane fails at once rather than after earlier passes.
         */
        fun parse(argument: String?): List<ThroughputVariant> {
            val names =
                argument
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    .orEmpty()
            if (names.isEmpty()) return DEFAULT_ORDER
            val unknown = names.filter { name -> entries.none { it.key == name } }
            require(unknown.isEmpty()) {
                "Unknown throughput variants $unknown, expected ${entries.joinToString { it.key }}"
            }
            return names.map { name -> entries.first { it.key == name } }
        }
    }
}
