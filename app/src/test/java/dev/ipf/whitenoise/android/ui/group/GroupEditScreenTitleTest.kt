package dev.ipf.whitenoise.android.ui.group

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests for the Group Info edit screen title.
 *
 * The edit screen previously rendered the bare generic "Edit" (R.string.edit)
 * in its TopAppBar, which is ambiguous navigation copy. The screen must use a
 * dedicated descriptive title (edit_group_info_title) in the default locale
 * and every shipped locale, and the screen source must reference it.
 */
class GroupEditScreenTitleTest {
    private val resDir =
        listOf(File("app/src/main/res"), File("src/main/res"))
            .firstOrNull(File::isDirectory) ?: error("Missing Android resources")

    private fun stringsFile(dirName: String): File = File(resDir, "$dirName/strings.xml")

    private val sourceFile =
        listOf(
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/group/GroupEditScreen.kt"),
            File("src/main/java/dev/ipf/whitenoise/android/ui/group/GroupEditScreen.kt"),
        ).firstOrNull(File::isFile) ?: error("Missing GroupEditScreen source")

    private fun stringValue(
        file: File,
        name: String,
    ): String? {
        val match =
            Regex(
                """<string\s+name=["']${Regex.escape(name)}["'][^>]*>(.*?)</string>""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(file.readText())
        return match?.groupValues?.get(1)?.trim()
    }

    @Test
    fun editScreenDedicatedTitleExistsInDefaultLocale() {
        val defaultStrings = stringsFile("values")
        assertTrue(
            "values/strings.xml must define edit_group_info_title",
            stringValue(defaultStrings, "edit_group_info_title") != null,
        )
    }

    @Test
    fun editScreenDedicatedTitleIsNotTheGenericEditLabel() {
        val defaultStrings = stringsFile("values")
        val title = stringValue(defaultStrings, "edit_group_info_title")
        val generic = stringValue(defaultStrings, "edit")
        assertTrue(
            "edit_group_info_title must differ from the bare generic 'Edit' label",
            title != null && generic != null && !title.equals(generic, ignoreCase = true),
        )
        assertTrue(
            "edit_group_info_title should name the object being edited (group info)",
            title!!.contains("group", ignoreCase = true),
        )
    }

    @Test
    fun editScreenDedicatedTitleExistsInEveryShippedLocale() {
        val localizedStrings =
            resDir
                .listFiles { file -> file.isDirectory && file.name.startsWith("values-") }
                .orEmpty()
                .filter { File(it, "strings.xml").isFile }
                .map { it.name }
        assertTrue("No localized values dirs found under $resDir", localizedStrings.isNotEmpty())

        localizedStrings.forEach { dirName ->
            val file = stringsFile(dirName)
            assertTrue(
                "$dirName/strings.xml is missing edit_group_info_title",
                stringValue(file, "edit_group_info_title") != null,
            )
        }
    }

    @Test
    fun editScreenDelegatesToGroupEditTopBar() {
        val screenBody = functionBody("GroupEditScreen")

        assertTrue(
            "GroupEditScreen must delegate its top bar to GroupEditTopBar",
            Regex("""topBar\s*=\s*\{\s*GroupEditTopBar\s*\(\s*onBack\s*=\s*onBack\s*\)\s*}""")
                .containsMatchIn(screenBody),
        )
    }

    @Test
    fun groupEditTopBarUsesDedicatedTitle() {
        val topBarBody = functionBody("GroupEditTopBar")

        assertTrue(
            "GroupEditTopBar title must use edit_group_info_title",
            Regex("""title\s*=\s*\{\s*Text\s*\(\s*stringResource\s*\(\s*R\.string\.edit_group_info_title""")
                .containsMatchIn(topBarBody),
        )
        assertTrue(
            "GroupEditTopBar title must not use the bare generic edit resource",
            !Regex("""title\s*=\s*\{\s*Text\s*\(\s*stringResource\s*\(\s*R\.string\.edit\s*\)""")
                .containsMatchIn(topBarBody),
        )
    }

    @Test
    fun editDetailsSectionUsesDedicatedTitle() {
        val screenBody = functionBody("GroupEditScreen")

        assertTrue(
            "GroupEditScreen details card must use edit_group_info_title",
            Regex("""SectionCard\s*\(\s*title\s*=\s*stringResource\s*\(\s*R\.string\.edit_group_info_title""")
                .containsMatchIn(screenBody),
        )
    }

    private fun functionBody(functionName: String): String {
        val text = sourceFile.readText()
        // Strip comments so a commented-out reference cannot satisfy the check.
        val noComments =
            text
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")
        val signature =
            Regex("""(?:internal\s+)?fun\s+${Regex.escape(functionName)}\s*\(""").find(noComments)
                ?: error("Missing $functionName function")
        val openingBrace = noComments.indexOf('{', signature.range.last)
        require(openingBrace >= 0) { "Missing $functionName body" }
        var depth = 0
        for (index in openingBrace until noComments.length) {
            when (noComments[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return noComments.substring(openingBrace + 1, index)
                }
            }
        }
        error("Unclosed $functionName body")
    }
}
