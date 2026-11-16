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
import dev.ipf.marmotkit.GroupCommitRefusalCauseFfi
import dev.ipf.marmotkit.GroupCommitRefusedRelayFfi
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
 * The recovery card's banner for relays that refused one of the user's group changes for good: one relay named for a
 * change too large, two relays counted, a dismissal that failed, and the dismissal in progress, across themes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupCommitRefusalNoticeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** One relay that refused a too-large change is named by host, light theme. */
    @Test
    fun oneRelayTooLargeLight() = capture(State.ONE_TOO_LARGE, Theme.LIGHT)

    /** Two relays that refused for other reasons are counted, dark theme. */
    @Test
    fun twoRelaysRejectedDark() = capture(State.TWO_REJECTED, Theme.DARK)

    /** A dismissal MDK refused explains itself and keeps the banner, AMOLED. */
    @Test
    fun dismissFailedAmoled() = capture(State.DISMISS_FAILED, Theme.AMOLED)

    /** At 200% RTL text the banner wraps and Dismiss stays reachable. */
    @Test
    @Config(qualifiers = "en-w600dp-h900dp-mdpi")
    fun oneRelayTooLargeLargeRtl() = capture(State.ONE_TOO_LARGE, Theme.LARGE_RTL)

    /** A dismissal in progress disables Dismiss so it cannot be sent twice. */
    @Test
    fun dismissingDisablesDismiss() {
        render(State.DISMISSING, Theme.LIGHT) {}
        composeRule.onNodeWithText(DISMISS).assertIsNotEnabled()
    }

    /** Renders one banner state in one theme, checks its text and Dismiss, and records the baseline. */
    private fun capture(
        state: State,
        theme: Theme,
    ) {
        var dismissals = 0
        render(state, theme) { dismissals++ }
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        when (state) {
            State.ONE_TOO_LARGE -> composeRule.onNodeWithText(ONE_TOO_LARGE_TEXT).assertIsDisplayed()
            State.TWO_REJECTED -> composeRule.onNodeWithText(TWO_REJECTED_TEXT).assertIsDisplayed()
            State.DISMISS_FAILED -> composeRule.onNodeWithText(FAILED_TEXT).assertIsDisplayed()
            State.DISMISSING -> Unit
        }
        composeRule
            .onNodeWithTag(CARD_TAG)
            .captureRoboImage("src/test/snapshots/group_commit_refusal_${state.slug}_${theme.slug}.png")
        composeRule.onNodeWithText(DISMISS).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, dismissals) }
    }

    /** Shows the recovery card holding only [state]'s refusal banner in [theme]. */
    private fun render(
        state: State,
        theme: Theme,
        onDismiss: () -> Unit,
    ) {
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
                        busy = false,
                        inviterName = { it },
                        inviterIdentity = { it },
                        onConfirm = {},
                        onDecline = {},
                        refusal = state.model(),
                        onDismissCommitRefusal = onDismiss,
                        modifier = Modifier.width(if (largeRtl) 600.dp else 360.dp).testTag(CARD_TAG),
                    )
                }
            }
        }
    }

    /** A recovery status with nothing to show, so only the refusal can raise the card. */
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
        ONE_TOO_LARGE("one_too_large"),
        TWO_REJECTED("two_rejected"),
        DISMISS_FAILED("dismiss_failed"),
        DISMISSING("dismissing"),
        ;

        /** The banner model this state shows. */
        fun model(): GroupCommitRefusalNoticeModel =
            when (this) {
                ONE_TOO_LARGE -> GroupCommitRefusalNoticeModel(listOf(relay("wss://relay.example", true)))
                TWO_REJECTED ->
                    GroupCommitRefusalNoticeModel(
                        listOf(relay("wss://a.example", false), relay("wss://b.example", false)),
                    )
                DISMISS_FAILED ->
                    GroupCommitRefusalNoticeModel(
                        listOf(relay("wss://relay.example", true)),
                        dismissFailure = R.string.group_commit_refusal_dismiss_failed,
                    )
                DISMISSING ->
                    GroupCommitRefusalNoticeModel(listOf(relay("wss://relay.example", true)), dismissing = true)
            }

        private companion object {
            /** One relay at [url] that refused, for size when [tooLarge]. */
            fun relay(
                url: String,
                tooLarge: Boolean,
            ) = GroupCommitRefusedRelayFfi(
                relayUrl = url,
                cause = if (tooLarge) GroupCommitRefusalCauseFfi.TOO_LARGE else GroupCommitRefusalCauseFfi.REJECTED,
                sizeClass = null,
            )
        }
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
        const val TITLE = "Group change refused"
        const val DISMISS = "Dismiss"
        const val ONE_TOO_LARGE_TEXT =
            "relay.example refused a group change you made because it was too large. " +
                "Members who use only that relay may not get it."
        const val TWO_REJECTED_TEXT =
            "2 relays refused a group change you made. Members who use only those relays may not get it."
        const val FAILED_TEXT = "Couldn’t dismiss this warning. Try again."
    }
}
