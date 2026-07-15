package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerRefFfi

fun StickerFfi.reference(): StickerRefFfi =
    StickerRefFfi(
        packCoordinate = packCoordinate,
        shortcode = shortcode,
        plaintextSha256 = sha256,
    )

fun StickerRefFfi.cacheIdentity(): String = "$packCoordinate:$shortcode:${plaintextSha256.lowercase()}"

fun StickerRefFfi.messageTag(): List<String> = listOf("sticker", packCoordinate, shortcode, plaintextSha256)
