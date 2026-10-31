package dev.ipf.whitenoise.android.media

import org.xmlpull.v1.XmlPullParser
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A conservative viewer-only affine subset and ancestor budget, before AndroidSVG composes Float matrices. */
internal class ViewerSvgTransformPolicy {
    private val ancestors = ArrayDeque<Affine>()

    fun validate(parser: XmlPullParser) {
        while (ancestors.size >= parser.depth) ancestors.removeLast()
        val parent = ancestors.lastOrNull() ?: Affine()
        val local = parse(parser.getAttributeValue(null, "transform"))
        ancestors.addLast(parent.then(local))
        // Gradients cannot reference/inherit another gradient in the admitted subset.
        parse(parser.getAttributeValue(null, "gradientTransform"))
    }

    private fun parse(value: String?): Affine {
        if (value == null) return Affine()
        var result = Affine()
        var end = 0
        for (match in TRANSFORM.findAll(value)) {
            require(value.substring(end, match.range.first).all { it.isWhitespace() || it == ',' })
            val numbers = match.groupValues[2].trim().split(Regex("[\\s,]+"))
            require(numbers.size in 1..MATRIX_COMPONENT_COUNT)
            val values =
                numbers.map {
                    requireNotNull(it.toDoubleOrNull()).also { n ->
                        require(n.isFinite() && abs(n) <= MAX_LOCAL_COMPONENT)
                    }
                }
            result = result.then(operation(match.groupValues[1], values))
            end = match.range.last + 1
        }
        require(end > 0 && value.substring(end).all { it.isWhitespace() || it == ',' })
        return result
    }

    private fun operation(
        name: String,
        values: List<Double>,
    ): Affine =
        when (name) {
            "matrix" -> {
                require(values.size == MATRIX_COMPONENT_COUNT)
                Affine(
                    values[0],
                    values[1],
                    values[2],
                    values[MATRIX_D_INDEX],
                    values[MATRIX_E_INDEX],
                    values[MATRIX_F_INDEX],
                )
            }
            "translate" -> {
                require(values.size in 1..2)
                Affine(e = values[0], f = values.getOrElse(1) { 0.0 })
            }
            "scale" -> {
                require(values.size in 1..2)
                Affine(a = values[0], d = values.getOrElse(1) { values[0] })
            }
            "rotate" -> rotation(values)
            else -> error("Unsupported bounded viewer transform")
        }

    private fun rotation(values: List<Double>): Affine {
        require(values.size == 1 || values.size == ROTATION_PIVOT_COMPONENT_COUNT)
        val angle = Math.toRadians(values[0])
        val rotated = Affine(cos(angle), sin(angle), -sin(angle), cos(angle))
        return if (values.size == 1) {
            rotated
        } else {
            Affine(e = values[1], f = values[2])
                .then(rotated)
                .then(Affine(e = -values[1], f = -values[2]))
        }
    }

    private data class Affine(
        val a: Double = 1.0,
        val b: Double = 0.0,
        val c: Double = 0.0,
        val d: Double = 1.0,
        val e: Double = 0.0,
        val f: Double = 0.0,
    ) {
        init {
            require(listOf(a, b, c, d).all { it.isFinite() && abs(it) <= MAX_LINEAR_COMPONENT })
            require(listOf(e, f).all { it.isFinite() && abs(it) <= MAX_TRANSLATION_COMPONENT })
        }

        fun then(next: Affine): Affine =
            Affine(
                a * next.a + c * next.b,
                b * next.a + d * next.b,
                a * next.c + c * next.d,
                b * next.c + d * next.d,
                a * next.e + c * next.f + e,
                b * next.e + d * next.f + f,
            )
    }

    private companion object {
        const val MATRIX_COMPONENT_COUNT = 6
        const val MATRIX_D_INDEX = 3
        const val MATRIX_E_INDEX = 4
        const val MATRIX_F_INDEX = 5
        const val ROTATION_PIVOT_COMPONENT_COUNT = 3
        const val MAX_LOCAL_COMPONENT = 8192.0
        const val MAX_LINEAR_COMPONENT = 65536.0
        const val MAX_TRANSLATION_COMPONENT = 1073741824.0
        val TRANSFORM = Regex("([A-Za-z]+)\\s*\\(([^()]*)\\)")
    }
}
