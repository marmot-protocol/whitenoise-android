package dev.ipf.whitenoise.android.ui.account

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class AccountSwitcherUnreadDotLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val unreadDescription
        get() = context.getString(R.string.account_unread_indicator)

    @Test
    fun stackRoutesEveryVisibleSliceToItsAccountInLtrAndRtl() {
        val width = 78f
        val ltrExpected = listOf(0, 0, 1, 1, 2, 2)
        val rtlExpected = ltrExpected.map { 2 - it }
        val positions = listOf(1f, 21f, 22f, 43f, 44f, 77f)

        assertEquals(
            ltrExpected,
            positions.map { accountStackTargetIndex(it, width, 3, LayoutDirection.Ltr) },
        )
        assertEquals(
            rtlExpected,
            positions.map { accountStackTargetIndex(it, width, 3, LayoutDirection.Rtl) },
        )
        assertEquals(1, accountStackTargetIndex(78f, width * 2f, 3, LayoutDirection.Ltr))
    }

    /** Selector rows receive rapid center taps without neighbor dispatch. */
    @Test
    fun selectorRowsReceiveRapidCenterTapsWithoutNeighborDispatch() {
        val switched = mutableListOf<String>()
        renderSelector(testAppState(accountCount = 4), onSelect = switched::add)
        val sheet = boundsForTag(ACCOUNT_SELECTOR_CONTENT_TAG)
        val centers =
            listOf("account-2", "account-3", "account-4").map { label ->
                boundsForTag(profileRowTag(label)).center - sheet.topLeft
            }
        composeRule.onNodeWithTag(ACCOUNT_SELECTOR_CONTENT_TAG).performTouchInput {
            centers.forEach { click(it) }
        }
        assertEquals(listOf("account-2", "account-3", "account-4"), switched)
    }

    @Test
    fun stackGesturesUseLatestCallbacksAfterCallbackOnlyRecomposition() {
        val appState = testAppState(accountCount = 2)
        val callbackGeneration = mutableStateOf(0)
        val switched = mutableListOf<String>()
        val opened = mutableListOf<Int>()

        composeRule.setContent {
            val generation = callbackGeneration.value
            WhiteNoiseTheme {
                OtherAccountAvatarsRow(
                    appState = appState,
                    onSwitchAccount = { accountRef -> switched += "$generation:$accountRef" },
                    onOpenSwitcher = { opened += generation },
                )
            }
        }
        composeRule.runOnIdle { callbackGeneration.value = 1 }

        composeRule.onNodeWithTag(OTHER_ACCOUNT_STACK_TAG).performTouchInput {
            click(center)
            longClick(center)
        }

        assertEquals(listOf("1:account-2"), switched)
        assertEquals(listOf(1), opened)
    }

    /** Selector exposes one named accessibility action per account and pinned destination. */
    @Test
    fun selectorExposesOneNamedAccessibilityActionPerAccountAndPinnedDestination() {
        val switched = mutableListOf<String>()
        var added = 0
        var settings = 0
        val app = testAppState(accountCount = 5)
        renderSelector(app, onSelect = switched::add, onAdd = { added++ }, onSettings = { settings++ })
        app.accounts.forEach { account ->
            val row =
                composeRule
                    .onNodeWithTag(profileRowTag(account.label))
                    .assertIsDisplayed()
                    .assertHasClickAction()
            row.assertTextContains(app.accountDisplayNameCached(account.accountIdHex))
            val action = row.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            composeRule.runOnIdle { action() }
        }
        composeRule.onNodeWithTag("profile_switcher.add_profile").assertHasClickAction().performClick()
        composeRule.onNodeWithTag("profile_switcher.settings").assertHasClickAction().performClick()
        assertEquals(app.accounts.map { it.label }, switched)
        assertEquals(1, added)
        assertEquals(1, settings)
    }

    /** Adjacent selector unread badges remain inside their own rows. */
    @Test
    fun adjacentSelectorUnreadBadgesRemainInsideTheirOwnRows() {
        renderSelector(
            testAppState(accountCount = 3).also {
                it.updateAccountUnreadCount("account-2", 1uL)
                it.updateAccountUnreadCount("account-3", 1uL)
            },
            rtl = false,
        )
        assertUnreadBadgeOwnedByRow("account-2", neighbor = "account-3")
        assertUnreadBadgeOwnedByRow("account-3", neighbor = "account-2")
    }

    /** Rtl adjacent selector unread badges remain inside their own rows. */
    @Test
    fun rtlAdjacentSelectorUnreadBadgesRemainInsideTheirOwnRows() {
        renderSelector(
            testAppState(accountCount = 3).also {
                it.updateAccountUnreadCount("account-2", 1uL)
                it.updateAccountUnreadCount("account-3", 1uL)
            },
            rtl = true,
        )
        assertUnreadBadgeOwnedByRow("account-2", neighbor = "account-3")
        assertUnreadBadgeOwnedByRow("account-3", neighbor = "account-2")
    }

    /** Active account unread announces unread on the profile selector action. */
    @Test
    fun activeAccountUnreadAnnouncesUnreadOnTheProfileSelectorAction() {
        renderTopBar(testAppState(accountCount = 2).also { it.updateAccountUnreadCount("personal", 2uL) })
        composeRule
            .onNode(
                hasContentDescription(context.getString(R.string.switch_profile), substring = true) and
                    hasContentDescription(unreadDescription, substring = true) and hasClickAction(),
                useUnmergedTree = true,
            ).assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithTag(OTHER_ACCOUNT_STACK_TAG).assertDoesNotExist()
    }

    /** Active unread marker remains fully inside the circular button clip in LTR. */
    @Test
    fun activeAccountUnreadMarkerRemainsInsideButtonClipInLtr() {
        assertActiveAccountUnreadMarkerInsideButtonClip(rtl = false)
    }

    /** Active unread marker remains fully inside the circular button clip in RTL. */
    @Test
    fun activeAccountUnreadMarkerRemainsInsideButtonClipInRtl() {
        assertActiveAccountUnreadMarkerInsideButtonClip(rtl = true)
    }

    /** Other account unread announces its count on the actual selector row. */
    @Test
    fun otherAccountUnreadAnnouncesItsCountOnTheActualSelectorRow() {
        renderTopBar(testAppState(accountCount = 2).also { it.updateAccountUnreadCount("account-2", 1uL) })
        composeRule.onNodeWithContentDescription(unreadDescription, substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("chats.switchProfile").performClick()
        val countDescription = context.resources.getQuantityString(R.plurals.unread_messages_count, 1, 1)
        composeRule
            .onNode(
                hasTestTag(profileRowTag("account-2")) and hasContentDescription(countDescription, substring = true),
            ).assertIsDisplayed()
            .assertHasClickAction()
        assertUnreadBadgeOwnedByRow("account-2", neighbor = "personal")
    }

    /** Read accounts render no unread dots. */
    @Test
    fun readAccounts_renderNoUnreadDots() {
        renderTopBar(appState = testAppState(accountCount = 3))

        composeRule
            .onNodeWithContentDescription(unreadDescription, substring = true)
            .assertDoesNotExist()
        composeRule
            .onNodeWithTag(otherAccountUnreadDotTag("account-2"), useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule
            .onNodeWithTag(otherAccountUnreadDotTag("account-3"), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    /** Switching active account moves unread ownership from selector row to active avatar. */
    @Suppress("LongMethod")
    @Test
    fun switchingActiveAccountMovesUnreadOwnershipFromSelectorRowToActiveAvatar() {
        val appStateHolder =
            mutableStateOf(
                testAppState(accountCount = 3).also { it.updateAccountUnreadCount("account-2", 1uL) },
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                val app = appStateHolder.value
                Box(Modifier.testTag(HARNESS_TAG)) {
                    ChatListTopBar(
                        app,
                        false,
                        "",
                        remember { FocusRequester() },
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        connectivityState = ConnectivityBannerState.Hidden,
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription(unreadDescription, substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("chats.switchProfile").performClick()
        assertUnreadBadgeOwnedByRow("account-2", neighbor = "personal")
        composeRule.runOnIdle {
            appStateHolder.value =
                testAppState(accountCount = 3, activeAccountRef = "account-2").also {
                    it.updateAccountUnreadCount("account-2", 1uL)
                }
        }
        composeRule.onNodeWithTag(profileRowTag("account-2")).assertIsSelected()
        composeRule
            .onNode(
                hasContentDescription(context.resources.getQuantityString(R.plurals.unread_messages_count, 1, 1)) and
                    hasAnyAncestor(hasTestTag(profileRowTag("account-2"))),
                useUnmergedTree = true,
            ).assertDoesNotExist()
        composeRule.onNodeWithTag("profile_switcher.settings").performClick()
        composeRule
            .onNode(
                hasContentDescription(context.getString(R.string.switch_profile), substring = true) and
                    hasContentDescription(unreadDescription, substring = true) and hasClickAction(),
            ).assertIsDisplayed()
            .assertHasClickAction()
    }

    /** Last selector unread badge is not covered by another row or pinned actions. */
    @Test
    fun lastSelectorUnreadBadgeIsNotCoveredByAnotherRowOrPinnedActions() {
        renderSelector(testAppState(accountCount = 5).also { it.updateAccountUnreadCount("account-5", 1uL) })
        assertUnreadBadgeOwnedByRow("account-5", neighbor = "account-4")
        val badge = unreadBadgeBounds("account-5")
        assertFalse(badge.overlaps(boundsForTag("profile_switcher.add_profile")))
        assertFalse(badge.overlaps(boundsForTag("profile_switcher.settings")))
    }

    @Test
    fun oneAccount_rendersNoOtherAccountUnreadDots() {
        renderTopBar(
            appState =
                testAppState(accountCount = 1).also { state ->
                    state.updateAccountUnreadCount("personal", 1uL)
                },
        )

        composeRule
            .onNodeWithTag(otherAccountUnreadDotTag("account-2"), useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(unreadDescription, substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun coldIdentityAccountLabelsDoNotAccessMarmotDuringTopBarComposition() {
        var marmotAccesses = 0
        val activeId = "aa".repeat(32)
        val otherId = "bb".repeat(32)
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(InMemoryDraftPersistence()),
                accountIdHexResolver = { activeId },
                accounts =
                    listOf(
                        accountSummary(label = activeId, accountIdHex = activeId),
                        accountSummary(label = otherId, accountIdHex = otherId),
                    ),
                activeAccountRef = activeId,
                profileReader = { null },
                profileDisplayNameReader = { null },
                profileRefreshRequest = {},
                marmotAccessObserver = { marmotAccesses += 1 },
            )

        renderTopBar(appState)

        assertEquals(0, marmotAccesses)
    }

    /** Renders top bar. */
    private fun renderTopBar(
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
                        ChatListTopBar(
                            appState = remember { appState },
                            searchOpen = false,
                            searchQuery = "",
                            searchFocusRequester = remember { FocusRequester() },
                            onSearchQueryChange = {},
                            onSearchOpen = {},
                            onSearchClose = {},
                            onMic = {},
                            onOpenSettings = onOpenSwitcher,
                            onSwitchAccount = onSwitchAccount,
                            connectivityState = ConnectivityBannerState.Hidden,
                        )
                    }
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

    /** Asserts the active marker is visible within the button's circular draw boundary. */
    private fun assertActiveAccountUnreadMarkerInsideButtonClip(rtl: Boolean) {
        renderTopBar(
            appState = testAppState(accountCount = 1).also { it.updateAccountUnreadCount("personal", 1uL) },
            rtl = rtl,
        )

        val button = boundsForTag("chats.switchProfile")
        val marker = boundsForTag(ACTIVE_ACCOUNT_UNREAD_DOT_TAG)
        val buttonRadius = minOf(button.width, button.height) / 2f
        val markerRadius = minOf(marker.width, marker.height) / 2f
        val centerDistance = (marker.center - button.center).getDistance()

        assertTrue(
            "marker must stay inside the button bounds in rtl=$rtl",
            marker.left >= button.left &&
                marker.top >= button.top &&
                marker.right <= button.right &&
                marker.bottom <= button.bottom,
        )
        assertTrue(
            "marker must stay inside the circular button clip in rtl=$rtl",
            centerDistance + markerRadius <= buttonRadius,
        )
        if (rtl) {
            assertTrue("RTL marker must remain on the trailing edge", marker.center.x < button.center.x)
        } else {
            assertTrue("LTR marker must remain on the trailing edge", marker.center.x > button.center.x)
        }
        assertTrue("marker must remain on the lower edge", marker.center.y > button.center.y)
    }

    /** Test tag of a profile row. */
    private fun profileRowTag(label: String): String = "profile_switcher.profile.$label"

    /** Bounds of the unread badge node. */
    private fun unreadBadgeBounds(label: String): Rect =
        composeRule
            .onNode(
                hasContentDescription(context.resources.getQuantityString(R.plurals.unread_messages_count, 1, 1)) and
                    hasAnyAncestor(hasTestTag(profileRowTag(label))),
                useUnmergedTree = true,
            ).assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

    /** Asserts unread badge owned by row. */
    private fun assertUnreadBadgeOwnedByRow(
        label: String,
        neighbor: String,
    ) {
        val badge = unreadBadgeBounds(label)
        val owner = boundsForTag(profileRowTag(label))
        val minimumVisibleExtent = with(composeRule.density) { 6.dp.toPx() }
        assertTrue(
            "$label badge must remain visible",
            badge.width >= minimumVisibleExtent &&
                badge.height >= minimumVisibleExtent,
        )
        assertTrue(
            "$label badge must stay inside its row",
            owner.left <= badge.left &&
                owner.top <= badge.top &&
                owner.right >= badge.right &&
                owner.bottom >= badge.bottom,
        )
        assertFalse("$label badge must not overlap $neighbor", badge.overlaps(boundsForTag(profileRowTag(neighbor))))
    }

    /** Same selector presentation and native unread projection used by the header's sheet. */
    private fun renderSelector(
        app: WhiteNoiseAppState,
        rtl: Boolean = false,
        onSelect: (String) -> Unit = {},
        onAdd: () -> Unit = {},
        onSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme {
                    ProfileSwitcherSheet(
                        state = accountSelectorState(app.accounts, app.activeAccountRef, false),
                        displayName = app::accountDisplayNameCached,
                        shortNpub = { "npub…${it.take(4)}" },
                        avatarUrl = { null },
                        unreadCountForAccount = app::confirmedUnreadCountForAccount,
                        hasUnreadForAccount = app::accountShowsUnreadDot,
                        onSelectProfile = onSelect,
                        onAddProfile = onAdd,
                        onSettings = onSettings,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun testAppState(
        accountCount: Int,
        activeAccountRef: String = "personal",
    ): WhiteNoiseAppState =
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
            activeAccountRef = activeAccountRef,
        )

    private fun accountSummary(
        label: String,
        accountIdHex: String,
    ): AccountSummaryFfi =
        AccountSummaryFfi(
            label = label,
            accountIdHex = accountIdHex,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val HARNESS_TAG = "account-switcher-unread-dot-harness"
    }
}
