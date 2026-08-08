package dev.ipf.whitenoise.android

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceBlockTestHelpersTest {
    @Test
    fun functionBodySkipsBracesInsideTriviaAfterParameters() {
        val source =
            """
            fun sample() /* { ignored } */
                // { also ignored }
                @Suppress("{ ignored in string }")
                { return "body" }
            """.trimIndent()

        assertEquals("{ return \"body\" }", source.functionBody("sample"))
    }
}
