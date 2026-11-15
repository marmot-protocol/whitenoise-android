package dev.ipf.whitenoise.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * Keeps `ConversationController.kt` from growing back. New conversation work belongs in the matching
     * `ConversationController<Topic>.kt` extension file, or a new one for a new topic. Lower
     * [CONVERSATION_CONTROLLER_MAX_LINES] when a change shrinks the file.
     */
    @Test
    fun conversationControllerClassFileDoesNotGrow() {
        val classFile = conversationControllerFiles().first()
        assertEquals("ConversationController.kt", classFile.name)
        assertTrue("Expected ConversationController topic files", conversationControllerFiles().size > 1)
        val lines =
            classFile
                .readText()
                .trimEnd()
                .lines()
                .size
        assertTrue(
            "ConversationController.kt has $lines lines (limit $CONVERSATION_CONTROLLER_MAX_LINES). Move new " +
                "work into a ConversationController<Topic>.kt extension file instead of growing the class.",
            lines <= CONVERSATION_CONTROLLER_MAX_LINES,
        )
    }

    private companion object {
        const val CONVERSATION_CONTROLLER_MAX_LINES = 4_964
    }
}
