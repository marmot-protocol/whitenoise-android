package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountDevicesActivationFfi
import dev.ipf.marmotkit.GroupLeaveFailureFfi
import dev.ipf.marmotkit.LocalCleanupReportFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.WipeOutcomeFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.accountDevicesActivationReason
import dev.ipf.whitenoise.android.state.accountDevicesRefusalDetail
import dev.ipf.whitenoise.android.state.deviceUnlinkedAccount
import dev.ipf.whitenoise.android.state.failureDetail
import dev.ipf.whitenoise.android.state.nextSignedInAccount
import dev.ipf.whitenoise.android.state.wipeReport
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
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

/**
 * A group offers linked-device membership only when MDK says it is eligible, an unlinked device is reported as
 * that, and a sign-out handed to the account's other devices is not counted as finished.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class GroupAccountDevicesControlsTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Only MDK's Eligible answer offers the action, which asks for confirmation rather than acting at once. */
    @Test
    fun onlyAnEligibleGroupOffersTheAction() {
        var requests = 0
        rows(AccountDevicesActivationFfi.ELIGIBLE, onEnableRequested = { requests++ })

        compose.onNodeWithTag(GROUP_ACCOUNT_DEVICES_STATUS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GROUP_ACCOUNT_DEVICES_ENABLE_TAG).performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    /** A member who is not an admin, and a group whose answer is unread, show no row. */
    @Test
    fun aNonAdminAndAnUnreadAnswerShowNothing() {
        rows(AccountDevicesActivationFfi.NOT_ADMIN)
        compose.onNodeWithTag(GROUP_ACCOUNT_DEVICES_ENABLE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GROUP_ACCOUNT_DEVICES_STATUS_TAG).assertDoesNotExist()
    }

    /** No row is shown before MDK has answered. */
    @Test
    fun anUnreadAnswerShowsNothing() {
        rows(null)

        compose.onNodeWithTag(GROUP_ACCOUNT_DEVICES_ENABLE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GROUP_ACCOUNT_DEVICES_STATUS_TAG).assertDoesNotExist()
    }

    /** A group that already has it says so and offers nothing, because it cannot be turned off. */
    @Test
    fun anEnabledGroupSaysSoAndOffersNothing() {
        rows(AccountDevicesActivationFfi.ALREADY_ENABLED)

        compose.onNodeWithTag(GROUP_ACCOUNT_DEVICES_ENABLE_TAG).assertDoesNotExist()
        val enabled = context.getString(R.string.group_account_devices_enabled)
        compose.onNodeWithText(enabled, substring = true).assertExists()
    }

    /** A group that cannot take it yet gives MDK's reason and offers nothing. */
    @Test
    fun anIneligibleGroupGivesItsReason() {
        rows(AccountDevicesActivationFfi.MEMBERS_NEED_UPDATE)

        compose.onNodeWithTag(GROUP_ACCOUNT_DEVICES_ENABLE_TAG).assertDoesNotExist()
        val reason = context.getString(R.string.group_account_devices_members_need_update)
        compose.onNodeWithText(reason, substring = true).assertExists()
    }

    /** Each reason MDK can give for an ineligible group has its own sentence, and the other answers have none. */
    @Test
    fun eachIneligibleReasonHasItsOwnSentence() {
        val without =
            listOf(
                AccountDevicesActivationFfi.ELIGIBLE,
                AccountDevicesActivationFfi.NOT_ADMIN,
                AccountDevicesActivationFfi.ALREADY_ENABLED,
            )
        val reasons = (AccountDevicesActivationFfi.entries - without.toSet()).map(::accountDevicesActivationReason)

        without.forEach { assertNull(it.toString(), accountDevicesActivationReason(it)) }
        assertFalse(reasons.any { it == null })
        assertEquals(reasons.size, reasons.toSet().size)
    }

    /** A refusal at commit time reports MDK's reason; any other failure keeps the generic retry copy. */
    @Test
    fun aRefusalAtCommitReportsMdksReason() {
        val reason = AccountDevicesActivationFfi.DEVICE_LIMIT_EXCEEDED
        val refused = MarmotKitException.AccountDevicesActivationRefused("group", reason)

        assertEquals(AppText.Resource(R.string.group_account_devices_limit), accountDevicesRefusalDetail(refused))
        assertEquals(
            AppText.Resource(R.string.error_try_again),
            accountDevicesRefusalDetail(MarmotKitException.TransportClosed()),
        )
    }

    /** The confirmation says the change cannot be undone, and only its confirm button proceeds. */
    @Test
    fun theConfirmationSaysItCannotBeUndone() {
        var confirms = 0
        var dismissals = 0
        compose.setContent {
            WhiteNoiseTheme {
                GroupAccountDevicesConfirmDialog(onConfirm = { confirms++ }, onDismiss = { dismissals++ })
            }
        }

        compose.onNodeWithText(context.getString(R.string.group_account_devices_confirm_message)).assertExists()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(0 to 1, confirms to dismissals) }
        compose.onNodeWithText(context.getString(R.string.group_account_devices_confirm)).performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }

    /** Only the account-wide unlink event names an unlinked profile. */
    @Test
    fun onlyTheUnlinkEventNamesAnUnlinkedProfile() {
        assertEquals("work", deviceUnlinkedAccount(MarmotEventFfi.DeviceUnlinked("id", "work")))
        assertNull(deviceUnlinkedAccount(MarmotEventFfi.LinkedDevicesChanged("id", "work")))
        assertNull(deviceUnlinkedAccount(MarmotEventFfi.DeviceLinkChanged("id", "work")))
    }

    /** After an unlink the app moves to another signed-in profile, never to a signed-out one or back to the same. */
    @Test
    fun theNextProfileIsOneStillSignedIn() {
        val accounts = listOf("work" to false, "old" to true, "home" to false)

        assertEquals("home", nextSignedInAccount(accounts, unlinked = "work"))
        assertNull(nextSignedInAccount(listOf("work" to false, "old" to true), unlinked = "work"))
    }

    /** Signing in to an unlinked account explains the unlink instead of suggesting a retry. */
    @Test
    fun signingInToAnUnlinkedAccountExplainsTheUnlink() {
        val unlinked = MarmotKitException.AccountUnlinked("work")
        val detail = failureDetail(unlinked, AppText.Resource(R.string.error_try_again))

        assertEquals(AppText.Resource(R.string.error_account_unlinked), detail)
    }

    /** Chats handed to the account's other devices are reported apart from the chats this device left. */
    @Test
    fun chatsHandedToOtherDevicesAreNotCountedAsLeft() {
        val report = wipeReport(outcome(groupsLeft = 2u, groupsLeftToSiblings = 5u))

        assertEquals(2, report.stages.first().completedCount)
        assertEquals(5, report.chatsLeftToOtherDevices)
        assertTrue(report.clean)
    }

    /** A sign-out that could not reach the other devices is not clean, even with no failed chat. */
    @Test
    fun aSignOutThatCouldNotReachTheOtherDevicesIsNotClean() {
        val report = wipeReport(outcome(siblingsNotReached = true))

        assertTrue(report.otherDevicesNotReached)
        assertFalse(report.clean)
        assertEquals(1, report.issueCount)
    }

    /** Failed chats and an unreached handoff are counted together. */
    @Test
    fun failuresAndAnUnreachedHandoffAddUp() {
        val failure = GroupLeaveFailureFfi("a".repeat(64), "not removed")
        val report = wipeReport(outcome(siblingsNotReached = true, failures = listOf(failure)))

        assertEquals(2, report.issueCount)
    }

    /** Renders the linked-devices rows of a group for [activation]. */
    private fun rows(
        activation: AccountDevicesActivationFfi?,
        onEnableRequested: () -> Unit = {},
    ) {
        compose.setContent {
            WhiteNoiseTheme {
                SettingsGroup { groupAccountDevicesRows(activation, enabled = true, onEnableRequested) }
            }
        }
    }

    /** A wipe outcome with the given leave and handoff results and a completed local cleanup. */
    private fun outcome(
        groupsLeft: UInt = 0u,
        groupsLeftToSiblings: UInt = 0u,
        siblingsNotReached: Boolean = false,
        failures: List<GroupLeaveFailureFfi> = emptyList(),
    ) = WipeOutcomeFfi(
        groupsLeft = groupsLeft,
        groupLeaveFailures = failures,
        keyPackagesDeleted = 1u,
        keyPackageFailures = emptyList(),
        localCleanup = LocalCleanupReportFfi(completed = true, reason = null),
        groupsLeftToSiblings = groupsLeftToSiblings,
        siblingsNotReached = siblingsNotReached,
    )
}
