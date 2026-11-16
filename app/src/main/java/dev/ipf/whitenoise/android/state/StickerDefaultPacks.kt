package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.whitenoise.android.core.MarmotClient
import java.net.URLEncoder

/**
 * White Noise's own sticker packs, which every account starts with installed so the picker has stickers without a
 * trip to the pack browser. The White Noise account that publishes the `white-noise` emoji set also publishes these.
 */
internal object StickerDefaultPacks {
    const val AUTHOR_PUBKEY_HEX = "75d737c3472471029c44876b330d2284288a42779b591a2ed4daa1c6c07efaf7"
    private const val SONAR_LINK_BASE = "https://sonarprivacy.xyz/stickers"

    val coordinates: List<String> =
        listOf("30031:$AUTHOR_PUBKEY_HEX:white-noise-originals", "30031:$AUTHOR_PUBKEY_HEX:marmot")

    /**
     * The packs live on White Noise's relays, which an imported account's own relay list may not include. MDK fetches
     * an install from the account's relays plus the input's hints, so each default install is a Sonar link naming them.
     */
    fun installInput(coordinate: String): String {
        val parameters = listOf("a" to coordinate) + MarmotClient.bootstrapRelays.map { "relay" to it }
        return parameters.joinToString("&", prefix = "$SONAR_LINK_BASE?") { (name, value) ->
            "$name=${URLEncoder.encode(value, "UTF-8")}"
        }
    }

    /** Default packs an account still needs, in order; one it has had installed is never offered again. */
    fun toInstall(
        installed: Set<String>,
        offered: Set<String>,
    ): List<String> = coordinates.filter { it !in installed && it !in offered }
}

/**
 * Which default packs one account has already had installed on this device. MDK's installed list can't tell "never
 * had it" from "removed it", so this is what keeps a removed default pack from coming back.
 */
class StickerDefaultPackLedger(
    private val read: () -> Set<String>,
    private val write: (Set<String>) -> Unit,
) {
    /** The default packs this account has had installed, including ones it removed since. */
    fun offered(): Set<String> = read()

    /** Adds [coordinates] to what this account has had, so they are never installed for it again. */
    fun record(coordinates: Collection<String>) {
        val current = read()
        val merged = current + coordinates
        if (merged != current) write(merged)
    }

    companion object {
        private const val PREFERENCES = "sticker_default_packs"

        /** The ledger for [accountRef], kept in app-private preferences. */
        fun forAccount(
            context: Context,
            accountRef: String,
        ): StickerDefaultPackLedger {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val key = "offered.$accountRef"
            return StickerDefaultPackLedger(
                read = { preferences.getStringSet(key, null).orEmpty().toSet() },
                write = { preferences.edit().putStringSet(key, it).apply() },
            )
        }
    }
}
