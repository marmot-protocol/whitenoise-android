package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SvgXmlCanonicalizationTest {
    private val body = """<svg xmlns="http://www.w3.org/2000/svg"><title>Åä é 水</title></svg>"""

    @Test
    fun acceptedStandardDeclarationsAreStrippedBeforeEitherParser() {
        for (declaration in listOf(
            """<?xml version="1.0"?>""",
            """<?xml version='1.1' encoding='uTf-8' standalone='yes'?>""",
            """<?xml version="1.0" standalone="no"?>""",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""",
        )) {
            assertEquals(body, canonicalSvgXml(declaration + body))
            assertEquals(body, GroupSvgPolicy.validate((declaration + body).toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun malformedVersionHiddenEncodingDuplicateAndAlternatePrefixesFailClosed() {
        val declarations =
            listOf(
                """<?xml version="1.0?" encoding="UTF-16LE"?>""",
                """<?xml version="encoding'UTF-16LE'"?>""",
                """<?xml version="1.0" encoding="UTF-8" encoding="UTF-16"?>""",
                """<?xml version="1.0>" encoding="UTF-16LE"?>""",
                """<?xml version="1.0" encoding="UTF-7"?>""",
                """<?xml version='1.0" encoding='UTF-8'?>""",
                """<?XML version="1.0"?>""",
                """<?xm version="1.0"?>""",
                """ <?xml version="1.0"?>""",
                """<?xml version="1.0"?><?xml version="1.0" encoding="UTF-16LE"?>""",
            )
        for (declaration in declarations) {
            assertThrows(IllegalArgumentException::class.java) { canonicalSvgXml(declaration + body) }
            assertThrows(IllegalArgumentException::class.java) {
                GroupSvgPolicy.validate((declaration + body).toByteArray())
            }
        }
    }
}
