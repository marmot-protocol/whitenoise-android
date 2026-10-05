package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.OptimisticGroupRosterMutation
import dev.ipf.whitenoise.android.state.PendingGroupMembershipActivity
import dev.ipf.whitenoise.android.ui.conversation.PendingGroupMembershipRow
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
class PendingGroupMembershipScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun pendingMembersLight() {
        render(dark = false)
        rule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/pending_group_members_light.png")
    }

    @Test
    fun pendingMembersDark() {
        render(dark = true)
        rule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/pending_group_members_dark.png")
    }

    @Test
    fun pendingMembersAmoledRtlLargeText() {
        render(dark = true, amoled = true, rtl = true, fontScale = 1.6f)
        rule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/pending_group_members_amoled_rtl_large.png")
    }

    @Test
    fun pendingMembersNarrowLongNames() {
        render(dark = false, narrow = true)
        rule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/pending_group_members_narrow.png")
    }

    private fun render(
        dark: Boolean,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        narrow: Boolean = false,
    ) {
        rule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = fontScale) {
                val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Surface(modifier = Modifier.width(if (narrow) 240.dp else 360.dp).testTag(TAG)) {
                        Column {
                            PendingGroupMembershipRow(
                                PendingGroupMembershipActivity(
                                    "invite",
                                    OptimisticGroupRosterMutation.Invite(listOf("Ada", "Grace")),
                                ),
                                displayName = { if (narrow) "$it Example of a longer contact nickname" else it },
                            )
                            PendingGroupMembershipRow(
                                PendingGroupMembershipActivity("remove", OptimisticGroupRosterMutation.Remove("Linus")),
                                displayName = { it },
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "pending-group-members"
    }
}
