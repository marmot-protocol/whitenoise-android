package dev.ipf.whitenoise.android.ui.devices

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.LinkChatProgressFfi
import dev.ipf.marmotkit.LinkedDeviceStatusFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.LinkedDeviceRoster
import dev.ipf.whitenoise.android.state.LinkedDeviceRosterState
import dev.ipf.whitenoise.android.state.withChatProgress
import dev.ipf.whitenoise.android.ui.profile.ProfileReadiness
import dev.ipf.whitenoise.android.ui.settings.AccountHubGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The roster screen renders MDK's roster truthfully in every read state, and its entry point follows the gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h2000dp-mdpi")
class LinkedDevicesContentTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Before the first read finishes the screen says it is loading and shows no empty note. */
    @Test
    fun beforeTheFirstReadTheScreenIsLoading() {
        content(LinkedDeviceRosterState(refreshing = true))

        compose.onNodeWithTag(LINKED_DEVICES_LOADING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(LINKED_DEVICES_EMPTY_TAG).assertDoesNotExist()
        compose.onNodeWithTag(LINKED_DEVICES_ERROR_TAG).assertDoesNotExist()
    }

    /** An account with nothing linked reads as empty, not as a failure. */
    @Test
    fun anEmptyRosterSaysNothingIsLinked() {
        content(LinkedDeviceRosterState(roster = LinkedDeviceRoster(null, emptyList(), emptyList())))

        compose.onNodeWithText(context.getString(R.string.linked_devices_empty)).assertIsDisplayed()
        compose.onNodeWithTag(LINKED_DEVICES_ERROR_TAG).assertDoesNotExist()
    }

    /** A failed first read shows the failure and a retry, never an empty list. */
    @Test
    fun aFailedFirstReadShowsTheFailureAndNoEmptyNote() {
        var retries = 0
        content(LinkedDeviceRosterState(readFailed = true), onRetry = { retries++ })

        compose.onNodeWithTag(LINKED_DEVICES_ERROR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(LINKED_DEVICES_EMPTY_TAG).assertDoesNotExist()
        compose.onNodeWithTag(LINKED_DEVICES_LOADING_TAG).assertDoesNotExist()
        compose.onNodeWithTag(LINKED_DEVICES_RETRY_TAG).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    /** A failed refresh keeps the rows already read beside the failure. */
    @Test
    fun aFailedRefreshKeepsTheRows() {
        content(fixtureRosterState().copy(readFailed = true))

        compose.onNodeWithTag(LINKED_DEVICES_ERROR_TAG).assertIsDisplayed()
        compose.onNodeWithTag("linked_devices.device.tablet").assertIsDisplayed()
    }

    /** Retry is disabled while a retry is already reading. */
    @Test
    fun retryIsDisabledWhileReading() {
        content(LinkedDeviceRosterState(readFailed = true, refreshing = true))

        compose.onNodeWithTag(LINKED_DEVICES_RETRY_TAG).assertIsNotEnabled()
    }

    /** This device, other devices and pending removals each get their own group. */
    @Test
    fun devicesAreGroupedByRole() {
        content(fixtureRosterState())

        compose.onNodeWithTag("linked_devices.group.this").assertIsDisplayed()
        compose.onNodeWithTag("linked_devices.group.others").assertIsDisplayed()
        compose.onNodeWithTag("linked_devices.group.removing").assertIsDisplayed()
        compose.onNodeWithTag(LINKED_DEVICES_EMPTY_TAG).assertDoesNotExist()
    }

    /** Completed and inconsistent inventories disappear. An outstanding inventory belongs to its exact slot. */
    @Test
    fun progressIsScopedToADeviceAndDisappearsWhenDone() {
        val self = fixtureDevice("self", isThisDevice = true)
        val sibling = fixtureDevice("sibling")
        val roster = LinkedDeviceRoster(self, listOf(sibling), emptyList())
        val progress = LinkChatProgressFfi("sibling", false, 12u, 5u)
        val active = roster.withChatProgress(listOf(progress))
        assertNull(active.thisDevice?.chatProgress)
        assertEquals(progress, active.device("sibling")?.chatProgress)
        assertNull(roster.withChatProgress(listOf(progress.copy(remaining = 0u))).device("sibling")?.chatProgress)
        assertNull(roster.withChatProgress(listOf(progress.copy(remaining = 13u))).device("sibling")?.chatProgress)
        content(LinkedDeviceRosterState(roster = active))
        val adding = context.getString(R.string.link_device_adding_chats, 7, 12)
        compose.onNodeWithText(adding, substring = true).assertExists()
    }

    /** An account with only this device says no other device is linked. */
    @Test
    fun aRosterWithOnlyThisDeviceSaysNoOthersAreLinked() {
        val onlySelf = LinkedDeviceRoster(fixtureDevice("self", isThisDevice = true), emptyList(), emptyList())
        content(LinkedDeviceRosterState(roster = onlySelf))

        compose.onNodeWithText(context.getString(R.string.linked_devices_no_others)).assertIsDisplayed()
        compose.onNodeWithTag("linked_devices.group.others").assertDoesNotExist()
    }

    /** A device without a name gets the fallback name rather than a blank title. */
    @Test
    fun anUnnamedDeviceUsesTheFallbackName() {
        content(fixtureRosterState())

        compose.onNodeWithText(context.getString(R.string.linked_devices_unnamed), substring = true).assertIsDisplayed()
    }

    /** A device says which device linked it, by name or as unnamed, and says nothing when MDK names no linker. */
    @Test
    fun theSummaryNamesTheLinkingDevice() {
        lateinit var named: List<String>
        lateinit var unnamed: List<String>
        lateinit var unknown: List<String>
        compose.setContent {
            named = linkedDeviceSummary(fixtureDevice("named", linkedByLabel = "Phone"))
            unnamed = linkedDeviceSummary(fixtureDevice("unnamed", linkedByLabel = ""))
            unknown = linkedDeviceSummary(fixtureDevice("unknown"))
        }
        val unnamedDevice = context.getString(R.string.linked_devices_unnamed)
        val prefix = context.getString(R.string.linked_devices_linked_by, "").trim()

        compose.runOnIdle {
            assertEquals(true, context.getString(R.string.linked_devices_linked_by, "Phone") in named)
            assertEquals(true, context.getString(R.string.linked_devices_linked_by, unnamedDevice) in unnamed)
            assertEquals(false, unknown.any { it.startsWith(prefix) })
        }
    }

    /** The roster row leaves out when and by whom a device was linked, which the device's own page still says. */
    @Test
    fun theRosterLeavesLinkHistoryToTheDevicePage() {
        lateinit var row: List<String>
        lateinit var page: List<String>
        val device = fixtureDevice("named", linkedByLabel = "Phone", lastActiveAtSeconds = FIXTURE_LINKED_AT)
        compose.setContent {
            row = linkedDeviceSummary(device, linkHistory = false)
            page = linkedDeviceSummary(device)
        }
        val linkedBy = context.getString(R.string.linked_devices_linked_by, "Phone")
        val linkedOnPrefix = context.getString(R.string.linked_devices_linked_on, "").trim()
        val lastActivePrefix = context.getString(R.string.linked_devices_last_active, "").trim()

        compose.runOnIdle {
            val history = page - row.toSet()
            assertEquals(2, history.size)
            assertEquals(true, history[0].startsWith(linkedOnPrefix) && "2026" in history[0])
            assertEquals(linkedBy, history[1])
            assertEquals(row, page - history.toSet())
            assertEquals(false, row.any { it.startsWith(linkedOnPrefix) })
            assertEquals(true, row.any { it.startsWith(lastActivePrefix) })
        }
    }

    /** A device is drawn as a handheld one, an unnamed platform included, unless its platform names a computer. */
    @Test
    fun aDeviceGetsTheGlyphOfItsPlatform() {
        assertEquals(R.drawable.ic_device, linkedDeviceIcon(""))
        assertEquals(R.drawable.ic_device, linkedDeviceIcon("Android"))
        assertEquals(R.drawable.ic_device, linkedDeviceIcon("watch"))
        assertEquals(R.drawable.ic_device_laptop, linkedDeviceIcon(" macOS "))
        assertEquals(R.drawable.ic_device_desktop, linkedDeviceIcon("LINUX"))
        assertEquals(R.drawable.ic_device_desktop, linkedDeviceIcon("Windows"))
    }

    /** A sibling with no counted heartbeat says so, and this device never shows a last-active line. */
    @Test
    fun heartbeatLinesFollowWhatMdkObserved() {
        lateinit var self: List<String>
        lateinit var silent: List<String>
        lateinit var seen: List<String>
        compose.setContent {
            self = linkedDeviceSummary(fixtureDevice("self", isThisDevice = true))
            silent = linkedDeviceSummary(fixtureDevice("silent"))
            seen = linkedDeviceSummary(fixtureDevice("seen", lastActiveAtSeconds = FIXTURE_LINKED_AT))
        }
        val noActivity = context.getString(R.string.linked_devices_no_activity_seen)
        val lastActivePrefix = context.getString(R.string.linked_devices_last_active, "").trim()

        compose.runOnIdle {
            assertEquals(false, self.any { it == noActivity || it.startsWith(lastActivePrefix) })
            assertEquals(true, noActivity in silent)
            assertEquals(true, seen.any { it.startsWith(lastActivePrefix) })
            assertEquals("White Noise · Android", self.first())
        }
    }

    /** The state line reports a pending removal first, then the roster's status, then local inactivity. */
    @Test
    fun theStateLineFollowsMdksPrecedence() {
        assertNull(linkedDeviceStateLabel(fixtureDevice("a")))
        assertEquals(
            R.string.linked_devices_removal_pending,
            linkedDeviceStateLabel(
                fixtureDevice("a", status = LinkedDeviceStatusFfi.LEAVING, removalPending = true),
            ),
        )
        assertEquals(
            R.string.linked_devices_status_inactive,
            linkedDeviceStateLabel(fixtureDevice("a", status = LinkedDeviceStatusFfi.INACTIVE, inactive = true)),
        )
        assertEquals(
            R.string.linked_devices_status_leaving,
            linkedDeviceStateLabel(fixtureDevice("a", status = LinkedDeviceStatusFfi.LEAVING)),
        )
        assertEquals(
            R.string.linked_devices_status_moved,
            linkedDeviceStateLabel(fixtureDevice("a", status = LinkedDeviceStatusFfi.MOVED)),
        )
        assertEquals(
            R.string.linked_devices_judged_inactive,
            linkedDeviceStateLabel(fixtureDevice("a", inactive = true)),
        )
    }

    /** The Account hub has no Devices row unless the caller supplies one. */
    @Test
    fun theAccountHubOmitsLinkedDevicesByDefault() {
        compose.setContent { WhiteNoiseTheme { AccountHubGroup(ProfileReadiness.Loading) {} } }

        compose.onNodeWithTag("settings.account.linked_devices").assertDoesNotExist()
    }

    /** With linked devices in the build the Account hub row opens the roster. */
    @Test
    fun theAccountHubRowOpensLinkedDevicesWhenSupplied() {
        var opened = 0
        compose.setContent {
            WhiteNoiseTheme { AccountHubGroup(ProfileReadiness.Loading, onOpenLinkedDevices = { opened++ }) {} }
        }

        compose.onNodeWithTag("settings.account.linked_devices").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    /** Renders the roster screen for [state]. */
    private fun content(
        state: LinkedDeviceRosterState,
        onRetry: () -> Unit = {},
    ) {
        val actions = LinkedDevicesActions(onRetry = onRetry)
        compose.setContent { WhiteNoiseTheme { LinkedDevicesContent(state, onBack = {}, actions = actions) } }
    }
}
