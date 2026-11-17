package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.MarmotClient
import java.net.URLEncoder

/**
 * White Noise's own sticker packs, which the pack browser offers in a section of their own. The White Noise account
 * that publishes the `white-noise` emoji set also publishes these. Nothing installs until the person taps Add.
 */
internal object WhiteNoiseStickerPacks {
    const val AUTHOR_PUBKEY_HEX = "75d737c3472471029c44876b330d2284288a42779b591a2ed4daa1c6c07efaf7"

    val coordinates: List<String> =
        listOf("30031:$AUTHOR_PUBKEY_HEX:white-noise-originals", "30031:$AUTHOR_PUBKEY_HEX:marmot")

    /**
     * The packs live on White Noise's relays, which an imported account's own relay list may not include. MDK fetches
     * a pack from the account's relays plus the input's hints, so each lookup is a Sonar link naming those relays.
     */
    fun link(coordinate: String): String = sonarStickerLink(coordinate, MarmotClient.bootstrapRelays)
}

/**
 * A Sonar viewer link to the pack at [coordinate], naming [relays] as hints. It opens in a browser, and MDK reads it
 * back, hints included, when it is pasted into "Add by link" or used to look a pack up.
 */
internal fun sonarStickerLink(
    coordinate: String,
    relays: List<String> = emptyList(),
): String {
    val parameters = listOf("a" to coordinate) + relays.map { "relay" to it }
    return parameters.joinToString("&", prefix = "$SONAR_STICKER_LINK_BASE?") { (name, value) ->
        "$name=${URLEncoder.encode(value, "UTF-8")}"
    }
}

private const val SONAR_STICKER_LINK_BASE = "https://sonarprivacy.xyz/stickers"
