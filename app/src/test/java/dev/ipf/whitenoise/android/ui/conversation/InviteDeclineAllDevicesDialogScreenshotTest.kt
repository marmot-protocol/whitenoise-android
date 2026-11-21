package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.LinkedDeviceFfi
import dev.ipf.marmotkit.LinkedDeviceStatusFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The question asked before an invitation is declined on an account with other linked devices: when it is asked,
 * and how it looks across light, dark, AMOLED and large RTL text, with each button wired to exactly one callback.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class InviteDeclineAllDevicesDialogScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A successfully read empty or single-device roster declines at once. */
    @Test
    fun aLoneDeviceIsNotAsked() {
        assertFalse(Result.success(listOf(device("a", isThisDevice = true))).declineNeedsConsent().getOrThrow())
        assertFalse(Result.success(emptyList<LinkedDeviceFfi>()).declineNeedsConsent().getOrThrow())
    }

    /** An unreadable inventory cannot take the path that declines on a lone device. */
    @Test
    fun failedReadKeepsTheInvitationPending() {
        val failure = IllegalStateException("roster unavailable")
        val decision = Result.failure<List<LinkedDeviceFfi>>(failure).declineNeedsConsent()
        assertEquals(failure, decision.exceptionOrNull())
    }

    /** Any other device of the account is reached by the decline, one whose removal is still pending included. */
    @Test
    fun anotherLinkedDeviceIsAskedAbout() {
        val here = device("a", isThisDevice = true)

        assertTrue(Result.success(listOf(here, device("b"))).declineNeedsConsent().getOrThrow())
        val removing = listOf(here, device("b", removalPending = true))
        assertTrue(Result.success(removing).declineNeedsConsent().getOrThrow())
    }

    /** The all-devices question and both answers in the light dialog surface. */
    @Test
    fun allDevicesQuestionLight() = capture("light", dark = false)

    /** The all-devices question stays legible on the dark dialog surface. */
    @Test
    fun allDevicesQuestionDark() = capture("dark", dark = true)

    /** AMOLED keeps the dialog outline and the error-coloured confirm action. */
    @Test
    fun allDevicesQuestionAmoled() = capture("amoled", dark = true, amoled = true)

    /** At 200% RTL text the explanation wraps and both answers stay reachable. */
    @Test
    fun allDevicesQuestionLargeRtl() = capture("large_rtl", dark = true, largeRtl = true)

    /** Renders the dialog, records its baseline, and checks that each answer reaches only its own callback. */
    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val lifetime = mutableStateOf(0)
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    key(lifetime.value) {
                        InviteDeclineAllDevicesDialog(onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
                    }
                }
            }
        }
        composeRule.onNodeWithText("Decline on all your devices?").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Declining takes this account out of the chat on every linked device. " +
                    "You’ll need a new invitation to come back.",
            ).assertIsDisplayed()
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/invite_decline_all_devices_$name.png")

        composeRule.onNodeWithTag(INVITE_DECLINE_ALL_DEVICES_CANCEL_TAG).performClick()
        composeRule.runOnIdle { assertEquals(0 to 1, confirmed to dismissed) }
        composeRule.runOnIdle { lifetime.value++ }
        composeRule.onNodeWithTag(INVITE_DECLINE_ALL_DEVICES_CONFIRM_TAG).performClick()
        composeRule.runOnIdle { assertEquals(1 to 1, confirmed to dismissed) }
    }

    /** One of the account's devices as MDK lists it. */
    private fun device(
        slot: String,
        isThisDevice: Boolean = false,
        removalPending: Boolean = false,
    ) = LinkedDeviceFfi(
        slotIdHex = slot,
        isThisDevice = isThisDevice,
        label = slot,
        client = "White Noise",
        platform = "Android",
        linkedAt = 1uL,
        linkedBySlotIdHex = null,
        status = LinkedDeviceStatusFfi.ACTIVE,
        addToAllChats = true,
        bringHistory = false,
        keepWhileInactive = false,
        lastActiveAt = null,
        inactive = false,
        removalPending = removalPending,
    )
}
