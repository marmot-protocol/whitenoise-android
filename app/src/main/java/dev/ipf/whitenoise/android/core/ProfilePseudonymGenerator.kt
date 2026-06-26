package dev.ipf.whitenoise.android.core

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Generates random profile pseudonyms in the same two-word style Marmot assigns
 * to new identities at signup.
 *
 * Keep the word lists and SHA-256 seed-to-index derivation in sync with
 * `marmot-app::default_profile_pseudonym` in the Marmot core repository. The
 * edit-profile roll action uses fresh local entropy, so rolls are distribution
 * compatible with signup pseudonyms but not reproducible from an account id.
 */
object ProfilePseudonymGenerator {
    private val adjectives =
        listOf(
            "Agile",
            "Angry",
            "Brave",
            "Bright",
            "Calm",
            "Clever",
            "Cosmic",
            "Daring",
            "Electric",
            "Gentle",
            "Golden",
            "Happy",
            "Hidden",
            "Jolly",
            "Kind",
            "Lucky",
            "Majestic",
            "Mellow",
            "Mighty",
            "Nimble",
            "Noble",
            "Quiet",
            "Rapid",
            "Sage",
            "Silver",
            "Sunny",
            "Swift",
            "Vivid",
            "Witty",
            "Wondrous",
            "Young",
            "Zesty",
        )

    private val nouns =
        listOf(
            "Antelope",
            "Badger",
            "Bear",
            "Beaver",
            "Bison",
            "Bobcat",
            "Cougar",
            "Dolphin",
            "Eagle",
            "Falcon",
            "Finch",
            "Fox",
            "Gecko",
            "Heron",
            "Jaguar",
            "Koala",
            "Llama",
            "Lynx",
            "Moose",
            "Narwhal",
            "Otter",
            "Owl",
            "Panda",
            "Puffin",
            "Raven",
            "Robin",
            "Seal",
            "Swan",
            "Tiger",
            "Turtle",
            "Wolf",
            "Yak",
        )

    private val secureRandom = SecureRandom()

    private const val ENTROPY_BYTE_COUNT = 32
    private const val RANDOM_ROLL_RETRY_COUNT = 8

    internal val adjectiveCount: Int
        get() = adjectives.size

    internal val nounCount: Int
        get() = nouns.size

    fun random(excluding: String? = null): String =
        random(excluding = excluding) {
            ByteArray(ENTROPY_BYTE_COUNT).also(secureRandom::nextBytes)
        }

    internal fun random(
        excluding: String? = null,
        entropyProvider: () -> ByteArray,
    ): String {
        val excluded = excluding?.trim()?.takeIf { it.isNotEmpty() }
        repeat(RANDOM_ROLL_RETRY_COUNT) {
            val candidate = fromEntropy(entropyProvider())
            if (candidate != excluded) return candidate
        }
        return nextAfter(excluded)
    }

    internal fun wordListFingerprint(): String {
        val payload =
            buildString {
                appendLine("adjectives")
                adjectives.forEach { appendLine(it) }
                appendLine("nouns")
                nouns.forEach { appendLine(it) }
            }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .toHexString()
    }

    internal fun fromEntropy(entropy: ByteArray): String = fromSeed(entropy.toHexString())

    internal fun fromSeed(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        val adjectiveIndex = digest.readUnsignedShort(0) % adjectives.size
        val nounIndex = digest.readUnsignedShort(2) % nouns.size
        return "${adjectives[adjectiveIndex]} ${nouns[nounIndex]}"
    }

    private fun nextAfter(excluded: String?): String {
        val all = adjectives.flatMap { adjective -> nouns.map { noun -> "$adjective $noun" } }
        val index = all.indexOf(excluded)
        val nextIndex = if (index == -1) 0 else (index + 1) % all.size
        return all[nextIndex]
    }

    private fun ByteArray.readUnsignedShort(offset: Int): Int {
        val high = this[offset].toInt() and 0xff
        val low = this[offset + 1].toInt() and 0xff
        return (high shl 8) or low
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
}
