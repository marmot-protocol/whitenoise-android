package dev.ipf.darkmatter.updates

/** Maps the device's primary ABI to a Zapstore/NIP-82 platform identifier. */
object AndroidAbi {
    const val APK_MIME = "application/vnd.android.package-archive"

    fun platformIdForPrimaryAbi(primaryAbi: String): String = "android-$primaryAbi"

    fun isSupportedPrimaryAbi(primaryAbi: String): Boolean = primaryAbi in SUPPORTED_PRIMARY_ABIS

    private val SUPPORTED_PRIMARY_ABIS =
        setOf(
            "arm64-v8a",
            "armeabi-v7a",
            "x86",
            "x86_64",
        )
}
