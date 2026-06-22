package dev.ipf.darkmatter.updates

import java.math.BigInteger
import java.util.Locale

/** Minimal BIP-340 Schnorr verifier for Nostr event signatures on secp256k1. */
internal object BIP340 {
    private val zero = BigInteger.ZERO
    private val one = BigInteger.ONE
    private val two = BigInteger.TWO
    private val three = BigInteger.valueOf(3)
    private val seven = BigInteger.valueOf(7)
    private val p = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val n = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val g = Point(gx, gy)

    fun verify(
        publicKeyHex: String,
        messageHex: String,
        signatureHex: String,
    ): Boolean {
        val publicKey = publicKeyHex.lowercase(Locale.US).hexToBytes() ?: return false
        val message = messageHex.lowercase(Locale.US).hexToBytes() ?: return false
        val signature = signatureHex.lowercase(Locale.US).hexToBytes() ?: return false
        if (publicKey.size != 32 || message.size != 32 || signature.size != 64) return false

        val pubkeyPoint = liftX(unsigned(publicKey)) ?: return false
        val r = unsigned(signature.copyOfRange(0, 32))
        val s = unsigned(signature.copyOfRange(32, 64))
        if (r >= p || s >= n) return false

        val challenge = taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32) + publicKey + message)
        val e = unsigned(challenge).mod(n)
        val rPoint = add(multiply(s, g), multiply(n.subtract(e), pubkeyPoint)) ?: return false
        return !rPoint.y.testBit(0) && rPoint.x == r
    }

    private fun liftX(x: BigInteger): Point? {
        if (x >= p) return null
        val c = mod(x.modPow(three, p).add(seven))
        val y = c.modPow(p.add(one).divide(BigInteger.valueOf(4)), p)
        if (mod(y.multiply(y).subtract(c)) != zero) return null
        return Point(x, if (y.testBit(0)) p.subtract(y) else y)
    }

    private fun taggedHash(
        tag: String,
        message: ByteArray,
    ): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(tagHash + tagHash + message)
    }

    private fun multiply(
        scalar: BigInteger,
        point: Point,
    ): Point? {
        var result: Point? = null
        var addend: Point? = point
        var k = scalar.mod(n)
        while (k > zero && addend != null) {
            if (k.testBit(0)) result = add(result, addend)
            addend = double(addend)
            k = k.shiftRight(1)
        }
        return result
    }

    private fun add(
        left: Point?,
        right: Point?,
    ): Point? {
        if (left == null) return right
        if (right == null) return left
        if (left.x == right.x) {
            if (mod(left.y.add(right.y)) == zero) return null
            return double(left)
        }
        val lambda = mod(right.y.subtract(left.y).multiply(right.x.subtract(left.x).modInverse(p)))
        val x = mod(lambda.multiply(lambda).subtract(left.x).subtract(right.x))
        val y = mod(lambda.multiply(left.x.subtract(x)).subtract(left.y))
        return Point(x, y)
    }

    private fun double(point: Point): Point? {
        if (point.y == zero) return null
        val lambda = mod(three.multiply(point.x).multiply(point.x).multiply(two.multiply(point.y).modInverse(p)))
        val x = mod(lambda.multiply(lambda).subtract(two.multiply(point.x)))
        val y = mod(lambda.multiply(point.x.subtract(x)).subtract(point.y))
        return Point(x, y)
    }

    private fun mod(value: BigInteger): BigInteger = value.mod(p)

    private fun unsigned(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    private data class Point(
        val x: BigInteger,
        val y: BigInteger,
    )
}
