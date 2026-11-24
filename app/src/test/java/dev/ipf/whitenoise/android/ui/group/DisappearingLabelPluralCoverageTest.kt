package dev.ipf.whitenoise.android.ui.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level guards that every counted disappearing-timer unit resolves through a plural resource. */
class DisappearingLabelPluralCoverageTest {
    /** Each counted label spec unit renders through `pluralStringResource`, never a plain `%d` string. */
    @Test
    fun everyCountedLabelSpecUnitResolvesThroughAPluralResource() {
        val units = countedUnits()
        assertTrue("Expected counted units in DisappearingLabelSpec", units.size >= 7)

        val label = groupSource("DisappearingMessages.kt")
        units.forEach { unit ->
            val branchPattern = Regex("""is DisappearingLabelSpec\.$unit ->\s*([^\n]*)""")
            val branch = branchPattern.find(label)?.groupValues?.get(1).orEmpty()
            val expected = "disappearing_${unit.lowercase()}_count"
            assertTrue(
                "$unit must resolve through R.plurals.$expected, found: $branch",
                "pluralStringResource(R.plurals.$expected, spec.count.toInt(), spec.count.toInt())" in branch,
            )
        }
    }

    /** No locale ships a plain-number duration string, and every locale defines each unit as a plural. */
    @Test
    fun noPlainNumberDurationStringRemainsInAnyLocale() {
        val units = countedUnits().map { it.lowercase() }
        val files = resFiles()
        assertTrue("Expected the default and localized strings files", files.size >= 2)

        files.forEach { file ->
            val text = file.readText()
            assertTrue(
                "${file.parentFile.name} must not define a disappearing_*_format string",
                !Regex("""name="disappearing_\w+_format"""").containsMatchIn(text),
            )
            val plainNumber = Regex("""<string name="(disappearing_\w+)"[^>]*>[^<]*%(?:\d\$)?d""")
            val plainNumbers = plainNumber.findAll(text).map { it.groupValues[1] }.toList()
            assertEquals("${file.parentFile.name} has plain-number duration strings", emptyList<String>(), plainNumbers)
            units.forEach { unit ->
                val block =
                    Regex("""<plurals name="disappearing_${unit}_count">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                        .find(text)
                assertTrue("${file.parentFile.name} must define disappearing_${unit}_count", block != null)
                val quantities =
                    Regex("""quantity="(\w+)"""").findAll(block!!.groupValues[1]).map { it.groupValues[1] }.toSet()
                assertEquals(
                    "${file.parentFile.name} disappearing_${unit}_count quantities",
                    requiredQuantities(file.parentFile.name),
                    quantities,
                )
            }
        }
    }

    /** CLDR categories each shipped locale needs so a count of one never falls back to the plural form. */
    private fun requiredQuantities(dir: String): Set<String> =
        when {
            dir.startsWith("values-ru") -> setOf("one", "few", "many", "other")
            dir.startsWith("values-zh") || dir.startsWith("values-b+zh") -> setOf("other")
            else -> setOf("one", "other")
        }

    private fun countedUnits(): List<String> =
        Regex("""data class (\w+)\(\s*val count: Long""")
            .findAll(groupSource("DisappearingDuration.kt"))
            .map { it.groupValues[1] }
            .toList()

    private fun groupSource(name: String): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/group/$name"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/group/$name"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing $name")

    private fun resFiles(): List<File> {
        val res =
            sequenceOf(File("src/main/res"), File("app/src/main/res")).firstOrNull(File::isDirectory)
                ?: error("Missing res directory")
        return res
            .listFiles { f -> f.isDirectory && f.name.startsWith("values") }
            .orEmpty()
            .map { File(it, "strings.xml") }
            .filter(File::isFile)
    }
}
