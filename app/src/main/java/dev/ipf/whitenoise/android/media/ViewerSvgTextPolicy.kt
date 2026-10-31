package dev.ipf.whitenoise.android.media

import org.xmlpull.v1.XmlPullParser

/** Bounded viewer-only text; identity SVG preparation keeps its original element subset. */
internal object ViewerSvgTextPolicy {
    private const val MAX_GEOMETRY_NUMBERS = 8192
    private const val MAX_NUMBER = 8192.0
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?(?:px)?")
    private val coordinates = setOf("x", "y", "dx", "dy", "font-size")
    private val opacity = setOf("opacity", "fill-opacity", "stroke-opacity", "stop-opacity")
    private val attributes =
        setOf(
            "id",
            "space",
            "x",
            "y",
            "dx",
            "dy",
            "font-size",
            "font-family",
            "font-weight",
            "font-style",
            "text-anchor",
            "dominant-baseline",
            "fill",
            "stroke",
            "stroke-width",
            "opacity",
            "fill-opacity",
            "stroke-opacity",
            "transform",
        )
    private val knownAttributes =
        attributes +
            setOf(
                "version",
                "viewBox",
                "preserveAspectRatio",
                "width",
                "height",
                "d",
                "points",
                "x1",
                "x2",
                "y1",
                "y2",
                "cx",
                "cy",
                "r",
                "rx",
                "ry",
                "fx",
                "fy",
                "gradientUnits",
                "gradientTransform",
                "spreadMethod",
                "offset",
                "stop-color",
                "stop-opacity",
                "fill-rule",
                "stroke-linecap",
                "stroke-linejoin",
                "stroke-miterlimit",
                "color",
                "display",
                "visibility",
                "vector-effect",
            )
    private val choices =
        mapOf(
            "font-family" to setOf("sans-serif", "serif", "monospace", "cursive", "fantasy", "system-ui"),
            "font-weight" to setOf("normal", "bold", "100", "200", "300", "400", "500", "600", "700", "800", "900"),
            "font-style" to setOf("normal", "italic", "oblique"),
            "text-anchor" to setOf("start", "middle", "end"),
            "dominant-baseline" to
                setOf(
                    "auto",
                    "alphabetic",
                    "central",
                    "middle",
                    "hanging",
                    "text-before-edge",
                    "text-after-edge",
                ),
        )

    fun validate(parser: XmlPullParser) {
        for (index in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(index)
            val value = parser.getAttributeValue(index)
            require(name in knownAttributes)
            if (parser.name in setOf("text", "tspan")) require(name in attributes)
            choices[name]?.let { require(value in it) }
            if (name in coordinates) validateCoordinates(name, value)
            if (name == "stroke-miterlimit") {
                require(
                    value.toDoubleOrNull()?.let { it.isFinite() && it in 1.0..MAX_NUMBER } == true,
                )
            }
            if (name == "offset") validateGradientOffset(value)
            if (name in opacity) require(value.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..1.0 } == true)
        }
    }

    private fun validateGradientOffset(value: String) {
        val limit = if (value.endsWith("%")) 100.0 else 1.0
        require(value.removeSuffix("%").toDoubleOrNull()?.let { it.isFinite() && it in 0.0..limit } == true)
    }

    private fun validateCoordinates(
        name: String,
        value: String,
    ) {
        val values = value.trim().split(Regex("[ ,]+"))
        require(values.isNotEmpty() && values.size <= MAX_GEOMETRY_NUMBERS)
        require(values.all(::validCoordinate))
        if (name == "font-size") require(values.size == 1 && values.single().removeSuffix("px").toDouble() > 0)
    }

    private fun validCoordinate(value: String): Boolean =
        number.matches(value) &&
            value.removeSuffix("px").toDoubleOrNull()?.let {
                it.isFinite() && kotlin.math.abs(it) <= MAX_NUMBER
            } == true
}
