package dev.ipf.whitenoise.android.notifications

/**
 * Deterministic message bodies of an exact Unicode code point length, for threshold and bound tests.
 *
 * Every body is built from a fixed repeating unit, so a failure reproduces byte for byte. Each ends in a
 * letter, never whitespace, so a formatter that trims cannot change the measured length. Lengths count code
 * points, matching the presenter's rule, and a count that lands inside a multi-code-point unit cuts that unit
 * short on purpose: the zero-width-joiner variant exists to prove the bound never miscounts one.
 */
object LongBodies {
    /** One code point under the original 160 code point expanded-text threshold. */
    const val BELOW_LEGACY_THRESHOLD = 159

    /** Exactly the original 160 code point expanded-text threshold. */
    const val AT_LEGACY_THRESHOLD = 160

    /** One code point over the original 160 code point expanded-text threshold. */
    const val ABOVE_LEGACY_THRESHOLD = 161

    /** The presenter's per-message safety bound, which no card may exceed. */
    const val SAFETY_BOUND = 1_000

    private const val PLAIN_UNIT = "Notification body sample text "
    private const val RTL_UNIT = "مرحبا بالعالم "
    private const val ZWJ_UNIT = "x👩‍💻 "
    private const val PLAIN_LAST = 'z'
    private const val RTL_LAST = 'م'

    /** A left-to-right body of exactly [codePoints] code points built from words and single spaces. */
    fun plain(codePoints: Int): String = repeated(PLAIN_UNIT, codePoints, PLAIN_LAST)

    /** A right-to-left body of exactly [codePoints] code points built from Arabic words and single spaces. */
    fun rtl(codePoints: Int): String = repeated(RTL_UNIT, codePoints, RTL_LAST)

    /** A body of exactly [codePoints] code points made of emoji joined by zero-width joiners and spaces. */
    fun zwj(codePoints: Int): String = repeated(ZWJ_UNIT, codePoints, PLAIN_LAST)

    /** The number of code points in [body], the unit every threshold in the presenter uses. */
    fun codePoints(body: CharSequence): Int = body.toString().let { it.codePointCount(0, it.length) }

    /** The body with [LongBodies.AT_LEGACY_THRESHOLD] code points. */
    fun atLegacyThreshold(): String = plain(AT_LEGACY_THRESHOLD)

    /** The body with [LongBodies.SAFETY_BOUND] code points. */
    fun atSafetyBound(): String = plain(SAFETY_BOUND)

    /** A body one code point past [LongBodies.SAFETY_BOUND], which the presenter must cut back. */
    fun pastSafetyBound(): String = plain(SAFETY_BOUND + 1)

    /** Repeats [unit] to exactly [codePoints] code points, forcing the final one to [last]. */
    private fun repeated(
        unit: String,
        codePoints: Int,
        last: Char,
    ): String {
        require(codePoints > 0) { "A body needs at least one code point" }
        val unitCodePoints = unit.codePoints().toArray()
        val body = StringBuilder()
        for (index in 0 until codePoints - 1) body.appendCodePoint(unitCodePoints[index % unitCodePoints.size])
        return body.append(last).toString()
    }
}
