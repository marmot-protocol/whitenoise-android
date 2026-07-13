package dev.ipf.whitenoise.android.updates

/** Constant-time byte-array equality for digest comparisons. */
internal fun constantTimeEquals(
    left: ByteArray,
    right: ByteArray,
): Boolean {
    if (left.size != right.size) return false
    var mismatch = 0
    for (index in left.indices) {
        mismatch = mismatch or (left[index].toInt() xor right[index].toInt())
    }
    return mismatch == 0
}

/** Constant-time SHA-256 digest comparison against a trusted lowercase hex string. */
internal fun constantTimeEqualsHex(
    computed: ByteArray,
    expectedHex: String,
): Boolean {
    val expected = expectedHex.lowercase().hexToBytes() ?: return false
    return constantTimeEquals(computed, expected)
}
