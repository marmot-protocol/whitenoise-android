package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRejoinInvitationFfi
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
class GroupRecoveryCardScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun invitationReviewShowsAuthenticatedInviterAndTrustWarning() {
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupRecoveryCard(
                    status =
                        GroupRecoveryStatusFfi(
                            groupIdHex = "group",
                            automaticRecoveryFailed = true,
                            pendingReinvites = 1u,
                            failedReinvites = 1u,
                            rejoinInvitations =
                                listOf(
                                    GroupRejoinInvitationFfi(
                                        welcomeIdHex = "welcome",
                                        welcomerAccountIdHex = "welcomer",
                                        epoch = 9u,
                                        localStateToken = "token",
                                    ),
                                ),
                        ),
                    busy = false,
                    inviterName = { "Marmot Friend" },
                    inviterIdentity = { "npub1trustedinviter" },
                    onConfirm = {},
                    onDecline = {},
                    modifier = Modifier.width(360.dp).testTag("group-recovery-card"),
                )
            }
        }

        composeRule.onNodeWithTag("group-recovery-card").captureRoboImage(
            "src/test/snapshots/group_recovery_card_light.png",
        )
        composeRule.onNodeWithText("Review rejoin invitation").performClick()
        composeRule
            .onNodeWithText(
                "Only rejoin if you trust this invitation. " +
                    "A newer group version alone does not make it trustworthy.",
            ).assertExists()
        composeRule.onNodeWithText("Marmot Friend", substring = true).assertExists()
        composeRule.onNodeWithText("npub1trustedinviter", substring = true).assertExists()
        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/group_recovery_confirmation_light.png",
        )
    }

    @Test
    fun failedStatusReadOffersRetryWithoutHidingTheConversation() {
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupRecoveryCard(
                    status = null,
                    busy = false,
                    inviterName = { it },
                    inviterIdentity = { it },
                    onConfirm = {},
                    onDecline = {},
                    readFailed = true,
                    onRetry = {},
                    modifier = Modifier.width(360.dp).testTag("group-recovery-card"),
                )
            }
        }

        composeRule.onNodeWithText("Couldn’t check group recovery. Try again.").assertExists()
        composeRule.onNodeWithText("Retry").assertExists()
        composeRule.onNodeWithTag("group-recovery-card").captureRoboImage(
            "src/test/snapshots/group_recovery_retry_light.png",
        )
    }
}
