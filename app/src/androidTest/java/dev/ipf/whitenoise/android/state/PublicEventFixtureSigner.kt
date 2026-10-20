package dev.ipf.whitenoise.android.state

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Test-only BIP-340 signer for public Nostr fixture events, so a device test can seed relays with genuinely signed
 * notes, articles, profiles and deletions by an author that is not a local account. It favors clarity over speed
 * and must never sign anything outside a disposable fixture.
 */
internal class PublicEventFixtureSigner(
    privateKeyHex: String,
) {
    private val secret = BigInteger(privateKeyHex, HEX)
    private val publicPoint = Secp256k1.multiply(secret)

    /** The x-only public key as lowercase hex. */
    val pubkeyHex: String = publicPoint.x.toFixedHex()

    /** A signed event of [kind] with [tags] and [content] created at [createdAt]. */
    fun sign(
        kind: Int,
        content: String,
        createdAt: Long,
        tags: List<List<String>> = emptyList(),
    ): JSONObject {
        val id = sha256(canonicalEvent(kind, content, createdAt, tags).toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("id", id.toHex())
            .put("pubkey", pubkeyHex)
            .put("created_at", createdAt)
            .put("kind", kind)
            .put("tags", JSONArray(tags.map(::JSONArray)))
            .put("content", content)
            .put("sig", schnorr(id).toHex())
    }

    /** NIP-01's id preimage, serialized without the `\/` escaping a JSON library would add. */
    private fun canonicalEvent(
        kind: Int,
        content: String,
        createdAt: Long,
        tags: List<List<String>>,
    ): String {
        val serializedTags = tags.joinToString(",", "[", "]") { tag -> tag.joinToString(",", "[", "]", transform = ::quote) }
        return "[0,${quote(pubkeyHex)},$createdAt,$kind,$serializedTags,${quote(content)}]"
    }

    /** A BIP-340 signature of [message] with an all-zero auxiliary random input, which the scheme permits. */
    private fun schnorr(message: ByteArray): ByteArray {
        val d = if (publicPoint.y.testBit(0)) Secp256k1.N - secret else secret
        val pubkey = publicPoint.x.toFixed32()
        val masked = d.toFixed32().zip(taggedHash("BIP0340/aux", ByteArray(SIZE))) { a, b -> (a.toInt() xor b.toInt()).toByte() }
        val nonceSeed = BigInteger(1, taggedHash("BIP0340/nonce", masked.toByteArray() + pubkey + message)).mod(Secp256k1.N)
        val noncePoint = Secp256k1.multiply(nonceSeed)
        val nonce = if (noncePoint.y.testBit(0)) Secp256k1.N - nonceSeed else nonceSeed
        val rx = noncePoint.x.toFixed32()
        val challenge = BigInteger(1, taggedHash("BIP0340/challenge", rx + pubkey + message)).mod(Secp256k1.N)
        return rx + nonce.add(challenge.multiply(d)).mod(Secp256k1.N).toFixed32()
    }

    private companion object {
        const val HEX = 16
        const val SIZE = 32

        /** The BIP-340 tagged hash of [data] under [tag]. */
        fun taggedHash(
            tag: String,
            data: ByteArray,
        ): ByteArray {
            val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
            return sha256(tagHash + tagHash + data)
        }

        /** SHA-256 of [data]. */
        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        /** A JSON string literal with NIP-01's escapes. */
        fun quote(value: String): String =
            buildString {
                append('"')
                value.forEach { char ->
                    when (char) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(char)
                    }
                }
                append('"')
            }

        /** Lowercase hex of these bytes. */
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /** This non-negative integer as exactly 32 big-endian bytes. */
        fun BigInteger.toFixed32(): ByteArray {
            val raw = toByteArray()
            return when {
                raw.size == SIZE -> raw
                raw.size > SIZE -> raw.copyOfRange(raw.size - SIZE, raw.size)
                else -> ByteArray(SIZE - raw.size) + raw
            }
        }

        /** This non-negative integer as 64 lowercase hex characters. */
        fun BigInteger.toFixedHex(): String = toFixed32().toHex()
    }
}

/** Affine secp256k1 arithmetic, enough for fixture key derivation and signing. */
private object Secp256k1 {
    val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G =
        Point(
            BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
            BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
        )

    /** A point on the curve; null stands for the point at infinity. */
    data class Point(
        val x: BigInteger,
        val y: BigInteger,
    )

    /** [scalar] times the generator, by double-and-add. */
    fun multiply(scalar: BigInteger): Point {
        var result: Point? = null
        var addend: Point? = G
        for (bit in 0 until scalar.bitLength()) {
            if (scalar.testBit(bit)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return checkNotNull(result) { "zero scalar" }
    }

    /** The sum of [a] and [b] under the group law. */
    private fun add(
        a: Point?,
        b: Point?,
    ): Point? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x && a.y != b.y) return null
        val slope =
            if (a == b) {
                BigInteger.valueOf(3).multiply(a.x.pow(2)).multiply(BigInteger.valueOf(2).multiply(a.y).modInverse(P))
            } else {
                b.y.subtract(a.y).multiply(b.x.subtract(a.x).modInverse(P))
            }.mod(P)
        val x =
            slope
                .pow(2)
                .subtract(a.x)
                .subtract(b.x)
                .mod(P)
        val y = slope.multiply(a.x.subtract(x)).subtract(a.y).mod(P)
        return Point(x, y)
    }
}

/** NIP-19 Bech32 encoding of public event pointers for fixtures. */
internal object PublicEventFixtureBech32 {
    /** A `note` for [eventIdHex]. */
    fun note(eventIdHex: String): String = encode("note", eventIdHex.hexBytes())

    /** An `naddr` for the addressable coordinate, optionally with a relay hint. */
    fun naddr(
        kind: Int,
        authorHex: String,
        identifier: String,
        relayHint: String? = null,
    ): String {
        val tlv = ByteArrayOutputStream()
        tlv.writeTlv(0, identifier.toByteArray(Charsets.UTF_8))
        relayHint?.let { tlv.writeTlv(1, it.toByteArray(Charsets.UTF_8)) }
        tlv.writeTlv(2, authorHex.hexBytes())
        tlv.writeTlv(3, byteArrayOf((kind ushr 24).toByte(), (kind ushr 16).toByte(), (kind ushr 8).toByte(), kind.toByte()))
        return encode("naddr", tlv.toByteArray())
    }

    /** Appends one type-length-value entry. */
    private fun ByteArrayOutputStream.writeTlv(
        type: Int,
        value: ByteArray,
    ) {
        write(type)
        write(value.size)
        write(value)
    }

    /** Bech32 (not Bech32m) encoding of [data] under [hrp]. */
    private fun encode(
        hrp: String,
        data: ByteArray,
    ): String {
        val words = toWords(data)
        val values = hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 } + words + List(6) { 0 }
        val polymod = polymod(values) xor 1
        val checksum = List(6) { (polymod shr (5 * (5 - it))) and 31 }
        return hrp + "1" + (words + checksum).joinToString("") { CHARSET[it].toString() }
    }

    /** Regroups bytes into padded 5-bit words. */
    private fun toWords(data: ByteArray): List<Int> {
        var accumulator = 0
        var bits = 0
        val words = mutableListOf<Int>()
        data.forEach { byte ->
            accumulator = (accumulator shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                words += (accumulator shr bits) and 31
            }
        }
        if (bits > 0) words += (accumulator shl (5 - bits)) and 31
        return words
    }

    /** The Bech32 BCH remainder over [values]. */
    private fun polymod(values: List<Int>): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var checksum = 1
        values.forEach { value ->
            val top = checksum shr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            generators.forEachIndexed { index, generator -> if ((top shr index) and 1 == 1) checksum = checksum xor generator }
        }
        return checksum
    }

    /** Bytes of an even-length hex string. */
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
}
