package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.audio.ConversationDictationComposerAccess
import dev.ipf.whitenoise.android.audio.ConversationDictationComposerPhase
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unresolved safety conditions can be explained inline, never through a recovery choice dialog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationDictationRecoveryStatusTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun nativeClosureStatusHasNoKeepDiscardOrSendDialog() {
        render(ConversationDictationComposerPhase.ClosingMicrophone)
        composeRule.onNodeWithTag("dictation-recovery-status").assertIsDisplayed()
        assertNoDecisions()
    }

    @Test
    fun unknownAudioStatusRemainsNonmodalInLargeRtlDarkTheme() {
        render(ConversationDictationComposerPhase.AudioStateUnavailable, rtl = true)
        composeRule.onNodeWithTag("dictation-recovery-status").assertIsDisplayed()
        assertNoDecisions()
    }

    @Test
    fun retryProgressHasNoModalOrDestructiveChoices() {
        render(ConversationDictationComposerPhase.Transcribing)
        composeRule.onNodeWithText("Transcribing…").assertIsDisplayed()
        assertNoDecisions()
    }

    private fun assertNoDecisions() {
        composeRule.onNode(isDialog()).assertDoesNotExist()
        composeRule.onNodeWithText("Keep for later").assertDoesNotExist()
        composeRule.onNodeWithText("Discard").assertDoesNotExist()
        composeRule.onNodeWithText("Send recognized text").assertDoesNotExist()
    }

    private fun render(
        phase: ConversationDictationComposerPhase,
        rtl: Boolean = false,
    ) {
        val access = ConversationDictationComposerAccess(1L, 1L, phase, ConversationDictationFailure.NoMatch)
        composeRule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = rtl, amoled = rtl, fontScale = if (rtl) 2f else 1f) {
                    Surface(Modifier.width(360.dp)) { ConversationDictationRecoveryStatus(access) }
                }
            }
        }
    }
}
