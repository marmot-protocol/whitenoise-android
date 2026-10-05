package dev.ipf.whitenoise.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalDeleteLocalizationTest {
    @Test
    fun germanRetryGuidanceNamesTheDisplayedDeleteAction() {
        val strings = strings("values-de")
        val label = strings.getValue("delete_from_device")
        listOf("local_delete_retry_detail", "chat_list_delete_stopped_detail").forEach { key ->
            assertTrue("$key must name $label", strings.getValue(key).contains(label))
        }
    }

    @Test
    fun italianStoppedCountUsesCountNeutralWording() {
        val detail = strings("values-it").getValue("chat_list_delete_stopped_detail")
        val prefix = "Eliminazioni confermate: %1\$d su %2\$d."
        assertTrue(detail.contains(prefix))
    }

    private fun strings(locale: String): Map<String, String> {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val document = builder.parse(File(res, "$locale/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }
}
