package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationMentionPickerStateCoverageTest {
    @Test
    fun mentionCandidatesUseContactDisplayNameCachedForPickerLabelsAndFiltering() {
        val body = mentionPickerSource().readText().functionBody("rememberConversationMentionPickerState")

        assertTrue(
            "mention picker candidates must use contactDisplayNameCached so private nicknames flow into Candidate.displayName and MentionComposer.filter",
            "contactDisplayNameCached(member.memberIdHex)" in body,
        )
        assertFalse(
            "mention picker must not use chatMemberTitleCached for candidate displayName; that skips the local nickname",
            Regex("""displayName\s*=\s*appState\.chatMemberTitleCached\s*\(\s*member\.memberIdHex\s*\)""")
                .containsMatchIn(body),
        )
    }

    private fun mentionPickerSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/composer/ConversationMentionPickerState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/composer/ConversationMentionPickerState.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ConversationMentionPickerState.kt source file")
}
