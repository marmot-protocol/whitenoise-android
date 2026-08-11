package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.group.GroupMemberMenuAction
import dev.ipf.whitenoise.android.ui.profile.ProfileSheetAdminActionRows
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual contract for profile-sheet group-admin moderation action rows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ProfileSheetAdminActionRowsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pendingGrantAdminActionDark() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                    ProfileSheetAdminActionRows(
                        actions =
                            listOf(
                                GroupMemberMenuAction.GrantAdmin,
                                GroupMemberMenuAction.RemoveMember,
                            ),
                        pendingAction = GroupMemberMenuAction.GrantAdmin,
                        busy = true,
                        onGrantAdmin = {},
                        onRevokeAdmin = {},
                        onRemoveMember = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/profile_admin_action_rows_pending_grant_dark.png")
    }

    private companion object {
        const val TAG = "profile-admin-action-rows"
    }
}
