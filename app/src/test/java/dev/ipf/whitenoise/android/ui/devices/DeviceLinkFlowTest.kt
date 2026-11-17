package dev.ipf.whitenoise.android.ui.devices

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.DeviceLinkEndReasonFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.marmotkit.NewDeviceLinkStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DeviceLinkChoices
import dev.ipf.whitenoise.android.state.DeviceLinkCommand
import dev.ipf.whitenoise.android.state.LinkedDeviceRoster
import dev.ipf.whitenoise.android.state.LinkedDeviceRosterState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Linking follows MDK's states on both devices, and only the named actions reach MDK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h2000dp-mdpi")
class DeviceLinkFlowTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Only a matching code offers They match; waiting and joining never offer a way to finish. */
    @Test
    fun onlyComparingOffersTheyMatchOnTheNewDevice() {
        assertEquals(DeviceLinkAction.ConfirmMatch, newDeviceLinkStep(NewDeviceLinkStateFfi.Comparing(CODE)).primary)
        listOf(
            NewDeviceLinkStateFfi.WaitingForAnswer,
            NewDeviceLinkStateFfi.WaitingForOtherDevice,
            NewDeviceLinkStateFfi.Joining,
        ).forEach { state ->
            val step = newDeviceLinkStep(state)
            assertNull(state.toString(), step.primary)
            assertEquals(state.toString(), DeviceLinkAction.Cancel, step.secondary)
        }
    }

    /** Joining is not shown as linked: only MDK's Linked state gets the linked wording and Done. */
    @Test
    fun onlyLinkedSaysTheDeviceIsLinked() {
        val linked = newDeviceLinkStep(NewDeviceLinkStateFfi.Linked)
        val joining = newDeviceLinkStep(NewDeviceLinkStateFfi.Joining)

        assertEquals(R.string.link_device_linked_title, linked.title)
        assertEquals(DeviceLinkAction.Done, linked.primary)
        assertEquals(R.string.link_device_joining_title, joining.title)
    }

    /** An ended link names MDK's reason and offers a new attempt, never a resumed one. */
    @Test
    fun anEndedLinkNamesItsReasonAndOffersANewAttempt() {
        val reasons = DeviceLinkEndReasonFfi.entries
        val steps = reasons.map { newDeviceLinkStep(NewDeviceLinkStateFfi.Ended(it)) }

        assertEquals(reasons.size, steps.map { it.detail }.toSet().size)
        steps.forEach { step ->
            assertEquals(DeviceLinkAction.TryAgain, step.primary)
            assertEquals(DeviceLinkAction.Close, step.secondary)
            assertTrue(step.code.isEmpty())
        }
    }

    /** On the approving device, Link device and Not now are offered only while MDK shows the code. */
    @Test
    fun onlyComparingOffersLinkAndNotNowOnTheApprovingDevice() {
        val comparing = deviceLinkRequestStep(DeviceLinkPromptStateFfi.Comparing(CODE)) { "" }
        assertEquals(DeviceLinkAction.Link, comparing.primary)
        assertEquals(DeviceLinkAction.NotNow, comparing.secondary)
        assertEquals(CODE, comparing.code)
        listOf(
            DeviceLinkPromptStateFfi.Preparing,
            DeviceLinkPromptStateFfi.CompetingRequests,
            DeviceLinkPromptStateFfi.RateLimited(1u),
            DeviceLinkPromptStateFfi.CannotLink(listOf("x")),
            DeviceLinkPromptStateFfi.TooManyDevices,
            DeviceLinkPromptStateFfi.Linking,
        ).forEach { state ->
            val step = deviceLinkRequestStep(state) { "" }
            assertNull(state.toString(), step.primary)
            assertEquals(state.toString(), DeviceLinkAction.Close, step.secondary)
        }
    }

    /** Linking is not shown as linked, and each settled outcome keeps its own wording. */
    @Test
    fun settledRequestsReportTheirOwnOutcome() {
        val linking = deviceLinkRequestStep(DeviceLinkPromptStateFfi.Linking) { "" }
        val linked = deviceLinkRequestStep(DeviceLinkPromptStateFfi.Linked) { "" }
        val named = deviceLinkRequestStep(DeviceLinkPromptStateFfi.AddedElsewhere("Tablet", "Pixel")) { "" }
        val unnamed = deviceLinkRequestStep(DeviceLinkPromptStateFfi.AddedElsewhere("", "Pixel")) { "" }
        val ended = deviceLinkRequestStep(DeviceLinkPromptStateFfi.Ended(DeviceLinkEndReasonFfi.TIMED_OUT)) { "" }

        assertEquals(R.string.link_request_linking, linking.detail)
        assertEquals(R.string.link_request_linked, linked.detail)
        assertEquals(DeviceLinkAction.Done, linked.primary)
        assertEquals(listOf("Tablet", "Pixel"), named.detailArgs)
        assertEquals(R.string.link_request_added_elsewhere_unnamed, unnamed.detail)
        assertEquals(R.string.link_device_ended_timed_out, ended.detail)
    }

    /** A rate-limited request shows the time MDK gave, formatted by the caller. */
    @Test
    fun aRateLimitedRequestShowsMdksRetryTime() {
        val step = deviceLinkRequestStep(DeviceLinkPromptStateFfi.RateLimited(42u)) { "at $it" }

        assertEquals(listOf("at 42"), step.detailArgs)
    }

    /** Every emoji is shown in MDK's order and announced with its position and description. */
    @Test
    fun theCodeIsShownInOrderWithPositions() {
        compose.setContent { WhiteNoiseTheme { DeviceLinkCode(CODE) } }

        CODE.forEachIndexed { index, emoji ->
            val spoken = context.getString(R.string.link_device_emoji_position, index + 1, CODE.size, emoji.description)
            compose.onNodeWithContentDescription(spoken).assertIsDisplayed()
        }
        compose.onAllNodesWithContentDescription("1 of", substring = true).fetchSemanticsNodes().let {
            assertEquals(1, it.size)
        }
    }

    /** Link device sends the name and both choices as entered. */
    @Test
    fun linkDeviceSendsTheEnteredChoices() {
        var linked: DeviceLinkChoices? = null
        request(DeviceLinkPromptStateFfi.Comparing(CODE), onLink = { linked = it })

        compose.onNodeWithTag(DEVICE_LINK_LABEL_TAG).performTextInput("  Desk  ")
        compose.onNodeWithTag(DEVICE_LINK_BRING_HISTORY_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(DEVICE_LINK_PRIMARY_TAG).performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(DeviceLinkChoices(label = "Desk", addToAllChats = true, bringHistory = false), linked)
        }
    }

    /** Both choices start on, as MDK offers them. */
    @Test
    fun bothChoicesStartOn() {
        var linked: DeviceLinkChoices? = null
        request(DeviceLinkPromptStateFfi.Comparing(CODE), onLink = { linked = it })

        compose.onNodeWithTag(DEVICE_LINK_PRIMARY_TAG).performScrollTo().performClick()

        compose.runOnIdle { assertEquals(DeviceLinkChoices(), linked) }
    }

    /** A name over MDK's byte limit says so and cannot be submitted. */
    @Test
    fun anOverlongNameCannotBeSubmitted() {
        var links = 0
        request(DeviceLinkPromptStateFfi.Comparing(CODE), onLink = { links++ })

        compose.onNodeWithTag(DEVICE_LINK_LABEL_TAG).performTextInput("a".repeat(65))

        compose.onNodeWithTag(DEVICE_LINK_PRIMARY_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.link_request_label_too_long)).assertExists()
        compose.runOnIdle { assertEquals(0, links) }
    }

    /** Not now declines; Back only closes and declines nothing. */
    @Test
    fun notNowDeclinesAndCloseAnswersNothing() {
        var declines = 0
        var closes = 0
        request(DeviceLinkPromptStateFfi.Comparing(CODE), onDecline = { declines++ }, onClose = { closes++ })

        compose.onNodeWithTag(DEVICE_LINK_SECONDARY_TAG).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1 to 0, declines to closes) }
    }

    /** A request that cannot be linked offers only Close, which answers nothing. */
    @Test
    fun aRequestThatCannotBeLinkedOnlyCloses() {
        var declines = 0
        var closes = 0
        request(DeviceLinkPromptStateFfi.TooManyDevices, onDecline = { declines++ }, onClose = { closes++ })

        compose.onNodeWithTag(DEVICE_LINK_PRIMARY_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DEVICE_LINK_LABEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DEVICE_LINK_SECONDARY_TAG).performClick()
        compose.runOnIdle { assertEquals(0 to 1, declines to closes) }
    }

    /** While a command runs neither action can be pressed again. */
    @Test
    fun actionsAreDisabledWhileACommandRuns() {
        request(DeviceLinkPromptStateFfi.Comparing(CODE), running = DeviceLinkCommand.Link)

        compose.onNodeWithTag(DEVICE_LINK_PRIMARY_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(DEVICE_LINK_SECONDARY_TAG).performScrollTo().assertIsNotEnabled()
    }

    /** The new device's actions reach the caller as the action the step named. */
    @Test
    fun theNewDeviceStepReportsItsActions() {
        val actions = mutableListOf<DeviceLinkAction>()
        compose.setContent {
            WhiteNoiseTheme {
                LinkThisDeviceContent(
                    step = newDeviceLinkStep(NewDeviceLinkStateFfi.Comparing(CODE)),
                    running = null,
                    onBack = {},
                    onAction = { actions += it },
                )
            }
        }

        compose.onNodeWithTag(DEVICE_LINK_PRIMARY_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(DEVICE_LINK_SECONDARY_TAG).performClick()

        compose.runOnIdle { assertEquals(listOf(DeviceLinkAction.ConfirmMatch, DeviceLinkAction.Cancel), actions) }
    }

    /** Link this device is offered once the roster is read and lists no sibling. */
    @Test
    fun linkThisDeviceIsOfferedWhenNoSiblingIsLinked() {
        var starts = 0
        val onlySelf = LinkedDeviceRoster(fixtureDevice("self", isThisDevice = true), emptyList(), emptyList())
        roster(LinkedDeviceRosterState(roster = onlySelf), onLinkThisDevice = { starts++ })

        compose.onNodeWithTag(LINKED_DEVICES_LINK_THIS_TAG).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
        compose.onNodeWithText(context.getString(R.string.link_device_add_steps)).assertExists()
    }

    /** An account that already has a sibling gets the steps for adding a device and no Link this device. */
    @Test
    fun aLinkedAccountIsNotOfferedLinkThisDevice() {
        roster(fixtureRosterState())

        compose.onNodeWithTag(LINKED_DEVICES_LINK_THIS_TAG).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.link_device_add_steps)).assertExists()
    }

    /** Link this device is not offered before the roster is read or after a failed read. */
    @Test
    fun linkThisDeviceWaitsForTheRoster() {
        roster(LinkedDeviceRosterState(readFailed = true))

        compose.onNodeWithTag(LINKED_DEVICES_LINK_THIS_TAG).assertDoesNotExist()
    }

    /** A link under way keeps its way back in and says it is in progress. */
    @Test
    fun aLinkUnderWayCanBeReopened() {
        roster(
            fixtureRosterState(),
            LinkedDevicesLinking(newDevice = NewDeviceLinkStateFfi.WaitingForOtherDevice),
        )

        compose.onNodeWithTag(LINKED_DEVICES_LINK_THIS_TAG).assertExists()
        compose.onNodeWithText(context.getString(R.string.link_device_in_progress), substring = true).assertExists()
    }

    /** A finished or ended link is not treated as under way. */
    @Test
    fun finishedLinksAreNotUnderWay() {
        assertFalse(null.inProgress())
        assertFalse(NewDeviceLinkStateFfi.Linked.inProgress())
        assertFalse(NewDeviceLinkStateFfi.Ended(DeviceLinkEndReasonFfi.CANCELLED).inProgress())
        assertTrue(NewDeviceLinkStateFfi.Joining.inProgress())
    }

    /** An account that signs through an external signer app is told linking is unavailable and offered no action. */
    @Test
    fun anExternalSignerAccountIsOfferedNoLinkAction() {
        val onlySelf = LinkedDeviceRoster(fixtureDevice("self", isThisDevice = true), emptyList(), emptyList())
        roster(
            LinkedDeviceRosterState(roster = onlySelf),
            LinkedDevicesLinking(availability = DeviceLinkAvailability.ExternalSigner),
        )

        compose.onNodeWithTag(LINKED_DEVICES_EXTERNAL_SIGNER_TAG).assertExists()
        compose.onNodeWithTag(LINKED_DEVICES_LINK_THIS_TAG).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.link_device_add_steps)).assertDoesNotExist()
    }

    /** A request on another profile is announced with that profile's name; Review and Not now are distinct. */
    @Test
    fun aRequestOnAnotherProfileIsAnnouncedNotAnswered() {
        var reviews = 0
        var hides = 0
        compose.setContent {
            WhiteNoiseTheme { DeviceLinkOtherProfileDialog("Work", onReview = { reviews++ }, onNotNow = { hides++ }) }
        }

        compose.onNodeWithText(context.getString(R.string.link_request_other_profile, "Work")).assertIsDisplayed()
        compose.onNodeWithTag(DEVICE_LINK_REVIEW_TAG).performClick()
        compose.onNodeWithText(context.getString(R.string.link_request_not_now)).performClick()
        compose.runOnIdle { assertEquals(1 to 1, reviews to hides) }
    }

    /** Renders the approving device's request for [state]. */
    private fun request(
        state: DeviceLinkPromptStateFfi,
        running: DeviceLinkCommand? = null,
        onLink: (DeviceLinkChoices) -> Unit = {},
        onDecline: () -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        compose.setContent {
            WhiteNoiseTheme { DeviceLinkRequestContent(state, running, onLink, onDecline, onClose) }
        }
    }

    /** Renders the roster screen with its linking section. */
    private fun roster(
        state: LinkedDeviceRosterState,
        linking: LinkedDevicesLinking = LinkedDevicesLinking(),
        onLinkThisDevice: () -> Unit = {},
    ) {
        compose.setContent {
            WhiteNoiseTheme {
                LinkedDevicesContent(state, {}, {}, linking, onLinkThisDevice)
            }
        }
    }

    private companion object {
        val CODE = fixtureCode()
    }
}
