package dev.ipf.whitenoise.android.fuzz

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FuzzJsonStructureTest {
    @Test
    fun withinBounds_acceptsEmptyObject() {
        assertTrue(FuzzJsonStructure.withinBounds("{}"))
    }

    @Test
    fun withinBounds_acceptsQuotedBracesAndCommasInsideStrings() {
        val json = """{"text":"{[,]}","nested":"a,b"}"""
        assertTrue(FuzzJsonStructure.withinBounds(json))
    }

    @Test
    fun withinBounds_rejectsDepthOverLimit() {
        val nested =
            buildString {
                repeat(FuzzBounds.MAX_DEPTH + 1) {
                    append('[')
                }
                append('1')
                repeat(FuzzBounds.MAX_DEPTH + 1) {
                    append(']')
                }
            }
        assertFalse(FuzzJsonStructure.withinBounds(nested))
    }

    @Test
    fun withinBounds_acceptsDepthAtLimit() {
        val nested =
            buildString {
                repeat(FuzzBounds.MAX_DEPTH) {
                    append('[')
                }
                repeat(FuzzBounds.MAX_DEPTH) {
                    append(']')
                }
            }
        assertTrue(FuzzJsonStructure.withinBounds(nested))
    }

    @Test
    fun withinBounds_acceptsScalarInsideDeepestPermittedContainer() {
        val nested =
            "[".repeat(FuzzBounds.MAX_DEPTH) +
                "0" +
                "]".repeat(FuzzBounds.MAX_DEPTH)
        assertTrue(FuzzJsonStructure.withinBounds(nested))
    }

    @Test
    fun withinBounds_rejectsCollectionOverLimit() {
        val elements = (1..FuzzBounds.MAX_COLLECTION_ELEMENTS + 1).joinToString(",")
        assertFalse(FuzzJsonStructure.withinBounds("[$elements]"))
    }

    @Test
    fun withinBounds_rejectsLenientObjectOverLimit() {
        val members =
            (1..FuzzBounds.MAX_COLLECTION_ELEMENTS + 1)
                .joinToString(";") { index -> "\"field$index\":$index" }

        assertFalse(FuzzJsonStructure.withinBounds("{$members}"))
    }

    @Test
    fun withinBounds_acceptsCollectionAtLimit() {
        val elements = (1..FuzzBounds.MAX_COLLECTION_ELEMENTS).joinToString(",")
        assertTrue(FuzzJsonStructure.withinBounds("[$elements]"))
    }

    @Test
    fun withinBounds_acceptsMalformedUnclosedString() {
        assertTrue(FuzzJsonStructure.withinBounds("""{"key":"unterminated"""))
        assertFalse(FuzzJsonStructure.scan("""{"key":"unterminated""").recognized)
    }

    @Test
    fun withinBounds_acceptsMalformedTrailingComma() {
        assertTrue(FuzzJsonStructure.withinBounds("""{"a":1,}"""))
        assertFalse(FuzzJsonStructure.scan("""{"a":1,}""").recognized)
    }

    @Test
    fun withinBounds_acceptsLenientPlainAndUnrecognizedInput() {
        assertTrue(FuzzJsonStructure.withinBounds("not json at all"))
        assertTrue(FuzzJsonStructure.withinBounds("  "))
        assertFalse(FuzzJsonStructure.scan("not json at all").recognized)
    }

    @Test
    fun withinBounds_stillRejectsLimitsAfterSyntaxBecomesUnrecognized() {
        val tooDeep = "not-json " + "[".repeat(FuzzBounds.MAX_DEPTH + 1)
        val tooMany = "[" + List(FuzzBounds.MAX_COLLECTION_ELEMENTS + 1) { "bare" }.joinToString(",") + "]"

        assertFalse(FuzzJsonStructure.withinBounds(tooDeep))
        assertFalse(FuzzJsonStructure.withinBounds(tooMany))
    }

    @Test
    fun withinBounds_rejectsInputSizeOverLimit() {
        assertTrue(FuzzJsonStructure.withinBounds("x".repeat(FuzzBounds.MAX_STRING_BYTES)))
        assertFalse(FuzzJsonStructure.withinBounds("x".repeat(FuzzBounds.MAX_STRING_BYTES + 1)))
    }
}
