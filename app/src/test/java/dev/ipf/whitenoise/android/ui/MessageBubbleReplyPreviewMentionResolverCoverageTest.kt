package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MessageBubbleReplyPreviewMentionResolverCoverageTest {
    @Test
    fun timelineReplyPreviewMentionResolverIsStableUntilProfilePresentationChanges() {
        val body = messageBubbleSource().readText().functionBody("MessageBubble")
        val replyPreviewCall =
            body
                .substringAfter("val replyPreviewCard:")
                .substringBefore("if (hasMedia)")

        assertFalse(
            "timeline reply preview must not pass a fresh mentionDisplayName lambda every recomposition",
            Regex("""mentionDisplayName\s*=\s*\{\s*appState\.mentionDisplayName\(it\)\s*}""")
                .containsMatchIn(replyPreviewCall),
        )
        assertTrue(
            "timeline reply preview mentionDisplayName must invalidate when profile presentation changes",
            Regex(
                """mentionDisplayName\s*=\s*remember\(appState,\s*appState\.profileRevisionForCompose\)\s*\{\s*\{\s*bech32:\s*String\s*->\s*appState\.mentionDisplayName\(bech32\)\s*}\s*}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(replyPreviewCall),
        )
    }

    private fun messageBubbleSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MessageBubble.kt source file")
}
