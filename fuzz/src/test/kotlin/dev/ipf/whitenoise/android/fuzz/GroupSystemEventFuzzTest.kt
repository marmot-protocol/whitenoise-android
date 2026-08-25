package dev.ipf.whitenoise.android.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.DictionaryEntries
import com.code_intelligence.jazzer.junit.DictionaryFile
import com.code_intelligence.jazzer.junit.FuzzTest
import dev.ipf.whitenoise.android.core.GroupSystemEventJson
import org.junit.jupiter.api.Tag

@Tag("fuzz-group-system")
class GroupSystemEventFuzzTest {
    @DictionaryEntries(
        "system_type",
        "member_added",
        "group_renamed",
        "disappearing_timer_changed",
        "actor",
        "subject",
        "old_retention_seconds",
        "new_retention_seconds",
    )
    @DictionaryFile(resourcePath = "/fuzz-grammar.dict")
    @FuzzTest
    fun fuzzGroupSystemEvent(data: FuzzedDataProvider) {
        data.consumeSubtarget(GroupSystemSubtarget.COUNT)
        val plaintext = data.consumeParserInput()

        exerciseParser(plaintext, requireAccepted = false)

        val selector = plaintext.hashCode()
        exerciseParser(generatedNestedJson(selector), requireAccepted = true)
        exerciseParser(generatedWideJson(selector), requireAccepted = true)
        exerciseParser(
            generatedNestedJson(GroupSystemEventJson.MAX_JSON_DEPTH - 1),
            requireAccepted = true,
        )
        exerciseParser(
            generatedWideJson(GroupSystemEventJson.MAX_COLLECTION_ELEMENTS),
            requireAccepted = true,
        )
    }

    private fun exerciseParser(
        plaintext: String,
        requireAccepted: Boolean,
    ) {
        val first = GroupSystemEventJson.parse(plaintext)
        val second = GroupSystemEventJson.parse(plaintext)

        FuzzAssertions.assertEquals("group-system parsing must be deterministic", first, second)
        if (requireAccepted) {
            FuzzAssertions.assertTrue("bounded generated group-system JSON must parse", first != null)
        }
        if (first != null) {
            FuzzAssertions.assertNull("raw JSON must not authenticate a payload actor", first.actor)
            FuzzAssertions.assertNull("raw JSON must not authenticate a payload subject", first.subject)
            FuzzAssertions.assertFalse(
                "raw JSON must remain an unauthenticated state projection",
                first.fromAuthenticatedStateProjection,
            )
        }
    }

    private fun generatedNestedJson(selector: Int): String {
        val nestedContainers = Math.floorMod(selector, GroupSystemEventJson.MAX_JSON_DEPTH)
        return buildString {
            append("{\"system_type\":\"generated_nested\",\"data\":")
            repeat(nestedContainers) { append('[') }
            append('0')
            repeat(nestedContainers) { append(']') }
            append('}')
        }
    }

    private fun generatedWideJson(selector: Int): String {
        val memberCount = Math.floorMod(selector, GroupSystemEventJson.MAX_COLLECTION_ELEMENTS + 1)
        return (0 until memberCount).joinToString(
            prefix = "{\"system_type\":\"generated_wide\",\"data\":{",
            postfix = "}}",
        ) { index -> "\"field$index\":$index" }
    }
}
