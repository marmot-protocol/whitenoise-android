package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationFixtureSeedProgress
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The debug-only seed dialog before a seed starts, while one runs, and after it was stopped. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationFixtureSeedDialogScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val targets =
        listOf(
            ConversationFixtureTarget("a1b2c3d4e5f60718", "Paging Fixture"),
            ConversationFixtureTarget("0c003bfa9e8d7c6b", "0c003bfa"),
            ConversationFixtureTarget("f00dcafe12345678", "Test it well"),
        )

    /** Idle: a target picked, the default count, Start enabled and Close offered. */
    @Test
    fun idleLight() {
        render(selected = targets.first().groupIdHex)
        composeRule.onNodeWithTag("developer.seed_fixture.start").assertIsEnabled()
        composeRule.onNodeWithText(context.getString(R.string.close)).assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/seed_fixture_dialog_idle_light.png")
    }

    /** Running: the progress line above the list, inputs disabled, Cancel instead of Close. */
    @Test
    fun runningAmoled() {
        render(
            selected = targets.first().groupIdHex,
            progress = ConversationFixtureSeedProgress(sent = 137, failed = 2, total = 300),
            running = true,
            dark = true,
        )
        composeRule.onNodeWithTag("developer.seed_fixture.start").assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.seed_fixture_progress, 137, 300, 2))
            .assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/seed_fixture_dialog_running_amoled.png")
    }

    /** Stopped: the line says where the seed ended and the dialog can be closed. */
    @Test
    fun stoppedLight() {
        render(
            selected = targets.first().groupIdHex,
            progress = ConversationFixtureSeedProgress(sent = 41, failed = 0, total = 300),
            stopped = true,
        )
        composeRule
            .onNodeWithText(context.getString(R.string.seed_fixture_stopped, 41, 300))
            .assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/seed_fixture_dialog_stopped_light.png")
    }

    /** Nothing to seed: the empty-account note replaces the target list and Start stays disabled. */
    @Test
    fun noTargetsLight() {
        render(targets = emptyList(), selected = null)
        composeRule.onNodeWithText(context.getString(R.string.seed_fixture_no_groups)).assertIsDisplayed()
        composeRule.onNodeWithTag("developer.seed_fixture.start").assertIsNotEnabled()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/seed_fixture_dialog_no_targets_light.png")
    }

    /** Composes the stateless dialog content in the requested theme with the given state. */
    private fun render(
        targets: List<ConversationFixtureTarget> = this.targets,
        selected: String?,
        progress: ConversationFixtureSeedProgress? = null,
        stopped: Boolean = false,
        running: Boolean = false,
        dark: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = dark) {
                ConversationFixtureSeedDialogContent(
                    targets = targets,
                    selected = selected,
                    count = TextFieldState("300"),
                    progress = progress,
                    stopped = stopped,
                    running = running,
                    onSelect = {},
                    onStart = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
    }
}
