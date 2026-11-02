package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
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

    /** Expanded long message uses standard conversation composer. */
    @Test
    fun expandedLongMessageUsesStandardConversationComposer() {
        val body = messageBubbleSource().readText().functionBody("MessageBubble")
        val fullScreenCall =
            body
                .substringAfter("MessageFullScreenView(")
                .substringBefore("if (surfaces.emojiPickerOpen")

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
                "InvitationActions(" in fullScreenCall,
        )
        val expandedComposerGate =
            "val canUseExpandedComposer = !deleted && !actionsReadOnly && " +
                "composerGate == ComposerGate.COMPOSER"
        assertTrue(
            "expanded reader reply/react actions must require a live message and an active shared composer",
            "if (surfaces.expandedFullView && !deleted)" in body &&
                expandedComposerGate in body &&
                "canReply = canUseExpandedComposer" in fullScreenCall &&
                "canReact = canUseExpandedComposer" in fullScreenCall,
        )
        assertTrue(
            "tapping Reply in the expanded reader should keep the reader open and focus the in-reader composer",
            Regex("""onReply\s*=\s*\{\s*if \(canUseExpandedComposer\) \{\s*beginReply\(\)\s*}\s*}""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(fullScreenCall),
        )
    }

    /**
     * The reader's composer hands an accepted send back to the conversation: it closes only its own reader
     * session, after the cleared composer reached the keyboard, and every row layer forwards the conversation's
     * accepted-send reveal (#3268).
     */
    @Test
    fun expandedReaderSendClosesItsSessionAndRevealsThroughTheConversation() {
        val body = messageBubbleSource().readText().functionBody("MessageBubble")
        val fullScreenCall =
            body
                .substringAfter("MessageFullScreenView(")
                .substringBefore("if (surfaces.emojiPickerOpen")
        assertTrue(
            "the reader composer must mark its own session when a send is accepted",
            "val readerSession = surfaces.expandedFullViewSession" in body &&
                "onAfterSend = { surfaces.finishExpandedReaderSend(readerSession) }" in fullScreenCall,
        )
        assertTrue(
            "the reader must close through the deferred dismissal that reveals through the conversation",
            "ReaderSendDismissal(surfaces, onReaderSendAccepted)" in body,
        )
        val state = messageBubbleSource().resolveSibling("MessageBubbleState.kt").readText()
        val dismissal = state.substringAfter("internal fun ReaderSendDismissal(").substringBefore("\n}\n")
        assertTrue(
            "closing in the clearing frame lets the keyboard's composing word restore the sent text",
            "withFrameNanos" in dismissal && "closeReaderAfterAcceptedSend(session)" in dismissal,
        )
        assertFalse(
            "Read more must open a new reader session rather than flipping the flag directly",
            "surfaces.expandedFullView = true" in body,
        )

        val timelineRow = conversationSource("TimelineRow.kt").readText()
        val rowBubble = conversationSource("TimelineRowTtsHighlight.kt").readText()
        assertTrue(
            "TimelineRow and its bubble wrapper must forward the accepted-send reveal to MessageBubble",
            "onReaderSendAccepted = onReaderSendAccepted" in timelineRow &&
                "onReaderSendAccepted = onReaderSendAccepted" in rowBubble,
        )

        val screen = conversationSource("ConversationScreen.kt").readText()
        val readerHandoff = screen.substringAfter("onReaderSendAccepted = {").substringBefore("},")
        assertTrue(
            "the conversation must reveal a reader send through the same path as its main composer",
            "acceptedSendRevealedTranscript = true" in readerHandoff && "revealSentMessage()" in readerHandoff,
        )
    }

    /** One composer gate and mention picker feed both the main composer and the reader. */
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

    /** A reader can share text ownership without overwriting the conversation's measured flight endpoints. */
    @Test
    fun expandedReaderCannotPublishConversationMotionGeometry() {
        val body = messageBubbleSource().readText().functionBody("MessageBubble")
        val readerStart = body.indexOf("MessageFullScreenView(")
        require(readerStart >= 0) { "Missing expanded reader" }
        val reader = body.kotlinBlockFrom(body.indexOf('(', readerStart), "reader arguments", '(', ')')
        val local = reader.indexOf("LocalComposerSendMotion provides null")
        require(local >= 0) { "Reader must isolate the conversation motion local" }
        val providerStart = reader.lastIndexOf("CompositionLocalProvider(", local)
        require(providerStart >= 0) { "Missing reader local provider" }
        val parametersStart = reader.indexOf('(', providerStart)
        val parameters = reader.kotlinBlockFrom(parametersStart, "reader motion local", '(', ')')
        assertTrue("Reader must provide a null motion owner", "LocalComposerSendMotion provides null" in parameters)
        val isolated =
            reader.kotlinBlockFrom(parametersStart + parameters.length, "isolated reader body", searchForOpening = true)
        assertTrue("Reader composer must be inside its isolated local", "ComposerBar(" in isolated)
        assertFalse("Reader disposal must not clear the conversation flight", "composerSendMotion.clear()" in reader)
        assertFalse("Reader disposal must not retire the conversation owner", "composerSendMotion.dispose()" in reader)
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
