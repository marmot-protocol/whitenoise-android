package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationMode
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTarget
import dev.ipf.whitenoise.android.ui.conversation.composer.DictationControlFacts
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Recovery handed to the composer hides the floating capsule while the session stays non-idle. The
 * capsule still leaves through the same shrink as any other ended session instead of vanishing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w240dp-h160dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DictationRecoveryHandoffExitScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var originalAnimatorScale = 1f

    @Before
    fun enableTestMotion() {
        originalAnimatorScale =
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @After
    fun restoreMotionPolicy() {
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            originalAnimatorScale,
        )
    }

    @Test
    fun recoveryHandoffShrinksTheCapsuleAway() {
        val target =
            ConversationDictationTarget("account", "group", TextFieldValue(), 0, ConversationDictationMode.InApp)
        val listening = ConversationDictationState.Listening(1L, target, startedAtElapsedMillis = 0L)
        val recovery = ConversationDictationState.Failed(1L, target, ConversationDictationFailure.Unknown, "hello")
        var owner by mutableStateOf(ConversationDictationControlOwner.Persistent)
        var state by mutableStateOf<ConversationDictationState>(listening)
        var facts by mutableStateOf(facts(recoveryHandedToComposer = false))
        var painted: ConversationDictationState? = null
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                        DictationControlExit(owner, state, facts, motionAllowed = true) { shown, _, modifier ->
                            painted = shown
                            Box(
                                modifier
                                    .size(width = 160.dp, height = 48.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Text("Listening") }
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        composeRule.runOnIdle {
            owner = ConversationDictationControlOwner.Hidden
            state = recovery
            facts = facts(recoveryHandedToComposer = true)
        }
        composeRule.mainClock.advanceTimeBy(DICTATION_CONTROL_EXIT_MILLIS / 2L)
        assertEquals("The frozen listening capsule keeps painting while it leaves", listening, painted)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_control_exit_recovery_handoff_mid_dark.png")
    }

    private fun facts(recoveryHandedToComposer: Boolean) =
        DictationControlFacts(
            captureInProgress = false,
            deliveryInProgress = false,
            completionActionsEnabled = true,
            processingDeliveryMode = null,
            recoveryHandedToComposer = recoveryHandedToComposer,
        )
}

private const val SETTLE_MILLIS = 600L
