package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LongMessageFullScreenComposerCoverageTest {
    @Test
    fun fullScreenLongMessageReaderReservesComposerSlot() {
        val body = messageFullScreenSource().readText().functionBody("MessageFullScreenView")

        assertTrue(
            "the full-screen long-message reader must expose a Scaffold bottomBar so the composer stays docked while the body scrolls",
            "bottomBar = bottomBar" in body,
        )
        assertTrue(
            "the full-screen long-message reader dialog must opt out of decor fitting so ComposerBar can receive IME insets",
            "decorFitsSystemWindows = false" in body,
        )
        assertFalse(
            "the edge-to-edge dialog should not zero TopAppBar status-bar insets; otherwise the bar can sit under the status bar",
            "WindowInsets(0, 0, 0, 0)" in body,
        )
    }

    @Test
    fun expandedLongMessageUsesStandardConversationComposer() {
        val body = messageBubbleSource().readText().functionBody("MessageBubble")
        val fullScreenCall =
            body
                .substringAfter("MessageFullScreenView(")
                .substringBefore("if (emojiPickerOpen")

        assertTrue(
            "expanded long-message view must keep its absolute clock timestamp instead of the bubble-relative label",
            "timeText = rememberedClockTime(record.recordedAt)" in fullScreenCall,
        )
        assertFalse(
            "expanded long-message view must not use the relative bubble timestamp formatter",
            "timeText = rememberedMessageBubbleTime(record.recordedAt)" in fullScreenCall,
        )
        assertTrue(
            "expanded long-message view must render the standard ComposerBar, not a separate ad-hoc input",
            "ComposerBar(" in fullScreenCall,
        )
        assertTrue(
            "expanded long-message view must render through the same ComposerGate states as the normal conversation bottom bar",
            "when (composerGate)" in fullScreenCall &&
                "ComposerGate.PENDING" in fullScreenCall &&
                "ComposerGate.NOTICE" in fullScreenCall &&
                "ComposerGate.INVITE" in fullScreenCall &&
                "ComposerGate.COMPOSER" in fullScreenCall &&
                "RemovedMemberComposerNotice()" in fullScreenCall &&
                "InvitePreviewActionBar(" in fullScreenCall,
        )
        assertTrue(
            "expanded reader reply/react actions must require a live message and an active shared composer",
            "if (expandedFullView && !deleted)" in body &&
                "val canUseExpandedComposer = !deleted && !readOnly && composerGate == ComposerGate.COMPOSER" in body &&
                "canReply = canUseExpandedComposer" in fullScreenCall &&
                "canReact = canUseExpandedComposer" in fullScreenCall,
        )
        assertTrue(
            "tapping Reply in the expanded reader should keep the reader open and focus the in-reader composer",
            Regex("""onReply\s*=\s*\{\s*if \(canUseExpandedComposer\) \{\s*beginReply\(\)\s*}\s*}""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(fullScreenCall),
        )
    }

    @Test
    fun conversationPassesMainComposerGateAndMentionPickerToExpandedReader() {
        val screenBody = conversationSource("ConversationScreen.kt").readText()
        val bottomBarBody = conversationSource("ConversationBottomBar.kt").readText()

        assertTrue(
            "ConversationScreen must compute one ComposerGate result and pass it both to the main bottom bar and expanded reader",
            "val composerGate =" in screenBody &&
                "when (composerGate)" in bottomBarBody &&
                Regex("composerGate = composerGate").findAll(screenBody).count() >= 2,
        )
        assertTrue(
            "mention candidate setup should be shared so the main composer and expanded-reader composer cannot drift",
            "val mentionPicker =" in screenBody &&
                "rememberConversationMentionPickerState(" in screenBody &&
                Regex("mentionCandidates = mentionPicker.candidates").findAll(screenBody).count() >= 2 &&
                Regex("mentionPickerEnabled = mentionPicker.enabled").findAll(screenBody).count() >= 2,
        )
    }

    private fun messageFullScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageFullScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageFullScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MessageFullScreen.kt source file")

    private fun messageBubbleSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MessageBubble.kt source file")

    private fun conversationSource(fileName: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/$fileName"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/$fileName"),
        ).firstOrNull { it.exists() }
            ?: error("Missing $fileName source file")
}
