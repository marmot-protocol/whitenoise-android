package dev.ipf.whitenoise.android.fuzz

object FuzzBounds {
    const val MAX_STRING_BYTES = 64 * 1024
    const val MAX_COLLECTION_ELEMENTS = 64
    const val MAX_DEPTH = 16
    const val MAX_FRAMES = 32
}

object FuzzGrammar {
    val relayFrameNames = listOf("EVENT", "EOSE", "CLOSED", "NOTICE", "REQ", "OK", "ERROR")
    val uriSchemes = listOf("marmot", "nostr", "whitenoise", "whitenoise-dev", "whitenoise-staging", "http", "https")
    val profileHosts = listOf("whitenoise.chat", "www.whitenoise.chat", "marmot.app", "www.marmot.app")
    val nip55Ops = listOf("get_public_key", "sign_event", "nip04_encrypt", "nip04_decrypt", "nip44_encrypt", "nip44_decrypt")
    val hexChars = "0123456789abcdefABCDEF"
    val bech32BodyChars = "acdefghjklmnpqrstuvwxyz023456789"
}
