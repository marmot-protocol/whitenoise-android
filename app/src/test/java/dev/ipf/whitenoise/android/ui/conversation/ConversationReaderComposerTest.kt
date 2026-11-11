package dev.ipf.whitenoise.android.ui.conversation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.window.Dialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationReaderComposerTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun mediaAcceptanceUsesItsSubmittingReaderHandoff() {
        val host = ConversationReaderComposer()
        var accepted = 0
        host.attach(ConversationReaderComposer.Session({ accepted++ }, {}))
        val complete = host.captureSendAccepted()
        assertEquals(0, accepted)
        assertEquals(true, complete())
        assertEquals(1, accepted)
    }

    @Test
    fun lateMediaAcceptanceCannotDismissOrScrollAwayAReplacementReader() {
        val host = ConversationReaderComposer()
        var accepted = 0
        val first = ConversationReaderComposer.Session({ accepted++ }, {})
        val replacement = ConversationReaderComposer.Session({ accepted++ }, {})
        host.attach(first)
        val complete = host.captureSendAccepted()
        host.detach(first)
        host.attach(replacement)
        assertEquals(true, complete())
        assertEquals(0, accepted)
        assertSame(replacement, host.session)
    }

    @Test
    fun mediaAcceptanceAfterClosingItsReaderCanRevealTheTimeline() {
        val host = ConversationReaderComposer()
        val reader = ConversationReaderComposer.Session({}, {})
        host.attach(reader)
        val complete = host.captureSendAccepted()
        host.detach(reader)
        assertEquals(false, complete())
    }

    @Test
    fun mediaSubmittedWithoutAReaderCannotDismissANewReader() {
        val host = ConversationReaderComposer()
        val complete = host.captureSendAccepted()
        var accepted = 0
        val reader = ConversationReaderComposer.Session({ accepted++ }, {})
        host.attach(reader)
        assertEquals(true, complete())
        assertEquals(0, accepted)
        assertSame(reader, host.session)
    }

    @Test
    fun dictatedSendUsesTheSameAcceptedReaderHandoff() {
        val host = ConversationReaderComposer()
        var accepted = 0
        val reader = ConversationReaderComposer.Session({ accepted++ }, {})
        host.attach(reader)
        host.onDictationSendRequested()
        assertEquals(0, accepted)
        assertEquals(true, host.onDictationSendAccepted())
        assertEquals(1, accepted)
    }

    @Test
    fun lateDictatedSendCannotDismissOrScrollAwayANewerReader() {
        val host = ConversationReaderComposer()
        var accepted = 0
        val first = ConversationReaderComposer.Session({ accepted++ }, {})
        val replacement = ConversationReaderComposer.Session({ accepted++ }, {})
        host.attach(first)
        host.onDictationSendRequested()
        host.detach(first)
        host.attach(replacement)
        assertEquals(true, host.onDictationSendAccepted())
        assertEquals(0, accepted)
        assertSame(replacement, host.session)
    }

    @Test
    fun dictatedSendWithoutAnOpenReaderUsesTheNormalTimelineReveal() {
        val host = ConversationReaderComposer()
        val reader = ConversationReaderComposer.Session({}, {})
        host.attach(reader)
        host.onDictationSendRequested()
        host.detach(reader)
        assertEquals(false, host.onDictationSendAccepted())
    }

    @Test
    fun externalDictatedSendDoesNotScrollAwayAnUnrelatedOpenReader() {
        val host = ConversationReaderComposer()
        var accepted = 0
        host.attach(ConversationReaderComposer.Session({ accepted++ }, {}))
        assertEquals(true, host.onDictationSendAccepted())
        assertEquals(0, accepted)
    }

    @Test
    fun staleReaderDisposalCannotDetachItsReplacement() {
        val host = ConversationReaderComposer()
        val first = ConversationReaderComposer.Session({}, {})
        val second = ConversationReaderComposer.Session({}, {})
        host.attach(first)
        host.attach(second)
        host.detach(first)
        assertSame(second, host.session)
        host.detach(second)
        assertEquals(null, host.session)
    }

    @Test
    fun acceptedSendRemainsPinnedToTheReaderThatSubmittedIt() {
        val host = ConversationReaderComposer()
        var firstSends = 0
        var secondSends = 0
        val first = ConversationReaderComposer.Session({ firstSends++ }, {})
        val second = ConversationReaderComposer.Session({ secondSends++ }, {})
        host.attach(first)
        val accepted = requireNotNull(host.session).onAfterSend
        host.attach(second)
        accepted()
        assertEquals(1, firstSends)
        assertEquals(0, secondSends)
        assertSame(second, host.session)
    }

    @Test
    fun movingIntoAndOutOfADialogKeepsExactlyOneComposerAndItsRememberedState() {
        readerRoundTrip(forcedClose = false)
    }

    @Test
    fun externallyDeletedReaderReturnsItsComposerBeforeRemovingTheDialog() {
        readerRoundTrip(forcedClose = true)
    }

    private fun readerRoundTrip(forcedClose: Boolean) {
        val host = ConversationReaderComposer()
        val open = mutableStateOf(false)
        val deleted = mutableStateOf(false)
        var remembered: Any? = null
        var disposals = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    LaunchedEffect(deleted.value) {
                        if (deleted.value) host.session?.onDismiss()
                    }
                    host.Host {
                        val identity = remember { Any() }
                        remembered = identity
                        DisposableEffect(identity) { onDispose { disposals++ } }
                        Text("Full composer")
                    }
                    if (open.value) {
                        Dialog(onDismissRequest = { open.value = false }) {
                            host.Reader(onAfterSend = {}, onDismiss = { open.value = false })
                        }
                    }
                }
            }
        }
        composeRule.onAllNodesWithText("Full composer").assertCountEquals(1)
        val original = composeRule.runOnIdle { requireNotNull(remembered) }
        composeRule.runOnIdle { open.value = true }
        composeRule.onAllNodesWithText("Full composer").assertCountEquals(1)
        composeRule.runOnIdle {
            assertSame(original, remembered)
            assertEquals(0, disposals)
        }
        composeRule.runOnIdle {
            if (forcedClose) deleted.value = true else requireNotNull(host.session).onDismiss()
        }
        composeRule.onAllNodesWithText("Full composer").assertCountEquals(1)
        composeRule.runOnIdle {
            assertSame(original, remembered)
            assertEquals(0, disposals)
            assertEquals(null, host.session)
        }
        composeRule.runOnIdle { open.value = true }
        composeRule.onAllNodesWithText("Full composer").assertCountEquals(1)
        composeRule.runOnIdle {
            assertSame(original, remembered)
            assertNotSame(null, host.session)
        }
    }
}
