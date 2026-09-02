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

    /** Ensures a property helper excludes declarations following the initializer call. */
    @Test
    fun propertyInitializerCallStopsAtItsBalancedArgumentList() {
        val source =
            """
            private val recovery =
                Coordinator(
                    shouldContinue = { value != ")" },
                    note = "ignored ) in string",
                )
            private val unrelated = true
            """.trimIndent()

        assertEquals(
            """
            (
                    shouldContinue = { value != ")" },
                    note = "ignored ) in string",
                )
            """.trimIndent(),
            source.propertyInitializerCall("recovery"),
        )
    }
}
