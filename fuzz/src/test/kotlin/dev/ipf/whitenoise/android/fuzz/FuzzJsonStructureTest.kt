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
                repeat(FuzzBounds.MAX_DEPTH - 1) {
                    append('[')
                }
                append('1')
                repeat(FuzzBounds.MAX_DEPTH - 1) {
                    append(']')
                }
            }
        assertTrue(FuzzJsonStructure.withinBounds(nested))
    }

    @Test
    fun withinBounds_rejectsCollectionOverLimit() {
        val elements = (1..FuzzBounds.MAX_COLLECTION_ELEMENTS + 1).joinToString(",")
        assertFalse(FuzzJsonStructure.withinBounds("[$elements]"))
    }

    @Test
    fun withinBounds_acceptsCollectionAtLimit() {
        val elements = (1..FuzzBounds.MAX_COLLECTION_ELEMENTS).joinToString(",")
        assertTrue(FuzzJsonStructure.withinBounds("[$elements]"))
    }

    @Test
    fun withinBounds_rejectsMalformedUnclosedString() {
        assertFalse(FuzzJsonStructure.withinBounds("""{"key":"unterminated"""))
    }

    @Test
    fun withinBounds_rejectsMalformedTrailingComma() {
        assertFalse(FuzzJsonStructure.withinBounds("""{"a":1,}"""))
    }
}
