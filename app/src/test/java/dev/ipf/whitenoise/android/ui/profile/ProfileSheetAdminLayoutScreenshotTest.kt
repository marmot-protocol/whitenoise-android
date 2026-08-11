package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.group.GroupMemberMenuAction
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
class ProfileSheetAdminLayoutScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun admin() =
        capture(
            name = "profile_sheet_admin_layout_admin_dark",
            actions = listOf(GroupMemberMenuAction.RevokeAdmin, GroupMemberMenuAction.RemoveMember),
        )

    @Test
    fun pendingRevoke() =
        capture(
            name = "profile_sheet_admin_layout_pending_revoke_dark",
            actions = listOf(GroupMemberMenuAction.RevokeAdmin, GroupMemberMenuAction.RemoveMember),
            pendingAction = GroupMemberMenuAction.RevokeAdmin,
        )

    @Test
    fun reconciledNonAdmin() =
        capture(
            name = "profile_sheet_admin_layout_non_admin_dark",
            actions = listOf(GroupMemberMenuAction.GrantAdmin, GroupMemberMenuAction.RemoveMember),
        )

    @Test
    fun pendingGrant() =
        capture(
            name = "profile_sheet_admin_layout_pending_grant_dark",
            actions = listOf(GroupMemberMenuAction.GrantAdmin, GroupMemberMenuAction.RemoveMember),
            pendingAction = GroupMemberMenuAction.GrantAdmin,
        )

    @Test
    fun adminAtLargeFontScale() =
        capture(
            name = "profile_sheet_admin_layout_admin_large_font_dark",
            actions = listOf(GroupMemberMenuAction.RevokeAdmin, GroupMemberMenuAction.RemoveMember),
            fontScale = 2f,
        )

    private fun capture(
        name: String,
        actions: List<GroupMemberMenuAction>,
        pendingAction: GroupMemberMenuAction? = null,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Surface(modifier = Modifier.width(320.dp).testTag(SCREENSHOT_TAG)) {
                        ProfileSheetAdminActionRows(
                            actions = actions,
                            pendingAction = pendingAction,
                            busy = pendingAction != null,
                            onGrantAdmin = {},
                            onRevokeAdmin = {},
                            onRemoveMember = {},
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/$name.png")
    }

    private companion object {
        const val SCREENSHOT_TAG = "profile-sheet-admin-layout-screenshot"
    }
}
