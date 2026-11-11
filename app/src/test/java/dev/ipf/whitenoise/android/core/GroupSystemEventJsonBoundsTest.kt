package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounds pre-scan must count the same structure every org.json parser sees.
 * Android's tokenizer accepts lexical extensions that can hide brackets from a
 * counting scanner, so each extension is rejected before parsing. These checks
 * call the scanner directly: the JVM test classpath may resolve a stricter
 * org.json that would reject the inputs on its own.
 */
class GroupSystemEventJsonBoundsTest {
    private val validEvent =
        """{"v":1,"system_type":"group_name_changed","text":"Renamed",""" +
            """"data":{"name":"New","old_name":"Old","old_retention_seconds":0}}"""

    private fun nested(levels: Int): String = "[".repeat(levels) + "0" + "]".repeat(levels)

    private fun eventWithData(data: String): String = """{"system_type":"x","data":$data}"""

    @Test
    fun strictJsonEventsStillParse() {
        val event = GroupSystemEventJson.parse(validEvent)
        assertEquals("group_name_changed", event?.systemType)
        assertEquals("New", event?.name)
        assertEquals("Old", event?.oldName)
        assertTrue(GroupSystemEventJson.withinResourceBounds(" \t\r\n$validEvent\n"))
        assertTrue(
            GroupSystemEventJson.withinResourceBounds(
                """{"a":[true,false,null,-1.5e+3,0],"s":"\" { [ /* # ' ; = é 😀 \\"}""",
            ),
        )
    }

    @Test
    fun depthAndCollectionLimitsHold() {
        assertTrue(GroupSystemEventJson.withinResourceBounds(nested(GroupSystemEventJson.MAX_JSON_DEPTH)))
        assertFalse(GroupSystemEventJson.withinResourceBounds(nested(GroupSystemEventJson.MAX_JSON_DEPTH + 1)))
        val limit = GroupSystemEventJson.MAX_COLLECTION_ELEMENTS
        assertTrue(GroupSystemEventJson.withinResourceBounds(List(limit) { "0" }.joinToString(",", "[", "]")))
        assertFalse(GroupSystemEventJson.withinResourceBounds(List(limit + 1) { "0" }.joinToString(",", "[", "]")))
    }

    @Test
    fun lexicalExtensionsThatHideStructureAreRejected() {
        val deep = nested(GroupSystemEventJson.MAX_JSON_DEPTH + 4)
        listOf(
            "/*\"*/" + eventWithData(deep),
            "//\"\n" + eventWithData(deep),
            "#\"\n" + eventWithData(deep),
            """{"a":b","c":$deep}""",
            """{'a':"x","c":1}""",
            """{"a":1;"b":2}""",
            """{"a"=1}""",
            """{"a"=>1}""",
            "\uFEFF$validEvent",
            "\u000C$validEvent",
            "\u00A0$validEvent",
            "]$validEvent",
        ).forEach { input ->
            assertFalse(input, GroupSystemEventJson.withinResourceBounds(input))
            assertNull(input, GroupSystemEventJson.parse(input))
        }
    }

    @Test
    fun ordinaryMalformedJsonStaysOnTheParserErrorPath() {
        listOf("", "{", """{"system_type":}""", """{"system_type":"x"""", "[1,2]").forEach { input ->
            assertNull(input, GroupSystemEventJson.parse(input))
        }
    }

    @Test
    fun utf8ByteLimitCountsEncodedBytes() {
        val overhead = """{"s":""}""".length
        val budget = GroupSystemEventJson.MAX_INPUT_BYTES - overhead
        assertTrue(GroupSystemEventJson.withinResourceBounds("""{"s":"${"a".repeat(budget)}"}"""))
        assertFalse(GroupSystemEventJson.withinResourceBounds("""{"s":"${"a".repeat(budget - 1)}é"}"""))
    }
}
