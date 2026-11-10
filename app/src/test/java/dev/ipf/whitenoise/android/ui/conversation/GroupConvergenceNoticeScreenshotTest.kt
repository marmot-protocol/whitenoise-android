package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The recovery card's stopped-syncing notice: a parked chat with Retry, the retry in progress, and a retry that did
 * not help, across light, dark, AMOLED and large RTL text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupConvergenceNoticeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A parked chat explains that sends wait and offers Retry in the light theme. */
    @Test
    fun parkedLight() = capture(State.PARKED, Theme.LIGHT)

    /** The parked notice and Retry stay legible on the dark recovery surface. */
    @Test
    fun parkedDark() = capture(State.PARKED, Theme.DARK)

    /** AMOLED keeps the notice outline and an outlined Retry. */
    @Test
    fun parkedAmoled() = capture(State.PARKED, Theme.AMOLED)

    /** At 200% RTL text the parked notice wraps and Retry stays reachable. */
    @Test
    @Config(qualifiers = "en-w600dp-h900dp-mdpi")
    fun parkedLargeRtl() = capture(State.PARKED, Theme.LARGE_RTL)

    /** A running retry shows progress in place of the Retry label, light theme. */
    @Test
    fun retryingLight() = capture(State.RETRYING, Theme.LIGHT)

    /** A running retry keeps its progress label visible on the dark surface. */
    @Test
    fun retryingDark() = capture(State.RETRYING, Theme.DARK)

    /** A running retry keeps its progress label visible on AMOLED. */
    @Test
    fun retryingAmoled() = capture(State.RETRYING, Theme.AMOLED)

    /** A running retry fits its progress label at 200% RTL text. */
    @Test
    @Config(qualifiers = "en-w600dp-h900dp-mdpi")
    fun retryingLargeRtl() = capture(State.RETRYING, Theme.LARGE_RTL)

    /** A retry that left the chat parked says why above a fresh Retry, light theme. */
    @Test
    fun failedLight() = capture(State.FAILED, Theme.LIGHT)

    /** The failure reason uses the error colour legibly on the dark surface. */
    @Test
    fun failedDark() = capture(State.FAILED, Theme.DARK)

    /** The failure reason stays legible on AMOLED black. */
    @Test
    fun failedAmoled() = capture(State.FAILED, Theme.AMOLED)

    /** The failure reason and Retry both wrap and stay reachable at 200% RTL text. */
    @Test
    @Config(qualifiers = "en-w600dp-h900dp-mdpi")
    fun failedLargeRtl() = capture(State.FAILED, Theme.LARGE_RTL)

    /** A chat that is not parked, with a healthy status, shows no card and no Retry. */
    @Test
    fun aMovingChatShowsNoNotice() {
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupRecoveryCard(
                    status = healthyStatus(),
                    busy = false,
                    inviterName = { it },
                    inviterIdentity = { it },
                    onConfirm = {},
                    onDecline = {},
                    convergence = GroupConvergenceNotice(parked = false),
                    modifier = Modifier.testTag(CARD_TAG),
                )
            }
        }

        composeRule.onNodeWithTag(CARD_TAG).assertDoesNotExist()
    }

    /** Renders one notice state in one theme, checks its actions, and records the baseline. */
    private fun capture(
        state: State,
        theme: Theme,
    ) {
        var retries = 0
        val largeRtl = theme == Theme.LARGE_RTL
        composeRule.setContent {
            WhiteNoiseTheme(
                darkTheme = theme != Theme.LIGHT,
                amoled = theme == Theme.AMOLED,
                fontScale = if (largeRtl) 2f else 1f,
            ) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    GroupRecoveryCard(
                        status = healthyStatus(),
                        busy = state == State.RETRYING,
                        inviterName = { it },
                        inviterIdentity = { it },
                        onConfirm = {},
                        onDecline = {},
                        convergence =
                            GroupConvergenceNotice(
                                parked = true,
                                retrying = state == State.RETRYING,
                                retryFailure = R.string.toast_send_connection_failed.takeIf { state == State.FAILED },
                            ),
                        onRetryConvergence = { retries++ },
                        modifier = Modifier.width(if (largeRtl) 600.dp else 360.dp).testTag(CARD_TAG),
                    )
                }
            }
        }
        composeRule.onNodeWithText(PARKED_TEXT).assertIsDisplayed()
        when (state) {
            State.RETRYING -> composeRule.onNodeWithText("Retrying…").assertIsDisplayed()
            State.FAILED -> composeRule.onNodeWithText(CONNECTION_TEXT).assertIsDisplayed()
            State.PARKED -> composeRule.onNodeWithText(CONNECTION_TEXT).assertDoesNotExist()
        }
        composeRule
            .onNodeWithTag(CARD_TAG)
            .captureRoboImage("src/test/snapshots/group_convergence_${state.slug}_${theme.slug}.png")
        if (state == State.RETRYING) {
            composeRule.onNodeWithText("Retrying…").assertIsNotEnabled()
        } else {
            composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
            composeRule.runOnIdle { assertEquals(1, retries) }
        }
    }

    /** A recovery status with nothing to show, so only the parked flag can raise the card. */
    private fun healthyStatus() =
        GroupRecoveryStatusFfi(
            groupIdHex = "group",
            automaticRecoveryFailed = false,
            pendingReinvites = 0u,
            failedReinvites = 0u,
            rejoinInvitations = emptyList(),
        )

    private enum class State(
        val slug: String,
    ) {
        PARKED("parked"),
        RETRYING("retrying"),
        FAILED("failed"),
    }

    private enum class Theme(
        val slug: String,
    ) {
        LIGHT("light"),
        DARK("dark"),
        AMOLED("amoled"),
        LARGE_RTL("large_rtl"),
    }

    private companion object {
        const val CARD_TAG = "group-recovery-card"
        const val PARKED_TEXT = "This chat stopped syncing. Messages you send will wait until it catches up."
        const val CONNECTION_TEXT = "Couldn’t reach the relay. Check your connection and try again."
    }
}
