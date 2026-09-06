package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.GroupRosterLoadState
import dev.ipf.whitenoise.android.ui.group.GroupDetailsAddMemberAction
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baselines for the Add member quick action's first-frame contract while
 * the authoritative roster is still LOADING: a warm member seed renders it
 * enabled, a cold open with no membership signal renders it disabled.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class GroupAddMemberActionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Seeded self-member while LOADING renders the enabled action. */
    @Test
    fun seededMemberWhileRosterLoadsLight() {
        render(seededSelfMember = true)
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/group_add_member_action_seeded_loading_light.png")
    }

    /** A cold open while LOADING keeps the disabled action. */
    @Test
    fun coldOpenWhileRosterLoadsLight() {
        render(seededSelfMember = false)
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/group_add_member_action_cold_loading_light.png")
    }

    private fun render(seededSelfMember: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    Box(modifier = Modifier.padding(16.dp).testTag(TAG)) {
                        GroupDetailsAddMemberAction(
                            visible = true,
                            rosterState = GroupRosterLoadState.LOADING,
                            mutationsBlocked = false,
                            onClick = {},
                            seededSelfMember = seededSelfMember,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val TAG = "add-member-action-screenshot"
    }
}
