package dev.ipf.whitenoise.android.ui.common

import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant gate for #3601: an emoji sheet opened from a text field opens underneath the field and never covers it.
 * Every `EmojiPickerSheet` call passes its field's anchor unless it is a reviewed picker with no text field, and the
 * shared text fields anchor their emoji action.
 */
class EmojiSheetFieldAnchorCoverageTest {
    @Test
    fun everyEmojiSheetFromATextFieldPassesTheFieldAnchor() {
        val root = appModuleFile("src/main/java")
        val unanchored =
            root
                .walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { file ->
                    val source = file.readText()
                    SHEET_CALL
                        .findAll(source)
                        .map { call -> source.kotlinBlockFrom(call.range.last, "EmojiPickerSheet call", '(', ')') }
                        .filterNot { arguments -> "fieldAnchor =" in arguments }
                        .map { file.relativeTo(root).invariantSeparatorsPath }
                }.sorted()
                .toList()
        assertEquals(NO_TEXT_FIELD, unanchored)
    }

    @Test
    fun sharedTextFieldsAnchorTheirEmojiAction() {
        val textField = appModuleSource("$COMMON/WhiteNoiseTextField.kt").functionBody("WhiteNoiseTextField")
        assertTrue("Modifier.emojiSheetFieldAnchor(emojiAnchor)" in textField)
        assertTrue("anchor = emojiAnchor" in textField)

        val prose = appModuleSource("$COMMON/ProseTextField.kt").functionBody("ProseTextField")
        assertTrue("containerModifier.emojiSheetFieldAnchor(emojiAnchor)" in prose)
        assertTrue("anchor = emojiAnchor" in prose)

        val groupName = appModuleSource("$COMMON/GroupNameEmojiField.kt").functionBody("GroupNameEmojiField")
        assertTrue("openBelowField(onEmojiPickerClick)" in groupName)
        assertTrue("emojiSheetFieldAnchor(it)" in groupName)

        val action = appModuleSource("$COMMON/TextEntryEmojiField.kt").functionBody("TextEntryEmojiFieldAction")
        assertTrue("openBelowField(open)" in action)
        assertTrue("fieldAnchor = anchor" in action)

        val newGroup = appModuleSource("$NEW_CHAT/NewGroupSetupContent.kt")
        val setup = newGroup.functionBody("NewGroupSetupContent")
        val nameField = newGroup.functionBody("GroupSetupNameField")
        assertTrue("GroupSetupNameField(draft, state, customize, actions.emoji)" in setup)
        assertTrue(".emojiSheetFieldAnchor(draft.nameEmojiAnchor)" in nameField)
        assertTrue("openBelowName(onEmoji)" in nameField)
    }

    private companion object {
        val SHEET_CALL = Regex("""(?<!fun )\bEmojiPickerSheet\(""")
        const val COMMON = "src/main/java/dev/ipf/whitenoise/android/ui/common"
        const val NEW_CHAT = "src/main/java/dev/ipf/whitenoise/android/ui/chats/newchat"

        /** Reaction pickers open from a message or timeline row, not from a text field; they keep the tall sheet. */
        val NO_TEXT_FIELD =
            listOf(
                "dev/ipf/whitenoise/android/ui/conversation/GroupSystemTimelineRow.kt",
                "dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt",
            )
    }
}
