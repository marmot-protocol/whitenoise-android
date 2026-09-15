package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises saved-state reconstruction without persisting audio bytes, source paths, or recorder handles. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class VoiceReviewRecoveryTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** A real save/restore loses the local clip but exposes an actionable, localized unavailable-review notice. */
    @Test
    fun finalizedReviewRecreationShowsUnavailableNoticeAndExplicitRecordAgain() {
        val restoration = StateRestorationTester(composeRule)
        var marker: VoiceReviewRecoveryMarker? = null
        var finalizeTake: (() -> Unit)? = null
        var recordedAgain = 0
        restoration.setContent {
            val current = rememberVoiceReviewRecoveryMarker("personal", "chat-a")
            var ownsTake by remember { mutableStateOf(false) }
            SideEffect {
                marker = current
                finalizeTake = {
                    ownsTake = true
                    current.updatePresence(true)
                }
            }
            WhiteNoiseTheme(darkTheme = true, fontScale = 1.6f) {
                if (current.hasReview && !ownsTake) {
                    VoiceReviewUnavailableNotice(
                        onDismiss = { current.updatePresence(false) },
                        onRecordAgain = {
                            current.consume {
                                recordedAgain += 1
                                current.updatePresence(false)
                            }
                        },
                    )
                }
            }
        }
        composeRule.runOnIdle { requireNotNull(finalizeTake).invoke() }
        restoration.emulateSavedInstanceStateRestore()
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.voice_review_unavailable_title)).assertIsDisplayed()
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/voice_review_unavailable_dark_large.png")
        composeRule.onNodeWithText(context.getString(R.string.voice_record_again)).performClick()
        composeRule.runOnIdle {
            assertEquals(1, recordedAgain)
            assertFalse(requireNotNull(marker).hasReview)
        }
    }

    /** The saved data is only a hash and a boolean, and neither another account nor chat receives its presence. */
    @Test
    fun savedPresenceCannotCrossAccountOrConversation() {
        val owner = voiceReviewRecoveryOwner("private-account-label", "chat-a")
        val marker = VoiceReviewRecoveryMarker(owner, true)
        val saver = voiceReviewRecoverySaver(owner)
        val saved = with(saver) { requireNotNull(SaverScope { true }.save(marker)) }
        assertEquals(listOf(owner, true), saved)
        assertEquals(64, owner.length)
        assertFalse(saved.toString().contains("private-account-label"))
        assertFalse(saved.toString().contains("chat-a"))
        assertTrue(requireNotNull(saver.restore(saved)).hasReview)
        for (other in listOf(
            voiceReviewRecoveryOwner("other-account", "chat-a"),
            voiceReviewRecoveryOwner("private-account-label", "chat-b"),
        )) {
            assertFalse(requireNotNull(voiceReviewRecoverySaver(other).restore(saved)).hasReview)
        }
    }

    /** A detached recovery callback cannot start a replacement recorder or clear a newer owner's warning. */
    @Test
    fun detachedMarkerRejectsCapturedRecordAgain() {
        val marker = VoiceReviewRecoveryMarker("owner", true)
        var starts = 0
        marker.release()
        marker.consume { starts += 1 }
        marker.updatePresence(false)
        assertEquals(0, starts)
        assertTrue(marker.hasReview)
    }
}
