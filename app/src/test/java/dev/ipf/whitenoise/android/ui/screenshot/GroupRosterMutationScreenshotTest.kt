package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.group.GroupMemberMutationStatus
import dev.ipf.whitenoise.android.ui.group.PendingGroupInviteRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual contract for the targeted progress shown by group roster mutations. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class GroupRosterMutationScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pendingGroupRosterMutationsDark() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                    Column {
                        PendingGroupInviteRow(
                            title = "Ada Lovelace",
                            subtitle = "Invite pending: npub1ada…4x7q",
                            avatarSeed = "ada",
                            avatarUrl = null,
                            onClick = {},
                        )
                        ContactRow(
                            title = "Grace Hopper",
                            subtitle = "npub1grace…9k2m",
                            avatarSeed = "grace",
                            avatarUrl = null,
                            trailing = {
                                GroupMemberMutationStatus(isAdmin = true, inProgress = true)
                            },
                        )
                        ContactRow(
                            title = "Linus Torvalds",
                            subtitle = "npub1linus…7p3v",
                            avatarSeed = "linus",
                            avatarUrl = null,
                            trailing = {
                                GroupMemberMutationStatus(isAdmin = false, inProgress = true)
                            },
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/group_roster_mutations_dark.png")
    }

    @Test
    fun pendingInviteRowPreservesCopyAction() {
        var copyCount = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                PendingGroupInviteRow(
                    title = "Ada Lovelace",
                    subtitle = "Invite pending: npub1ada…4x7q",
                    avatarSeed = "ada",
                    avatarUrl = null,
                    onClick = { copyCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Ada Lovelace").performClick()

        composeRule.runOnIdle { assertEquals(1, copyCount) }
    }

    private companion object {
        const val TAG = "group-roster-mutations"
    }
}
