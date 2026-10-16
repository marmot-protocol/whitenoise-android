package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #3310 abandonment confirmation: review and failure states across themes, RTL and large text, plus its semantics. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupAbandonmentDialogScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** A direct-chat review in the light theme. */
    @Test fun dmReviewLight() = capture("dm_review_light", DM)

    /** A direct-chat review in the dark theme. */
    @Test fun dmReviewDark() = capture("dm_review_dark", DM, dark = true)

    /** A group reset review on AMOLED, keeping the group wording of the same forget. */
    @Test fun groupReviewAmoled() = capture("group_review_amoled", GROUP, dark = true, amoled = true)

    /** A long-named direct chat at 200% text in RTL keeps every line and both actions reachable. */
    @Test fun dmReviewLargeRtl() = capture("dm_review_large_rtl", LONG_DM, scale = 2f, rtl = true)

    /** A failed direct-chat attempt shows its readable error and a retry in the light theme. */
    @Test fun dmFailedLight() = capture("dm_failed_light", DM, status = FAILED)

    /** A failed group reset in the dark theme. */
    @Test fun groupFailedDark() = capture("group_failed_dark", GROUP, status = FAILED, dark = true)

    /** The leave-blocked escape path offered after a Leave that did not finish. */
    @Test
    fun leaveBlockedNoticeLight() = captureLeaveBlocked("light", isDm = true)

    /** The group's leave-blocked reset notice in the dark theme. */
    @Test
    fun leaveBlockedNoticeDark() = captureLeaveBlocked("group_dark", isDm = false, dark = true)

    /** The direct chat's leave-blocked notice at 200% text in RTL keeps its hint and action readable. */
    @Test
    fun leaveBlockedNoticeLargeRtl() = captureLeaveBlocked("large_rtl", isDm = true, scale = 2f, rtl = true)

    /** The sole-admin gate for a direct chat: Cancel and an error-coloured Abandon chat on this device. */
    @Test
    fun soleAdminDmLight() = captureSoleAdmin("dm_light", isDm = true)

    /** The sole-admin gate for a group at 200% text in RTL in the dark theme. */
    @Test
    fun soleAdminGroupLargeRtl() = captureSoleAdmin("group_large_rtl", isDm = false, dark = true, large = true)

    /** In the sole-admin gate Cancel only dismisses, and only the destructive action opens the abandonment. */
    @Test
    fun soleAdminCancelDismissesAndAbandonOpensTheReview() {
        var abandons = 0
        var dismissals = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                GroupSoleAdminLeaveDialog(
                    groupName = "Alice",
                    isDm = true,
                    onAbandon = { abandons++ },
                    onDismiss = { dismissals++ },
                )
            }
        }
        composeRule.onNodeWithText(app.getString(R.string.sole_admin_abandon_hint_dm)).assertIsDisplayed()
        composeRule.onNodeWithTag("chat_info.sole_admin_leave.cancel").performClick()
        assertEquals(0, abandons)
        assertEquals(1, dismissals)
        composeRule
            .onNodeWithTag("chat_info.sole_admin_leave.abandon")
            .assertTextEquals(app.getString(R.string.chat_abandon_action))
            .performClick()
        assertEquals(1, abandons)
    }

    /** The chat list's bulk review names every selected chat and the account, with both kinds' consequences. */
    @Test
    fun bulkMixedReviewLight() = captureBulk("light")

    /** The bulk review at 200% text in RTL in the dark theme keeps every chat, consequence and button reachable. */
    @Test
    fun bulkMixedReviewLargeRtl() = captureBulk("large_rtl", dark = true, large = true)

    /** The bulk review lists each chat with its own kind and confirms once for all of them. */
    @Test
    fun bulkReviewNamesEveryChatAndConfirmsOnce() {
        var confirms = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                GroupAbandonmentDialog(listOf(DM, GROUP), onConfirm = { confirms++ }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText(app.getString(R.string.chats_abandon_dialog_title)).assertIsDisplayed()
        listOf(
            app.getString(R.string.abandon_identity_chat, DM.conversationTitle),
            app.getString(R.string.abandon_identity_group, GROUP.conversationTitle),
            app.getString(R.string.abandon_identity_account, DM.accountName),
        ).forEach { composeRule.onNodeWithText(it).assertExists() }
        composeRule.onNodeWithTag("group.abandon.confirm").performClick()
        assertEquals(1, confirms)
    }

    /** The dialog names the action, the exact conversation and the acting account, and Cancel submits nothing. */
    @Test
    fun reviewNamesTheTargetAndCancelSubmitsNothing() {
        var confirms = 0
        var dismissals = 0
        render(DM, GroupAbandonmentStatus.Review, onConfirm = { confirms++ }, onDismiss = { dismissals++ })
        composeRule.onNodeWithText(app.getString(R.string.chat_abandon_dialog_title)).assertIsDisplayed()
        val chat = app.getString(R.string.abandon_identity_chat, DM.conversationTitle)
        val account = app.getString(R.string.abandon_identity_account, DM.accountName)
        composeRule.onNodeWithText(chat).assertIsDisplayed()
        composeRule.onNodeWithText(account).assertIsDisplayed()
        composeRule.onNodeWithTag("group.abandon.cancel").performClick()
        assertEquals(0, confirms)
        assertEquals(1, dismissals)
    }

    /** While the forget runs, confirm is disabled so repeated taps cannot submit again. */
    @Test
    fun runningDisablesConfirmAndFailureOffersRetry() {
        val status = mutableStateOf(GroupAbandonmentStatus.Running)
        var confirms = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                GroupAbandonmentDialog(DM, status.value, onConfirm = { confirms++ }, onDismiss = {})
            }
        }
        composeRule.onNodeWithTag("group.abandon.confirm").assertIsNotEnabled()
        status.value = GroupAbandonmentStatus.Failed
        composeRule.onNodeWithTag("group.abandon.error").assertIsDisplayed()
        composeRule.onNodeWithTag("group.abandon.confirm").assertIsEnabled().performClick()
        assertEquals(1, confirms)
    }

    /** Composes the leave-blocked notice under the requested appearance and records it. */
    private fun captureLeaveBlocked(
        name: String,
        isDm: Boolean,
        dark: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = scale) {
                    GroupLeaveBlockedNotice(isDm = isDm, enabled = true, onAbandon = {})
                }
            }
        }
        composeRule
            .onNodeWithTag("chat_info.leave_blocked")
            .captureRoboImage("src/test/snapshots/group_abandon_leave_blocked_$name.png")
    }

    /** Composes the bulk review of a direct chat and a group and records its window. */
    private fun captureBulk(
        name: String,
        dark: Boolean = false,
        large: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (large) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = if (large) 2f else 1f) {
                    GroupAbandonmentDialog(listOf(DM, GROUP), onConfirm = {}, onDismiss = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/group_abandon_bulk_mixed_$name.png")
    }

    /** Composes the sole-admin gate under the requested appearance and records its window. */
    private fun captureSoleAdmin(
        name: String,
        isDm: Boolean,
        dark: Boolean = false,
        large: Boolean = false,
    ) {
        val groupName = if (isDm) "Alice" else "Hiking club"
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (large) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = if (large) 2f else 1f) {
                    GroupSoleAdminLeaveDialog(groupName, isDm, onAbandon = {}, onDismiss = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/group_abandon_sole_admin_$name.png")
    }

    /** Composes the dialog under the requested appearance and records its window. */
    private fun capture(
        name: String,
        target: GroupAbandonmentTarget,
        status: GroupAbandonmentStatus = GroupAbandonmentStatus.Review,
        dark: Boolean = false,
        amoled: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
    ) {
        render(target, status, dark = dark, amoled = amoled, scale = scale, rtl = rtl)
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/group_abandon_$name.png")
    }

    /** Sets the dialog content with its appearance and callbacks. */
    @Suppress("LongParameterList") // Test appearance switches mirror the screenshot matrix.
    private fun render(
        target: GroupAbandonmentTarget,
        status: GroupAbandonmentStatus,
        onConfirm: () -> Unit = {},
        onDismiss: () -> Unit = {},
        dark: Boolean = false,
        amoled: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    GroupAbandonmentDialog(target, status, onConfirm, onDismiss)
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        val DM = GroupAbandonmentTarget("account-a", "Carol", "group-dm", "Alice", isDm = true)
        val GROUP = GroupAbandonmentTarget("account-a", "Carol", "group-hiking", "Hiking club", isDm = false)
        val LONG_DM = DM.copy(conversationTitle = "Alexandria Montgomery-Fitzwilliam (work phone, second account)")
        val FAILED = GroupAbandonmentStatus.Failed
    }
}
