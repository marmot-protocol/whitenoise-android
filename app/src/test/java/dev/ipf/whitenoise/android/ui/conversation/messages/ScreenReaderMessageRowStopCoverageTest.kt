package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A message row is one screen-reader stop outside batch and text selection (#3603). Android offers only
 * the merging node's own actions, so the row must carry every action its nested controls and text leaves
 * had, while the sender, quote, edited label and reaction pills stop being separate focus targets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenReaderMessageRowStopCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rowActionsKeepMenuOrderDropUnavailableEntriesAndEndWithForwardedTextActions() {
        val performed = mutableListOf<String>()
        val forwarded = CustomAccessibilityAction("Read aloud from here") { true }
        val actions =
            messageRowCustomActions(
                listOf(
                    MessageRowAction("Reply", available = true) { performed += "reply" },
                    MessageRowAction("React", available = false) { performed += "react" },
                    MessageRowAction("Info", available = true) { performed += "info" },
                ),
                listOf(forwarded),
            )

        assertEquals(listOf("Reply", "Info", "Read aloud from here"), actions.map { it.label })
        assertTrue(actions[1].action?.invoke() == true)
        assertEquals(listOf("info"), performed)
    }

    /**
     * A long message forwards one read-aloud action per paragraph. The row keeps its own actions first, offers
     * one entry per distinct label, and stays below Compose's limit of 32 custom actions, which throws while an
     * accessibility service such as TalkBack reads the row.
     */
    @Test
    fun longMessageRowStaysBelowTheCustomActionLimit() {
        val own = (1..8).map { MessageRowAction("Action $it", available = true) {} }
        val sentences = List(40) { CustomAccessibilityAction("Choose sentence to read") { true } }
        val links = (1..40).map { CustomAccessibilityAction("Copy link: https://example.com/$it") { true } }

        val actions = messageRowCustomActions(own, sentences + links)

        assertTrue("got ${actions.size} actions", actions.size <= MAX_ROW_CUSTOM_ACTIONS)
        assertTrue(MAX_ROW_CUSTOM_ACTIONS < 32)
        assertEquals(own.map { it.label }, actions.take(own.size).map { it.label })
        assertEquals(1, actions.count { it.label == "Choose sentence to read" })
        assertEquals(actions.size, actions.map { it.label }.distinct().size)
    }

    @Test
    fun textLeavesRegisterByIdentityAndLeaveWhenRemoved() {
        val registry = MessageRowTextActions()
        val first = Any()
        val second = Any()
        val ran = mutableListOf<String>()

        registry.update(first, emptyList()) { false }
        assertTrue(registry.actions.isEmpty())

        registry.update(first, listOf("Read aloud from here")) { index ->
            ran += "first:$index"
            true
        }
        // Two paragraphs may offer the same label; each keeps its own entry.
        registry.update(second, listOf("Read aloud from here", "Copy link")) { index ->
            ran += "second:$index"
            true
        }
        assertEquals(
            listOf("Read aloud from here", "Read aloud from here", "Copy link"),
            registry.actions.map { it.label },
        )

        registry.actions[2].action?.invoke()
        assertEquals(listOf("second:1"), ran)

        registry.update(first, listOf("Read aloud from here", "Copy link")) { true }
        assertEquals(4, registry.actions.size)
        registry.remove(first)
        assertEquals(listOf("Read aloud from here", "Copy link"), registry.actions.map { it.label })
    }

    @Test
    fun mergedRowIsOneStopThatSpeaksItsChromeAndOffersEveryAction() {
        var spoke = false
        var openedProfile = false
        val bodyShown = mutableStateOf(true)
        val registry = MessageRowTextActions()

        setMergedRow(registry, bodyShown, onSpeak = { spoke = true }, onOpenProfile = { openedProfile = true })
        composeRule.waitForIdle()

        // Nothing inside the row is its own focus stop any more.
        assertEquals(0, composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        assertTrue(
            composeRule
                .onNodeWithTag(ROW)
                .fetchSemanticsNode()
                .children
                .isEmpty(),
        )

        val row = composeRule.onNodeWithTag(ROW).fetchSemanticsNode().config
        // Chrome reads as text in place, so the row speaks in rendered order.
        val spoken = row.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertEquals(listOf("Alice", "Replying to Bob: lunch?", "Sure, noon works", "Edited"), spoken)
        assertFalse(row.contains(SemanticsProperties.ContentDescription))
        assertFalse(row.contains(SemanticsActions.OnClick))

        val labels = row[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Open profile", "Read aloud from here"), labels)
        row[SemanticsActions.CustomActions][1].action?.invoke()
        assertTrue(spoke)
        row[SemanticsActions.CustomActions][0].action?.invoke()
        assertTrue(openedProfile)

        // A text leaf that leaves the row takes its forwarded actions with it.
        bodyShown.value = false
        composeRule.waitForIdle()
        assertEquals(
            listOf("Open profile"),
            composeRule
                .onNodeWithTag(ROW)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .map { it.label },
        )
    }

    @Test
    fun messageBubbleRoutesNestedControlsThroughItsRow() {
        // The merged row and its actions are exercised on the production bubble by MessageRowScreenReaderActionsTest.
        val bubble = appModuleSource("$MESSAGES/MessageBubble.kt")
        // Avatar and reaction pills, sender name and quote card all yield to the row.
        assertEquals(2, Regex("""rowOwnsSemantics = rowMerged""").findAll(bubble).count())
        assertTrue(bubble.contains(".messageRowChrome(rowMerged, appState.displayName(record.sender))"))
        assertTrue(bubble.contains("replyQuoteAccessibilityText(preview, appState)"))

        val footer = appModuleSource("$MESSAGES/MessageInlineFooter.kt")
        assertTrue(footer.contains("editedLabelTarget(it, onEditedClick)"))
        val giphy = appModuleSource("$CONVERSATION/media/RemoteGiphyMediaBubble.kt")
        assertTrue(giphy.contains("editedLabelTarget(label, footer.onEditedClick)"))
    }

    @Test
    fun everyTextRendererForwardsItsActionsToTheRow() {
        val host = appModuleSource("$CONVERSATION/TimelineRowTtsHighlight.kt")
        assertTrue(host.contains("LocalMessageRowTextActions provides rowTextActions"))
        val markdown = appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/MarkdownRenderer.kt")
        // Markdown paragraphs forward their links and one chooser over the whole message's sentences.
        assertTrue(markdown.contains("ttsSentenceRowAccessibilityActions(leafId, text.text, sentenceOwner)"))
        assertTrue(markdown.contains("ForwardTextActionsToMessageRow(rowSpeechActions + linkActions)"))
        val plain = appModuleSource("$MESSAGES/BubbleContentBlocks.kt")
        assertTrue(plain.contains("ForwardTextActionsToMessageRow(sentenceActions)"))
    }

    /** A merged row shaped like a message bubble: sender, quote, body text and edited label. */
    private fun setMergedRow(
        registry: MessageRowTextActions,
        bodyShown: MutableState<Boolean>,
        onSpeak: () -> Unit,
        onOpenProfile: () -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMessageRowTextActions provides registry) {
                Column(
                    Modifier.testTag(ROW).semantics(mergeDescendants = true) {
                        customActions =
                            messageRowCustomActions(
                                listOf(MessageRowAction("Open profile", true, onOpenProfile)),
                                registry.actions,
                            )
                    },
                ) {
                    Text(
                        "Alice",
                        Modifier
                            .messageRowChrome(rowOwnsSemantics = true, label = "Alice")
                            .clickable(onClick = onOpenProfile),
                    )
                    Box(
                        Modifier
                            .size(24.dp)
                            .messageRowChrome(rowOwnsSemantics = true, label = "Replying to Bob: lunch?")
                            .clickable {},
                    )
                    if (bodyShown.value) {
                        ForwardTextActionsToMessageRow(
                            listOf(
                                CustomAccessibilityAction("Read aloud from here") {
                                    onSpeak()
                                    true
                                },
                            ),
                        )
                        Text("Sure, noon works")
                    }
                    Text("Edited", Modifier.editedLabelTarget("Edited") {})
                }
            }
        }
    }

    private companion object {
        const val ROW = "row"
        const val CONVERSATION = "src/main/java/dev/ipf/whitenoise/android/ui/conversation"
        const val MESSAGES = "$CONVERSATION/messages"
    }
}
