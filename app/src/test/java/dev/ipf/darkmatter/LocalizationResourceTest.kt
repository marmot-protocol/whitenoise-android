package dev.ipf.darkmatter

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizationResourceTest {
    @Test
    fun localizedStringFilesHaveTheSameKeysAsDefaultEnglish() {
        val resDir = listOf(File("src/main/res"), File("app/src/main/res"))
            .first { it.exists() }
        val defaultKeys = stringKeys(File(resDir, "values/strings.xml"))

        resDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { File(it, "strings.xml") }
            .filter { it.exists() }
            .forEach { localized ->
                assertEquals(localized.path, defaultKeys, stringKeys(localized))
            }
    }

    private fun stringKeys(file: File): Set<String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val strings = document.getElementsByTagName("string")
        return buildSet {
            for (index in 0 until strings.length) {
                add(strings.item(index).attributes.getNamedItem("name").nodeValue)
            }
        }
    }
}
