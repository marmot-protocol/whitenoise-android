package dev.ipf.darkmatter.state

/**
 * The four kinds of media a bubble can carry. Each call site already knows
 * its own type (image bubble -> [Image], voice bubble -> [Audio], etc.), so
 * the gate is passed the literal type rather than re-deriving it from MIME.
 */
enum class MediaAutoDownloadType(
    val preferenceKey: String,
) {
    Image("image"),
    Audio("audio"),
    Video("video"),
    Document("document"),
    ;

    companion object {
        fun fromKey(key: String?): MediaAutoDownloadType? = entries.firstOrNull { it.preferenceKey == key }
    }
}

/**
 * The four network conditions an auto-download decision is made against. A
 * live connection can match several at once (e.g. cellular that is both
 * roaming and metered); the decision applies the most-restrictive matching
 * rule. See [MediaAutoDownloadMatrix.shouldAutoDownload].
 */
enum class MediaAutoDownloadNetwork(
    val preferenceKey: String,
) {
    WiFi("wifi"),
    Mobile("mobile"),
    Roaming("roaming"),
    Metered("metered"),
    ;

    companion object {
        fun fromKey(key: String?): MediaAutoDownloadNetwork? = entries.firstOrNull { it.preferenceKey == key }
    }
}

/**
 * Pure, immutable representation of the 4x4 per-type, per-network media
 * auto-download matrix (issue #407). Holds only the set of enabled
 * `(type, network)` cells; everything else is derived.
 *
 * No Android APIs are referenced here beyond serializing to/from a plain
 * String, so the decision logic is unit-testable on the JVM. Network
 * *detection* lives in [DarkMatterAppState] (it needs a `Context`); this
 * type just answers "given these active networks, should we auto-download?".
 */
data class MediaAutoDownloadMatrix(
    private val enabled: Set<Pair<MediaAutoDownloadType, MediaAutoDownloadNetwork>>,
) {
    fun isEnabled(
        type: MediaAutoDownloadType,
        network: MediaAutoDownloadNetwork,
    ): Boolean = (type to network) in enabled

    /**
     * Returns a new matrix with the `(type, network)` cell toggled on/off.
     * The receiver is never mutated.
     */
    fun withToggle(
        type: MediaAutoDownloadType,
        network: MediaAutoDownloadNetwork,
        on: Boolean,
    ): MediaAutoDownloadMatrix {
        val cell = type to network
        val next =
            if (on) {
                if (cell in enabled) return this
                enabled + cell
            } else {
                if (cell !in enabled) return this
                enabled - cell
            }
        return MediaAutoDownloadMatrix(next)
    }

    /**
     * Most-restrictive decision: auto-download [type] only when [activeNetworks]
     * is non-empty AND the type is enabled for *every* network the active
     * connection currently matches. If any matching network has the type OFF,
     * the answer is no ("if Roaming is OFF for a type, that wins over Wi-Fi
     * being ON"). An empty set (unknown/offline) is treated conservatively as
     * "do not auto-download".
     */
    fun shouldAutoDownload(
        type: MediaAutoDownloadType,
        activeNetworks: Set<MediaAutoDownloadNetwork>,
    ): Boolean {
        if (activeNetworks.isEmpty()) return false
        return activeNetworks.all { isEnabled(type, it) }
    }

    /**
     * Serializes the enabled cells to a flat, order-stable String for
     * SharedPreferences. Round-trips with [fromPreference]. Cells are encoded
     * as `type:network` pairs joined by `,`.
     */
    fun toPreference(): String =
        MediaAutoDownloadType.entries
            .flatMap { type ->
                MediaAutoDownloadNetwork.entries.mapNotNull { network ->
                    if (isEnabled(type, network)) "${type.preferenceKey}:${network.preferenceKey}" else null
                }
            }.joinToString(",")

    companion object {
        /**
         * Suggested defaults from issue #407:
         *  - Wi-Fi:    Images ON,  Audio ON,  Video ON,  Documents OFF
         *  - Mobile:   Images ON,  Audio ON,  Video OFF, Documents OFF
         *  - Roaming:  Images OFF, Audio OFF, Video OFF, Documents OFF
         *  - Metered:  Images ON,  Audio OFF, Video OFF, Documents OFF
         */
        val DEFAULT: MediaAutoDownloadMatrix =
            MediaAutoDownloadMatrix(
                buildSet {
                    add(MediaAutoDownloadType.Image to MediaAutoDownloadNetwork.WiFi)
                    add(MediaAutoDownloadType.Audio to MediaAutoDownloadNetwork.WiFi)
                    add(MediaAutoDownloadType.Video to MediaAutoDownloadNetwork.WiFi)

                    add(MediaAutoDownloadType.Image to MediaAutoDownloadNetwork.Mobile)
                    add(MediaAutoDownloadType.Audio to MediaAutoDownloadNetwork.Mobile)

                    add(MediaAutoDownloadType.Image to MediaAutoDownloadNetwork.Metered)
                },
            )

        /**
         * Inverse of [toPreference]. Unknown/garbage cells are skipped rather
         * than throwing; a fully unparseable value yields an empty (all-OFF)
         * matrix. Callers that want defaults for a never-seen account should
         * branch on key presence before calling this (see [DarkMatterAppState]).
         */
        fun fromPreference(value: String?): MediaAutoDownloadMatrix {
            if (value.isNullOrBlank()) return MediaAutoDownloadMatrix(emptySet())
            val cells =
                value
                    .split(",")
                    .mapNotNull { token ->
                        val parts = token.trim().split(":")
                        if (parts.size != 2) return@mapNotNull null
                        val type = MediaAutoDownloadType.fromKey(parts[0]) ?: return@mapNotNull null
                        val network = MediaAutoDownloadNetwork.fromKey(parts[1]) ?: return@mapNotNull null
                        type to network
                    }.toSet()
            return MediaAutoDownloadMatrix(cells)
        }
    }
}
