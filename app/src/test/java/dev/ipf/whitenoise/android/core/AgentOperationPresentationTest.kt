package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOperationPresentationTest {
    @Test
    fun structuredToolCallMapsPreviewArgumentsAndCompletionMetadata() {
        val record =
            agentOperation(
                """
                {
                    "event_type":"tool_call",
                    "name":"mcp__fff_whitenoise_android__grep",
                    "preview":"AgentOperation\napp/src/main",
                    "status":"completed",
                    "text":"⚙️ mcp__fff_whitenoise_android__grep: \"AgentOperation\"",
                    "details":{"args":{"query":"AgentOperation","paths":["app/src","test"]}},
                    "ok":true,
                    "duration_ms":1250,
                    "v":1
                }
                """.trimIndent(),
            )

        val presentation = AgentOperationProjector.project(record)
        assertNotNull(presentation)
        presentation!!

        assertEquals("tool_call", presentation.eventType)
        assertEquals("mcp__fff_whitenoise_android__grep", presentation.name)
        assertEquals("AgentOperation\napp/src/main", presentation.preview)
        assertEquals("AgentOperation\napp/src/main", presentation.collapsedText)
        assertEquals("completed", presentation.status)
        assertEquals(true, presentation.ok)
        assertEquals(1250L, presentation.durationMs)
        assertTrue(presentation.canExpand)
        assertTrue(presentation.argumentsJson.orEmpty().contains('\n'))
        val arguments = JSONObject(presentation.argumentsJson.orEmpty())
        assertEquals("AgentOperation", arguments.getString("query"))
        assertEquals("test", arguments.getJSONArray("paths").getString(1))
    }

    @Test
    fun missingDetailsKeepsPreviewExpandableWithoutInventingArguments() {
        val presentation =
            AgentOperationProjector.project(
                agentOperation(
                    """{"event_type":"tool_call","status":"started","text":"Searching","preview":"needle","v":1}""",
                    tags =
                        listOf(
                            MessageTagFfi(listOf("operation-name", "grep")),
                            MessageTagFfi(listOf("operation-status", "started")),
                        ),
                ),
            )
        assertNotNull(presentation)
        presentation!!

        assertEquals("grep", presentation.name)
        assertEquals("needle", presentation.collapsedText)
        assertEquals(null, presentation.argumentsJson)
        assertEquals(null, presentation.ok)
        assertEquals(null, presentation.durationMs)
        assertTrue(presentation.canExpand)
    }

    @Test
    fun nonAgentOperationDoesNotMapToToolCallPresentation() {
        assertEquals(null, AgentOperationProjector.project(agentOperation("hello").copy(kind = 9uL)))
        assertFalse(MessageProjector.isAgentOperation(agentOperation("hello").copy(kind = 9uL)))
    }

    private fun agentOperation(
        plaintext: String,
        tags: List<MessageTagFfi> = emptyList(),
    ) = AppMessageRecordFfi(
        messageIdHex = "operation-1",
        direction = "received",
        groupIdHex = "group",
        sender = "agent",
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
        kind = 1202uL,
        tags = tags,
        recordedAt = 1uL,
        receivedAt = 1uL,
    )
}
