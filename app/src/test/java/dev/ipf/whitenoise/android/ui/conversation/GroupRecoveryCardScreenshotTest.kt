package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRejoinInvitationFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupRecoveryCardScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Captures the recovery summary and the evidence shown before a state-replacing rejoin. */
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
        composeRule.onNodeWithText("Review invitation from Marmot Friend · epoch 9").performClick()
        composeRule
            .onNodeWithText(
                "Only rejoin if you trust this invitation. " +
                    "A newer group version alone does not make it trustworthy.",
            ).assertExists()
        composeRule.onNodeWithText("Invited by Marmot Friend\nnpub1trustedinviter").assertExists()
        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/group_recovery_confirmation_light.png",
        )
    }

    /** Captures the non-blocking retry state for an advisory recovery read failure. */
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

    /** A healthy group — the state a successful create lands in — renders no recovery chrome at all. */
    @Test
    fun aHealthyGroupShowsNeitherTheFailureCopyNorRetry() {
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupRecoveryCard(
                    status =
                        GroupRecoveryStatusFfi(
                            groupIdHex = "group",
                            automaticRecoveryFailed = false,
                            pendingReinvites = 0u,
                            failedReinvites = 0u,
                            rejoinInvitations = emptyList(),
                        ),
                    busy = false,
                    inviterName = { it },
                    inviterIdentity = { it },
                    onConfirm = {},
                    onDecline = {},
                    readFailed = false,
                    onRetry = {},
                    modifier = Modifier.width(360.dp).testTag("group-recovery-card"),
                )
            }
        }

        composeRule.onNodeWithTag("group-recovery-card").assertDoesNotExist()
        composeRule.onNodeWithText("Couldn’t check group recovery. Try again.").assertDoesNotExist()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    /** Dark recovery keeps the native retry action visible within the prototype notice frame. */
    @Test
    fun recoveryRetryDark() = captureRetry(dark = true, amoled = false, largeRtl = false)

    /** Wide RTL and large typography retain the fixed notice maximum and an actionable retry. */
    @Test
    @Config(qualifiers = "en-w600dp-h900dp-mdpi")
    fun recoveryRetryAmoledLargeRtl() = captureRetry(dark = true, amoled = true, largeRtl = true)

    /** Renders the retry state and records its screenshot baseline. */
    private fun captureRetry(
        dark: Boolean,
        amoled: Boolean,
        largeRtl: Boolean,
    ) {
        var retries = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    GroupRecoveryCard(
                        status = null,
                        busy = false,
                        inviterName = { it },
                        inviterIdentity = { it },
                        onConfirm = {},
                        onDecline = {},
                        readFailed = true,
                        onRetry = { retries++ },
                        modifier = Modifier.width(if (largeRtl) 600.dp else 360.dp).testTag("group-recovery-card"),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
        val name = if (largeRtl) "amoled_large_rtl" else "dark"
        composeRule
            .onNodeWithTag("group-recovery-card")
            .captureRoboImage("src/test/snapshots/group_recovery_retry_$name.png")
    }
}
