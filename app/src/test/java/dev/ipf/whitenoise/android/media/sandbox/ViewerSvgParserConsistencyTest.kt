package dev.ipf.whitenoise.android.media.sandbox

import dev.ipf.whitenoise.android.media.GroupSvgPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ViewerSvgParserConsistencyTest {
    private fun svg(body: String): String =
        (
            """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24">""" +
                """$body</svg>"""
        )

    @Test
    fun everyAncestorRejectsUnhandledInheritedFontAttributes() {
        for (attribute in listOf(
            "font=\"1e30px serif\"",
            "font-variant=\"small-caps\"",
            "font-stretch=\"1e30%\"",
            "writing-mode=\"tb\"",
        )) {
            for (element in listOf("g", "svg")) {
                val body = """<g $attribute><text x="1" y="12">a</text></g>"""
                val source =
                    if (element == "g") {
                        svg(body)
                    } else {
                        svg("<text x=\"1\" y=\"12\">a</text>").replace(
                            "width=",
                            "$attribute width=",
                        )
                    }
                assertNull(ViewerSvgDecoder.inspect(source.toByteArray()))
            }
        }
    }

    @Test
    fun nonUtf8DeclarationsCannotChangeCharactersBetweenParsers() {
        for (encoding in listOf("UTF-7", "UTF-16", "IMAP-mailbox-name", "SCSU", "BOCU-1")) {
            val source =
                (
                    """<?xml version="1.0" encoding="$encoding"?>"""
                ) + svg("<g>+ADw-use href=+ACI-#a+ACI-/+AD4-</g>")
            assertThrows(IllegalArgumentException::class.java) { GroupSvgPolicy.validate(source.toByteArray()) }
            assertNull(ViewerSvgDecoder.inspect(source.toByteArray()))
        }
    }

    @Test
    fun ancestorTransformProductsCannotOverflowRendererMatrices() {
        val nested = "<g transform=\"scale(8192)\">".repeat(15) + "<text x=\"1\" y=\"12\">a</text>" + "</g>".repeat(15)
        assertNull(ViewerSvgDecoder.inspect(svg(nested).toByteArray()))
        assertNull(ViewerSvgDecoder.inspect(svg("<g transform=\"skewX(89.999999)\"><text>a</text></g>").toByteArray()))
        assertNotNull(
            ViewerSvgDecoder.inspect(
                svg(
                    "<g transform=\"translate(2, 2) scale(2) rotate(10)\"><text x=\"1\" y=\"8\">a</text></g>",
                ).toByteArray(),
            ),
        )
    }

    @Test
    fun malformedDeclarationInputsAreRejectedByViewerAndIdentityPolicy() {
        for (declaration in listOf(
            """<?xml version="1.0?" encoding="UTF-16LE"?>""",
            """<?xml version="encoding'UTF-16LE'"?>""",
            """<?xml version="1.0" encoding="UTF-8" encoding="UTF-16"?>""",
            """<?xml version="1.0>" encoding="UTF-16LE"?>""",
        )) {
            val source = (declaration + svg("<title>Åä é 水</title>")).toByteArray(Charsets.UTF_8)
            assertThrows(IllegalArgumentException::class.java) { GroupSvgPolicy.validate(source) }
            assertNull(ViewerSvgDecoder.inspect(source))
        }
    }

    @Test
    fun utf8DeclarationAndNonAsciiTextRemainValid() {
        val source =
            (
                """<?xml version="1.0" encoding="UTF-8"?>"""
            ) + svg("<text x=\"1\" y=\"12\" font-size=\"8\">Åä é 水</text>")
        assertNotNull(ViewerSvgDecoder.inspect(source.toByteArray(Charsets.UTF_8)))
        assertNotNull(
            GroupSvgPolicy.validate(
                (
                    (
                        """<?xml version="1.0" encoding="UTF-8"?>"""
                    ) + svg("<title>Åä é 水</title>")
                ).toByteArray(),
            ),
        )
    }
}
