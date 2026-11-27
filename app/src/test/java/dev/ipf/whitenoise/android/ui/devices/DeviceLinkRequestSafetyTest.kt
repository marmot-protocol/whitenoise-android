package dev.ipf.whitenoise.android.ui.devices

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A link request reaches the screen only through its notice, arms Link late and warns on repeated requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h2000dp-mdpi")
class DeviceLinkRequestSafetyTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Link stays disabled until the code has been on screen for a second. */
    @Test
    fun linkArmsASecondAfterTheCodeAppears() {
        compose.mainClock.autoAdvance = false
        compose.setContent { WhiteNoiseTheme { DeviceLinkRequestContent(COMPARING, null, {}, {}, {}) } }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithTag(DEVICE_LINK_PRIMARY_TAG).performScrollTo().assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(DEVICE_LINK_ARM_DELAY_MS - 100L)
        compose.onNodeWithTag(DEVICE_LINK_PRIMARY_TAG).assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(200L)
        compose.onNodeWithTag(DEVICE_LINK_PRIMARY_TAG).assertIsEnabled()
    }

    @Test
    fun aPressBeforeLinkArmsCannotApproveWhenReleasedAfterward() {
        var links = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { WhiteNoiseTheme { DeviceLinkRequestContent(COMPARING, null, { links++ }, {}, {}) } }
        compose.mainClock.advanceTimeByFrame()
        val link = compose.onNodeWithTag(DEVICE_LINK_PRIMARY_TAG).performScrollTo().assertIsNotEnabled()
        link.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(DEVICE_LINK_ARM_DELAY_MS + 100L)
        link.assertIsEnabled().performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, links) }
        link.performClick()
        compose.runOnIdle { assertEquals(1, links) }
    }

    /** Time spent preparing does not count: the wait starts when the code appears. */
    @Test
    fun theWaitStartsWhenTheCodeAppears() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf<DeviceLinkPromptStateFfi>(DeviceLinkPromptStateFfi.Preparing)
        compose.setContent { WhiteNoiseTheme { DeviceLinkRequestContent(state.value, null, {}, {}, {}) } }
        compose.mainClock.advanceTimeBy(DEVICE_LINK_ARM_DELAY_MS * 3)

        state.value = COMPARING
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(DEVICE_LINK_PRIMARY_TAG).performScrollTo().assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(DEVICE_LINK_ARM_DELAY_MS + 100L)
        compose.onNodeWithTag(DEVICE_LINK_PRIMARY_TAG).assertIsEnabled()
    }

    /** A repeated request shows the warning with no code and no Link; its two actions decline or open Devices. */
    @Test
    fun aRepeatedRequestWarnsInsteadOfOfferingLink() {
        var declines = 0
        var devices = 0
        compose.setContent {
            WhiteNoiseTheme {
                DeviceLinkRequestContent(
                    state = COMPARING,
                    running = null,
                    onLink = { error("Link must not be offered") },
                    onDecline = { declines++ },
                    onClose = {},
                    repeated = true,
                    onReviewDevices = { devices++ },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.link_request_repeated_title)).assertIsDisplayed()
        compose.onNodeWithTag(DEVICE_LINK_CODE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DEVICE_LINK_LABEL_TAG).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.link_request_link)).assertDoesNotExist()
        compose
            .onNodeWithTag(DEVICE_LINK_PRIMARY_TAG)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithTag(DEVICE_LINK_SECONDARY_TAG).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1 to 1, declines to devices) }
    }

    /** A request this user already approved keeps its own step even when its profile's requests are repeated. */
    @Test
    fun aRepeatedRequestAlreadyLinkingKeepsItsStep() {
        compose.setContent {
            WhiteNoiseTheme {
                DeviceLinkRequestContent(DeviceLinkPromptStateFfi.Linking, null, {}, {}, {}, repeated = true)
            }
        }

        compose.onNodeWithText(context.getString(R.string.link_request_linking)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.link_request_repeated_title)).assertDoesNotExist()
    }

    /** The notice names the profile, turns into the warning when repeated, and Review and Not now stay distinct. */
    @Test
    fun theNoticeReviewsOrPutsOff() {
        var reviews = 0
        var putOff = 0
        val repeated = mutableStateOf(false)
        val profile = mutableStateOf<String?>(null)
        compose.setContent {
            WhiteNoiseTheme { DeviceLinkRequestBanner(repeated.value, profile.value, { reviews++ }, { putOff++ }) }
        }

        compose.onNodeWithText(context.getString(R.string.link_request_notice)).assertIsDisplayed()
        compose.onNodeWithTag(DEVICE_LINK_REQUEST_NOTICE_REVIEW_TAG).performClick()
        compose.onNodeWithTag(DEVICE_LINK_REQUEST_NOTICE_DISMISS_TAG).performClick()
        compose.runOnIdle { assertEquals(1 to 1, reviews to putOff) }

        compose.runOnIdle { profile.value = "Work" }
        compose.onNodeWithText(context.getString(R.string.link_request_notice_other_profile, "Work")).assertIsDisplayed()
        compose.runOnIdle { repeated.value = true }
        compose.onNodeWithText(context.getString(R.string.link_request_repeated_notice_other_profile, "Work")).assertIsDisplayed()
        compose.runOnIdle { profile.value = null }
        compose.onNodeWithText(context.getString(R.string.link_request_repeated_notice)).assertIsDisplayed()
    }

    /** A touch that began before the request was shown never reaches its buttons; a later one does. */
    @Test
    fun touchesBegunBeforeTheRequestAreIgnored() {
        var taps = 0
        val shownAt = mutableStateOf(Long.MAX_VALUE)
        compose.setContent {
            Box(Modifier.ignoringTouchesBefore(shownAt.value)) {
                Button(onClick = { taps++ }, modifier = Modifier.testTag("button")) { Text("Link") }
            }
        }

        compose.onNodeWithTag("button").performClick()
        compose.runOnIdle { assertEquals(0, taps) }
        compose.runOnIdle { shownAt.value = 0L }
        compose.onNodeWithTag("button").performClick()
        compose.runOnIdle { assertEquals(1, taps) }
    }

    /** Only a touch that went down strictly before the request appeared counts as earlier. */
    @Test
    fun anEarlierTouchIsOneThatWentDownBeforeTheRequest() {
        assertTrue(touchBeganBefore(downUptimeMs = 99L, shownUptimeMs = 100L))
        assertFalse(touchBeganBefore(downUptimeMs = 100L, shownUptimeMs = 100L))
        assertFalse(touchBeganBefore(downUptimeMs = 101L, shownUptimeMs = 100L))
    }

    private companion object {
        val COMPARING = DeviceLinkPromptStateFfi.Comparing(fixtureCode())
    }
}
