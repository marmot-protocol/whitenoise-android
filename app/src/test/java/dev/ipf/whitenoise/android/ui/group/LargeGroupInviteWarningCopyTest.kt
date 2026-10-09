package dev.ipf.whitenoise.android.ui.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LargeGroupInviteWarningCopyTest {
    /** Every maintained translation names the same boundary used by the warning and confirmation. */
    @Test
    fun warningAndConfirmationCopyMatchTheThresholdInEveryLocale() {
        val resDir = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val keys = setOf("large_group_invite_warning_message", "large_group_invite_confirm_message")
        val parser =
            DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        val locales =
            resDir.listFiles().orEmpty().filter {
                it.isDirectory && it.name.startsWith("values") && File(it, "strings.xml").isFile
            }
        assertTrue("No maintained string resources found", locales.isNotEmpty())
        locales.forEach { locale ->
            val strings = parser.newDocumentBuilder().parse(File(locale, "strings.xml")).getElementsByTagName("string")
            val found = mutableSetOf<String>()
            for (index in 0 until strings.length) {
                val node = strings.item(index)
                val key = node.attributes.getNamedItem("name").nodeValue
                if (key in keys) {
                    found += key
                    val numbers = Regex("\\d+").findAll(node.textContent).map { it.value }.toList()
                    assertEquals(
                        "${locale.name}/$key",
                        listOf(LARGE_GROUP_INVITE_WARNING_THRESHOLD.toString()),
                        numbers,
                    )
                }
            }
            assertEquals("${locale.name} omits warning copy", keys, found)
        }
    }
}
