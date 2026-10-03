package dev.ipf.whitenoise.android.media

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A deliberately bounded static SVG subset, validated before AndroidSVG can see untrusted XML. */
internal object GroupSvgPolicy {
    const val MAX_BYTES = 256 * 1024
    private const val MAX_ELEMENTS = 256
    private const val MAX_DEPTH = 16
    private const val MAX_ATTRIBUTE_LENGTH = 16 * 1024
    private const val MAX_NUMBER = 8192.0
    private const val MAX_GEOMETRY_NUMBERS = 8192
    private const val MAX_TEXT_CHARACTERS = 8192
    private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
    private val elements =
        setOf(
            "svg",
            "g",
            "defs",
            "title",
            "desc",
            "path",
            "rect",
            "circle",
            "ellipse",
            "line",
            "polygon",
            "polyline",
            "linearGradient",
            "radialGradient",
            "stop",
        )
    private val geometry =
        setOf(
            "d",
            "points",
            "transform",
            "gradientTransform",
            "viewBox",
            "width",
            "height",
            "x",
            "y",
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
            "dx",
            "dy",
            "stroke-width",
            "stroke-dasharray",
            "stroke-dashoffset",
            "font-size",
        )
    private val forbidden =
        setOf(
            "href",
            "src",
            "filter",
            "mask",
            "clip-path",
            "marker-start",
            "marker-mid",
            "marker-end",
            "stroke-dasharray",
        )
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val localUrl = Regex("url\\(\\s*['\"]?#([A-Za-z_][A-Za-z0-9_.-]*)['\"]?\\s*\\)", RegexOption.IGNORE_CASE)
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")

    fun validate(bytes: ByteArray): String {
        val xml = validatedXml(bytes)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(xml.reader())
        val gradients = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        val references = mutableSetOf<String>()
        var count = 0
        var rootSeen = false
        var textCharacters = 0
        var geometryNumbers = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    require(++count <= MAX_ELEMENTS && parser.depth <= MAX_DEPTH)
                    require(parser.namespace == SVG_NAMESPACE && parser.name in elements)
                    if (!rootSeen) {
                        require(parser.name == "svg" && parser.depth == 1)
                        rootSeen = true
                    } else {
                        require(parser.name != "svg")
                    }
                    geometryNumbers += validateAttributes(parser, references)
                    require(geometryNumbers <= MAX_GEOMETRY_NUMBERS)
                    registerGradientId(parser, ids, gradients)
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    textCharacters += parser.text.length
                    require(textCharacters <= MAX_TEXT_CHARACTERS)
                }
                XmlPullParser.ENTITY_REF -> {
                    // DTDs are refused before parsing; only built-in/numeric text references remain.
                    require(parser.text != null)
                    textCharacters += parser.text.length
                    require(textCharacters <= MAX_TEXT_CHARACTERS)
                }
                XmlPullParser.DOCDECL, XmlPullParser.PROCESSING_INSTRUCTION -> error("Unsafe SVG XML")
            }
            parser.nextToken()
        }
        require(rootSeen && references.all { it in gradients })
        return xml
    }

    private fun validatedXml(bytes: ByteArray): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val xml =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
        // Never let either XML parser expand a DTD, including internal or external entities.
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true))
        return xml
    }

    private fun registerGradientId(
        parser: XmlPullParser,
        ids: MutableSet<String>,
        gradients: MutableSet<String>,
    ) {
        parser.getAttributeValue(null, "id")?.let { id ->
            require(identifier.matches(id) && ids.add(id))
            if (parser.name == "linearGradient" || parser.name == "radialGradient") gradients.add(id)
        }
    }

    private fun validateAttributes(
        parser: XmlPullParser,
        references: MutableSet<String>,
    ): Int {
        var geometryNumbers = 0
        for (index in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(index)
            val value = parser.getAttributeValue(index)
            val namespace = parser.getAttributeNamespace(index)
            require(
                namespace.isNullOrEmpty() || (namespace == "http://www.w3.org/XML/1998/namespace" && name == "space"),
            )
            require(!name.startsWith("on", ignoreCase = true) && name !in forbidden)
            require(value.length <= MAX_ATTRIBUTE_LENGTH && '\\' !in value && '@' !in value)
            val matches = localUrl.findAll(value).toList()
            require(!localUrl.replace(value, "").contains("url", ignoreCase = true))
            require(matches.isEmpty() || name in setOf("fill", "stroke", "style"))
            matches.forEach { references.add(it.groupValues[1]) }
            validateRootDimension(parser.depth, name, value)
            if (name == "style") validateStyle(value)
            geometryNumbers += geometryNumberCount(name, value)
        }
        return geometryNumbers
    }

    private fun validateStyle(value: String) {
        require(forbidden.none { Regex("(?:^|;)\\s*$it\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(value) })
        require(
            listOf("font-size", "stroke-width", "stroke-dasharray", "stroke-dashoffset")
                .none { value.contains(it, ignoreCase = true) },
        )
    }

    private fun validateRootDimension(
        depth: Int,
        name: String,
        value: String,
    ) {
        if (depth == 1 && name in setOf("width", "height")) {
            require(
                number
                    .find(value)
                    ?.value
                    ?.toDouble()
                    ?.let { it > 0 } == true,
            )
        }
    }

    private fun geometryNumberCount(
        name: String,
        value: String,
    ): Int {
        if (name !in geometry) return 0
        val numbers = number.findAll(value).toList()
        require(
            numbers.all { it.value.toDouble().let { n -> n.isFinite() && kotlin.math.abs(n) <= MAX_NUMBER } },
        )
        return numbers.size
    }
}
