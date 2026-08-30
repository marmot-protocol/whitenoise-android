package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class QuickAccountSwitchTransitionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Captures the light first and midpoint account-switch frames. */
    @Test
    fun firstAndMidpointFramesLight() {
        captureFrames(darkTheme = false, amoled = false, themeName = "light")
    }

    /** Captures the AMOLED first and midpoint account-switch frames. */
    @Test
    fun firstAndMidpointFramesAmoled() {
        captureFrames(darkTheme = true, amoled = true, themeName = "amoled")
    }

    /** Drives the account cue through deterministic first and midpoint captures. */
    private fun captureFrames(
        darkTheme: Boolean,
        amoled: Boolean,
        themeName: String,
    ) {
        var transition by mutableStateOf<QuickAccountSwitchTransition?>(request())
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(
                    modifier = Modifier.fillMaxSize().testTag(ROOT_TAG),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        TargetAccountChatList()
                        QuickAccountSwitchTransitionOverlay(
                            transition = transition,
                            visible = transition?.phase == QuickAccountSwitchPhase.AwaitingTarget,
                            onFinished = { requestId ->
                                if (transition?.requestId == requestId) transition = null
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/account_switch_transition_first_$themeName.png")

        composeRule.runOnUiThread {
            transition = transition?.copy(phase = QuickAccountSwitchPhase.RevealingTarget)
        }
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(QUICK_ACCOUNT_SWITCH_TRANSITION_MILLIS.toLong() / 2L)
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/account_switch_transition_midpoint_$themeName.png")
    }

    /** Creates the deterministic target identity used by screenshot coverage. */
    private fun request() =
        QuickAccountSwitchTransition(
            requestId = 1L,
            sourceAccountRef = "personal",
            targetAccountRef = "work",
            targetTitle = "Work",
            targetSeed = "work-account-identity",
            targetPictureUrl = null,
            motion = QuickAccountSwitchMotion.Animated,
        )

    /** Renders the already-composed target list behind the account cue. */
    @androidx.compose.runtime.Composable
    private fun TargetAccountChatList() {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Work", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Target account · 3 conversations",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            ChatPreview("Design team", "Mina: The review is ready", "2")
            ChatPreview("Release planning", "Version 0.7.0 checklist", "")
            ChatPreview("Mina", "See you tomorrow", "")
        }
    }

    /** Renders one deterministic target-account row. */
    @androidx.compose.runtime.Composable
    private fun ChatPreview(
        title: String,
        preview: String,
        unread: String,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (unread.isNotEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        unread,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }

    private companion object {
        const val ROOT_TAG = "quick-account-switch-screenshot-root"
    }
}
