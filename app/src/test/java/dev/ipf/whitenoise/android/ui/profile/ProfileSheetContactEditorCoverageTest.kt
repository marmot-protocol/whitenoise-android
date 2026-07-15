package dev.ipf.whitenoise.android.ui.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileSheetContactEditorCoverageTest {
    @Test
    fun profileSheetCollapsesNicknameSectionIntoSingleEditorRow() {
        val source = profileSheetSource().readText()

        assertFalse(
            "profile sheet must not render the old private nickname section card",
            "R.string.profile_private_nickname" in source,
        )
        assertFalse(
            "profile sheet must not keep a separate nickname-only action row",
            "R.string.profile_set_nickname" in source || "R.string.profile_edit_nickname" in source,
        )

        val actionRowsBlock =
            source.requiredSection(
                start = "if (hex != null && !targetIsSelf) {",
                end = "\n            // Group-admin moderation actions",
            )
        assertTrue(
            "profile sheet must expose nickname and notes from one action row",
            "R.string.profile_nickname_and_notes" in actionRowsBlock,
        )
        assertTrue(
            "profile sheet must show the empty nickname and notes state on the row",
            "R.string.profile_add_nickname_and_notes" in actionRowsBlock,
        )
        assertFalse(
            "profile sheet must not preview notes inline on the action row",
            "contactNotes" in actionRowsBlock,
        )
    }

    @Test
    fun contactEditorDialogEditsNicknameAndNotesTogether() {
        val source = profileSheetSource().readText()
        val dialogBody = source.kotlinFunctionBody("ContactPrivateDetailsDialog")
        val editorLaunchBlock =
            source.requiredSection(
                start = "if (showContactEditorDialog && hex != null && !targetIsSelf) {",
                end = "\n    }\n\n    ModalBottomSheet(",
            )

        assertTrue(
            "contact editor must label the private name field as Name",
            "R.string.profile_contact_name_hint" in dialogBody,
        )
        assertTrue(
            "contact editor must add a notes field",
            "R.string.profile_contact_notes_hint" in dialogBody,
        )
        assertTrue(
            "notes field must allow multi-line input",
            "singleLine = false" in dialogBody,
        )
        assertTrue(
            "save must persist nickname and notes together",
            "setContactNickname" in editorLaunchBlock && "setContactNotes" in editorLaunchBlock,
        )
    }

    private fun profileSheetSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/profile/ProfileSheet.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/profile/ProfileSheet.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ProfileSheet.kt source file")

    private fun String.kotlinFunctionBody(name: String): String {
        val signature = "fun $name("
        val startIndex = indexOf(signature)
        require(startIndex >= 0) { "Missing function: $name" }
        val braceStart = indexOf('{', startIndex)
        require(braceStart >= 0) { "Missing function body for: $name" }
        var depth = 0
        for (index in braceStart until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return substring(startIndex, index + 1)
                }
            }
        }
        error("Unterminated function body for: $name")
    }

    private fun String.requiredSection(
        start: String,
        end: String,
    ): String {
        val startIndex = indexOf(start)
        require(startIndex >= 0) { "Missing section start: $start" }
        val endIndex = indexOf(end, startIndex + start.length)
        require(endIndex >= 0) { "Missing section end: $end" }
        return substring(startIndex, endIndex)
    }
}
