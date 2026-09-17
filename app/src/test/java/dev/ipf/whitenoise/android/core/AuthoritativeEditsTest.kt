package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * MarmotKit 0.10.1 resolves accepted edits inside the engine, so a message's edit count and effective text
 * no longer depend on how much of the conversation the app has loaded. These pin that the engine's summary
 * wins over the window-limited local aggregate without discarding what the window already knows.
 */
class AuthoritativeEditsTest {
    /** An edit the loaded window never saw still marks the message as edited, with the engine's count. */
    @Test
    fun engineSummaryMarksAnEditTheWindowMissed() {
        val merged = withAuthoritativeEdits(emptyMap(), listOf(AuthoritativeEdit(TARGET, editCount = 3, "final text")))

        val state = merged.getValue(TARGET)
        assertEquals(3, state.count)
        assertEquals("final text", state.latestText)
        assertEquals(emptyList<EditVersion>(), state.versions)
    }

    /** The engine's count replaces an undercount from the window, and its effective text replaces the body. */
    @Test
    fun engineSummaryReplacesTheLocalUndercount() {
        val local = mapOf(TARGET to EditState(latestText = "stale", count = 1, versions = listOf(version("v1"))))

        val merged = withAuthoritativeEdits(local, listOf(AuthoritativeEdit(TARGET, editCount = 4, "final text")))

        val state = merged.getValue(TARGET)
        assertEquals(4, state.count)
        assertEquals("final text", state.latestText)
        assertEquals(listOf(version("v1")), state.versions)
    }

    /** Messages the engine reported nothing about keep exactly the state the window produced. */
    @Test
    fun untouchedMessagesKeepTheirLocalState() {
        val other = EditState(latestText = "kept", count = 2, versions = listOf(version("v9")))
        val local = mapOf("other" to other)

        val merged = withAuthoritativeEdits(local, listOf(AuthoritativeEdit(TARGET, editCount = 1, "final text")))

        assertSame(other, merged.getValue("other"))
        assertEquals(1, merged.getValue(TARGET).count)
    }

    /** A message the engine reports as never edited gets no marker. */
    @Test
    fun zeroEditsAddsNoMarker() {
        val merged = withAuthoritativeEdits(emptyMap(), listOf(AuthoritativeEdit(TARGET, editCount = 0, "text")))

        assertNull(merged[TARGET])
    }

    /** With nothing from the engine the local aggregate is returned untouched. */
    @Test
    fun noEngineSummariesLeavesTheAggregateAlone() {
        val local = mapOf(TARGET to EditState(latestText = "local", count = 1, versions = emptyList()))

        assertSame(local, withAuthoritativeEdits(local, emptyList()))
    }

    private fun version(id: String) = EditVersion(messageIdHex = id, text = "text-$id", recordedAt = 1uL)

    private companion object {
        const val TARGET = "target-message"
    }
}
