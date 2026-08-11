package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentActivityPresentationTest {
    @Test
    fun commentaryPayloadProjectsVisibleTextAndStatus() {
        val record =
            activity(
                plaintext = """{"v":1,"status":"commentary","text":"Checking the review comments."}""",
            )
        val presentation = project(record)

        assertEquals("Checking the review comments.", presentation?.text)
        assertEquals("commentary", presentation?.status)
        assertEquals("Checking the review comments.", MessageProjector.displayBody(record))
        assertEquals("Checking the review comments.", MessageProjector.previewText(record))
    }

    @Test
    fun statusTagIsUsedWhenPayloadOmitsStatus() {
        val presentation =
            project(
                activity(
                    plaintext = """{"v":1,"text":"Still working."}""",
                    tags = listOf(MessageTagFfi(listOf("status", "thinking"))),
                ),
            )

        assertEquals("Still working.", presentation?.text)
        assertEquals("thinking", presentation?.status)
    }

    @Test
    fun malformedPayloadUsesSafeActivityFallback() {
        val record = activity("not-json")

        assertEquals("Agentenaktivität", AgentActivityProjector.project(record, "Agentenaktivität")?.text)
        assertEquals(
            "Agentenaktivität",
            MessageProjector.displayBody(record, MessageTextCopy.Default.copy(agentActivity = "Agentenaktivität")),
        )
    }

    @Test
    fun missingBlankOrNonStringTextUsesSafeActivityFallback() {
        listOf(
            "{}",
            """{"text":"  \n\t "}""",
            """{"text":42}""",
            """{"text":{"internal":"hidden"}}""",
        ).forEach { plaintext ->
            assertEquals("Agent activity", project(activity(plaintext))?.text)
            assertNull(AgentActivityProjector.previewText(plaintext))
        }
    }

    @Test
    fun nonObjectJsonUsesSafeActivityFallback() {
        listOf("null", "[]", "\"raw text\"").forEach { plaintext ->
            assertEquals("Agent activity", project(activity(plaintext))?.text)
            assertNull(AgentActivityProjector.previewText(plaintext))
        }
    }

    @Test
    fun oversizedPayloadUsesSafeActivityFallback() {
        val plaintext = """{"text":"${"a".repeat(64 * 1024)}"}"""

        assertEquals("Agent activity", project(activity(plaintext))?.text)
    }

    @Test
    fun multibytePayloadOverByteLimitUsesSafeActivityFallback() {
        val plaintext = """{"text":"${"é".repeat(33 * 1024)}"}"""

        assertEquals("Agent activity", project(activity(plaintext))?.text)
        assertNull(AgentActivityProjector.previewText(plaintext))
    }

    @Test
    fun wrongKindPayloadDoesNotProjectAsAgentActivity() {
        assertNull(project(activity("""{"text":"hello"}""").copy(kind = 9uL)))
    }

    @Test
    fun activityTextIsCappedForCompactSurfaces() {
        val text = "a".repeat(141)
        val plaintext = """{"text":"$text"}"""

        assertEquals("a".repeat(140), project(activity(plaintext))?.text)
        assertEquals("a".repeat(140), AgentActivityProjector.previewText(plaintext))
    }

    @Test
    fun activityTextIsFlattenedForCompactSurfaces() {
        val plaintext = """{"text":"Checking\nmultiple\tsteps."}"""

        assertEquals("Checking multiple steps.", project(activity(plaintext))?.text)
        assertEquals("Checking multiple steps.", AgentActivityProjector.previewText(plaintext))
    }

    private fun activity(
        plaintext: String,
        tags: List<MessageTagFfi> = emptyList(),
    ) = AppMessageRecordFfi(
        messageIdHex = "activity-1",
        direction = "received",
        groupIdHex = "group",
        sender = "agent",
        plaintext = plaintext,
        contentTokens =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = emptyList(),
                blankLinesBefore = ByteArray(0),
            ),
        kind = 1201uL,
        tags = tags,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = 1uL,
        receivedAt = 1uL,
    )

    private fun project(record: AppMessageRecordFfi) = AgentActivityProjector.project(record, "Agent activity")
}
