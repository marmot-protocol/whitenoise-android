package dev.ipf.whitenoise.android.ui.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.chats.ConnectivityBannerState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Quick-switch avatars are separated, independently tappable targets (#2796).
 *
 * The parent-owned hit map PR #2217 introduced is preserved; what changes is that every target now
 * owns one Material-width slot, so adjacent avatars never overlap and a slice never spans two of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class QuickSwitchTargetSpacingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Three visible avatars keep visible air between them at normal phone width. */
    @Test
    fun threeVisibleAvatarsKeepVisibleSeparationAtNormalWidth() {
        renderTopBar(testAppState(accountCount = 4))
        val labels = listOf("account-2", "account-3", "account-4")
        val bounds = labels.map { boundsForTag(otherAccountAvatarTag(it)) }
        val minimumGap = with(composeRule.density) { 8.dp.toPx() }
        bounds.zipWithNext { leading, trailing ->
            assertTrue(
                "adjacent quick-switch avatars must not overlap",
                trailing.left >= leading.right,
            )
            assertTrue(
                "adjacent quick-switch avatars must be separated by at least 8dp",
                trailing.left - leading.right >= minimumGap,
            )
        }
    }

    /** Every visible avatar's centre and both edges dispatch only to its own account in LTR. */
    @Test
    fun threeVisibleAvatarsRouteCentreAndEdgeTapsToTheirOwnAccountInLtr() {
        assertAvatarEdgeTapsRouteToTheirOwnAccount(rtl = false, accountCount = 4)
    }

    /** Every visible avatar's centre and both edges dispatch only to its own account in RTL. */
    @Test
    fun threeVisibleAvatarsRouteCentreAndEdgeTapsToTheirOwnAccountInRtl() {
        assertAvatarEdgeTapsRouteToTheirOwnAccount(rtl = true, accountCount = 4)
    }

    /** Two visible avatars route their own centre and edge taps on a narrow bar in LTR. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun twoVisibleAvatarsRouteCentreAndEdgeTapsToTheirOwnAccountInLtr() {
        assertAvatarEdgeTapsRouteToTheirOwnAccount(rtl = false, accountCount = 3)
    }

    /** Two visible avatars route their own centre and edge taps on a narrow bar in RTL. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun twoVisibleAvatarsRouteCentreAndEdgeTapsToTheirOwnAccountInRtl() {
        assertAvatarEdgeTapsRouteToTheirOwnAccount(rtl = true, accountCount = 3)
    }

    /** Consecutive taps across the whole stack in one gesture stream never leak into a neighbour. */
    @Test
    fun rapidTapsAcrossTheStackStayWithTheirOwnAccounts() {
        val switched = mutableListOf<String>()
        renderStack(testAppState(accountCount = 4), onSwitchAccount = switched::add)
        val labels = listOf("account-2", "account-3", "account-4")
        val stack = boundsForTag(OTHER_ACCOUNT_STACK_TAG)
        val centers = labels.map { boundsForTag(otherAccountAvatarTag(it)).center - stack.topLeft }
        composeRule.onNodeWithTag(OTHER_ACCOUNT_STACK_TAG).performTouchInput {
            centers.forEach { click(it) }
            centers.reversed().forEach { click(it) }
        }
        assertEquals(labels + labels.reversed(), switched)
    }

    /** A long press on any avatar opens the full switcher instead of switching accounts. */
    @Test
    fun longPressOnAnyAvatarOpensTheFullSwitcher() {
        val switched = mutableListOf<String>()
        var opened = 0
        renderStack(
            testAppState(accountCount = 4),
            onSwitchAccount = switched::add,
            onOpenSwitcher = { opened++ },
        )
        val stack = boundsForTag(OTHER_ACCOUNT_STACK_TAG)
        listOf("account-2", "account-4").forEach { label ->
            composeRule.onNodeWithTag(OTHER_ACCOUNT_STACK_TAG).performTouchInput {
                longClick(boundsForTag(otherAccountAvatarTag(label)).center - stack.topLeft)
            }
        }
        assertEquals(2, opened)
        assertTrue("long press must never switch accounts", switched.isEmpty())
    }

    /** A narrow bar shows fewer avatars plus the overflow chip, which routes to the full switcher. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun narrowBarCollapsesSurplusAccountsIntoTheOverflowChip() {
        val switched = mutableListOf<String>()
        var opened = 0
        renderStack(
            testAppState(accountCount = 5),
            onSwitchAccount = switched::add,
            onOpenSwitcher = { opened++ },
        )
        composeRule.onNodeWithTag(otherAccountAvatarTag("account-2"), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(otherAccountAvatarTag("account-3"), useUnmergedTree = true).assertDoesNotExist()
        val stack = boundsForTag(OTHER_ACCOUNT_STACK_TAG)
        val chip = boundsForTag(OTHER_ACCOUNT_OVERFLOW_TAG)
        val lastAvatar = boundsForTag(otherAccountAvatarTag("account-2"))
        assertTrue("the overflow chip must not overlap the last avatar", chip.left >= lastAvatar.right)
        composeRule.onNodeWithTag(OTHER_ACCOUNT_STACK_TAG).performTouchInput { click(chip.center - stack.topLeft) }
        assertEquals(1, opened)
        assertTrue("the overflow chip must never switch accounts", switched.isEmpty())
    }

    /** At the narrowest supported width and largest font the stack never covers the other top-bar controls. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun narrowLargeFontStackDoesNotCoverTheOtherTopBarControls() {
        renderTopBar(
            testAppState(accountCount = 5).also { it.updateAccountUnreadCount("account-2", 1uL) },
            fontScale = 2f,
        )
        val stack = boundsForTag(OTHER_ACCOUNT_STACK_TAG)
        val activeAvatar = boundsForTag("chats.switchProfile")
        val harness = boundsForTag(HARNESS_TAG)
        assertFalse("the stack must not cover the active-account button", stack.overlaps(activeAvatar))
        assertTrue("the stack must stay inside the top bar", stack.right <= harness.right)
        val dot = boundsForTag(otherAccountUnreadDotTag("account-2"))
        val owner = boundsForTag(otherAccountAvatarTag("account-2"))
        assertTrue(
            "an unread dot must stay on its own avatar",
            dot.left >= owner.left && dot.right <= owner.right,
        )
    }

    /**
     * A pane narrower than the window still collapses to the overflow chip — capacity must come
     * from the bar's own measured width, not [androidx.compose.ui.platform.LocalWindowInfo]'s full
     * window (#2796 follow-up). The window here is wide enough that every avatar would fit if the
     * old window-width reading were still in effect; only the 320dp host pane is narrow.
     */
    @Test
    @Config(sdk = [36], qualifiers = "w800dp-h780dp-mdpi")
    fun narrowHostPaneInAWideWindowStillCollapsesToOverflow() {
        renderTopBar(testAppState(accountCount = 5), hostWidth = 320.dp)
        composeRule.onNodeWithTag(otherAccountAvatarTag("account-2"), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(otherAccountAvatarTag("account-3"), useUnmergedTree = true).assertDoesNotExist()
        val stack = boundsForTag(OTHER_ACCOUNT_STACK_TAG)
        val chip = boundsForTag(OTHER_ACCOUNT_OVERFLOW_TAG)
        assertTrue(
            "the overflow chip must appear once the host pane, not the window, runs out of room",
            chip.left >= stack.left,
        )
    }

    /** Taps at each avatar's centre and both edges, in order, reach only that avatar's account. */
    private fun assertAvatarEdgeTapsRouteToTheirOwnAccount(
        rtl: Boolean,
        accountCount: Int,
    ) {
        val switched = mutableListOf<String>()
        renderStack(testAppState(accountCount = accountCount), rtl = rtl, onSwitchAccount = switched::add)
        val labels = (2..accountCount).map { "account-$it" }
        val stack = boundsForTag(OTHER_ACCOUNT_STACK_TAG)
        val expected = mutableListOf<String>()
        labels.forEach { label ->
            val avatar = boundsForTag(otherAccountAvatarTag(label))
            listOf(avatar.left + 1f, avatar.center.x, avatar.right - 1f).forEach { x ->
                composeRule.onNodeWithTag(OTHER_ACCOUNT_STACK_TAG).performTouchInput {
                    click(Offset(x - stack.left, avatar.center.y - stack.top))
                }
                expected += label
            }
        }
        assertEquals(expected, switched)
    }

    /** Renders the quick-switch stack on its own so its switch and switcher callbacks are observable. */
    private fun renderStack(
        appState: WhiteNoiseAppState,
        rtl: Boolean = false,
        onSwitchAccount: (String) -> Unit = {},
        onOpenSwitcher: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme {
                    Box(Modifier.testTag(HARNESS_TAG)) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            OtherAccountAvatarsRow(
                                appState = remember { appState },
                                onSwitchAccount = onSwitchAccount,
                                onOpenSwitcher = onOpenSwitcher,
                                barWidthDp = maxWidth,
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * Renders the production chat-list header so the stack is measured beside the real top-bar
     * controls. [hostWidth], when given, hosts the bar in a pane narrower than the Robolectric
     * window itself — simulating a multi-pane layout where the bar is not the full window width.
     */
    private fun renderTopBar(
        appState: WhiteNoiseAppState,
        fontScale: Float = 1f,
        hostWidth: Dp? = null,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = fontScale) {
                Box(Modifier.testTag(HARNESS_TAG).let { if (hostWidth != null) it.width(hostWidth) else it }) {
                    ChatListTopBar(
                        appState = remember { appState },
                        searchOpen = false,
                        searchQuery = "",
                        searchFocusRequester = remember { FocusRequester() },
                        onSearchQueryChange = {},
                        onSearchOpen = {},
                        onSearchClose = {},
                        onMic = {},
                        onOpenSettings = {},
                        onSwitchAccount = {},
                        connectivityState = ConnectivityBannerState.Hidden,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Bounds of the node with the given tag. */
    private fun boundsForTag(tag: String): Rect =
        composeRule
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    /** App state carrying [accountCount] signed-in signing accounts, the first of them active. */
    private fun testAppState(accountCount: Int): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                (1..accountCount).map { index ->
                    AccountSummaryFfi(
                        label = if (index == 1) "personal" else "account-$index",
                        accountIdHex = index.toString(16).padStart(2, '0').repeat(32),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    )
                },
            activeAccountRef = "personal",
        )

    private class InMemoryDraftPersistence : DraftPersistence {
        /** No persisted drafts back this fixture. */
        override fun read(): Map<String, String> = emptyMap()

        /** Draft writes are discarded by this fixture. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val HARNESS_TAG = "quick-switch-target-spacing-harness"
    }
}
