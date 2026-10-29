package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Accept through the production Send button; captured ink alone must never choose the collapse clock. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
class ComposerAcceptedCollapseTest {
    @get:Rule val rule = createComposeRule()

    /** An admitted send flight animates the accepted composer collapse, and the next user edit ends that pairing. */
    @Test fun admittedTextSharesTheFlightClock() = assertAcceptance("admitted", true)

    /** With send motion disabled, acceptance clears the draft but installs no flight, so the collapse snaps. */
    @Test fun disabledMotionSnaps() = assertAcceptance("disabled", false)

    /** A reply send captures no source ink, so the accepted collapse snaps without a flight. */
    @Test fun replyingSnaps() = assertAcceptance("reply", false)

    /** Without a measured source layout, acceptance installs no flight and the collapse snaps. */
    @Test fun missingSourceGeometrySnaps() = assertAcceptance("missing", false)

    /** Cycling motion between capture and acceptance makes the captured source stale, so the collapse snaps. */
    @Test fun ownerRetiredBetweenCaptureAndAcceptanceSnaps() = assertAcceptance("retired", false)

    /** An acceptance without a presentation id installs no flight, so the collapse snaps. */
    @Test fun missingPresentationSnaps() = assertAcceptance("no-presentation", false)

    /** Sends a two-line draft via the real Send button under [scenario] and checks whether the collapse animates. */
    private fun assertAcceptance(
        scenario: String,
        expectedAnimated: Boolean,
    ) {
        val state = ComposerTextState(TextFieldValue("First line\nSecond line"))
        var accepted: ((String?) -> Unit)? = null
        lateinit var motion: ComposerSendMotion
        rule.setContent {
            WhiteNoiseTheme {
                motion = remember { ComposerSendMotion().apply { enabled = scenario != "disabled" } }
                ComposerSendMotionHost(motion, motionEnabled = true) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f))
                        ComposerBar(
                            replyingTo = replyRecord().takeIf { scenario == "reply" },
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            textState = state,
                            onSend = { _, _ -> error("Typed acceptance expected") },
                            onSendWithPresentation = { _, callback -> accepted = callback },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { if (scenario == "missing") motion.sourceLayout = null }
        rule.mainClock.autoAdvance = false
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        rule.runOnIdle {
            assertNotNull(accepted)
            if (scenario == "retired") {
                motion.enabled = false
                motion.enabled = true
            }
            accepted!!.invoke(if (scenario == "no-presentation") null else "accepted")
            assertEquals("", state.valueState.value.text)
            assertTrue(state.collapsedBySend)
            assertEquals(expectedAnimated, state.animatesAcceptedCollapse(motion.flight))
            assertEquals(expectedAnimated, motion.flight != null)
            state.updateValueFromUser(TextFieldValue("Next draft"))
            assertFalse(state.animatesAcceptedCollapse(motion.flight))
            assertEquals("Next draft", state.valueState.value.text)
        }
    }

    /** A minimal received message for the composer to reply to. */
    private fun replyRecord() =
        AppMessageRecordFfi(
            messageIdHex = "1".repeat(64),
            direction = "received",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "Reply",
            contentTokens = EMPTY_MARKDOWN_DOCUMENT,
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )
}
