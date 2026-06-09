package dev.ipf.darkmatter.state

/**
 * When incoming image attachments are fetched/decrypted automatically.
 * [WifiOnly] is the default: it keeps metered-data usage down and avoids
 * leaking "online + viewing" timing to the Blossom server on cellular, while
 * still feeling automatic on Wi-Fi.
 */
enum class MediaAutoDownloadPolicy(
    val preferenceValue: String,
) {
    Always("always"),
    WifiOnly("wifi"),
    Never("never"),
    ;

    companion object {
        fun fromPreference(value: String?): MediaAutoDownloadPolicy = entries.firstOrNull { it.preferenceValue == value } ?: WifiOnly
    }
}
