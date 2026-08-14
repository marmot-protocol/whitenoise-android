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
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
    fun threeAccountAdjacentUnreadDots_areNotOccludedByLaterStackedAvatars() {
        renderTopBar(
            appState =
                testAppState(accountCount = 3).also { state ->
                    state.updateAccountUnreadCount("account-2", 1uL)
                    state.updateAccountUnreadCount("account-3", 1uL)
                },
        )

        assertUnreadDotFullyOwnedByAvatar(
            dotTag = otherAccountUnreadDotTag("account-2"),
            ownerAvatarTag = otherAccountAvatarTag("account-2"),
            rightNeighborAvatarTag = otherAccountAvatarTag("account-3"),
        )
        assertUnreadDotFullyOwnedByAvatar(
            dotTag = otherAccountUnreadDotTag("account-3"),
            ownerAvatarTag = otherAccountAvatarTag("account-3"),
            leftNeighborAvatarTag = otherAccountAvatarTag("account-2"),
        )
    }

    @Test
    fun rtl_threeAccountAdjacentUnreadDots_areNotOccludedByLaterStackedAvatars() {
        renderTopBar(
            appState =
                testAppState(accountCount = 3).also { state ->
                    state.updateAccountUnreadCount("account-2", 1uL)
                    state.updateAccountUnreadCount("account-3", 1uL)
                },
            rtl = true,
        )

        assertUnreadDotFullyOwnedByAvatar(
            dotTag = otherAccountUnreadDotTag("account-2"),
            ownerAvatarTag = otherAccountAvatarTag("account-2"),
            rightNeighborAvatarTag = otherAccountAvatarTag("account-3"),
        )
        assertUnreadDotFullyOwnedByAvatar(
            dotTag = otherAccountUnreadDotTag("account-3"),
            ownerAvatarTag = otherAccountAvatarTag("account-3"),
            leftNeighborAvatarTag = otherAccountAvatarTag("account-2"),
        )
    }

    @Test
    fun activeAccountUnread_announcesUnreadOnSettingsAction() {
        renderTopBar(
            appState =
                testAppState(accountCount = 2).also { state ->
                    state.updateAccountUnreadCount("personal", 2uL)
                },
        )

        composeRule
            .onNode(
                hasContentDescription(context.getString(R.string.open_settings), substring = true) and
                    hasContentDescription(unreadDescription, substring = true) and
                    hasClickAction(),
                useUnmergedTree = true,
            ).assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithTag(otherAccountUnreadDotTag("account-2")).assertDoesNotExist()
    }

    @Test
    fun otherAccountUnread_announcesUnreadOnSwitchAction() {
        renderTopBar(
            appState =
                testAppState(accountCount = 2).also { state ->
                    state.updateAccountUnreadCount("account-2", 1uL)
                },
        )

        composeRule
            .onNode(
                hasContentDescription(unreadDescription, substring = true) and hasClickAction(),
            ).assertIsDisplayed()
            .assertHasClickAction()
        assertUnreadDotFullyOwnedByAvatar(
            dotTag = otherAccountUnreadDotTag("account-2"),
            ownerAvatarTag = otherAccountAvatarTag("account-2"),
        )
    }

    @Test
    fun readAccounts_renderNoUnreadDots() {
        renderTopBar(appState = testAppState(accountCount = 3))

        composeRule
            .onNodeWithContentDescription(unreadDescription, substring = true)
            .assertDoesNotExist()
        composeRule.onNodeWithTag(otherAccountUnreadDotTag("account-2")).assertDoesNotExist()
        composeRule.onNodeWithTag(otherAccountUnreadDotTag("account-3")).assertDoesNotExist()
    }

    @Test
    fun switchingActiveAccount_movesUnreadDotOwnership() {
        val appStateHolder =
            mutableStateOf(
                testAppState(accountCount = 3, activeAccountRef = "personal").also { state ->
                    state.updateAccountUnreadCount("account-2", 1uL)
                },
            )

        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                WhiteNoiseTheme {
                    val appState = appStateHolder.value
                    Box(Modifier.testTag(HARNESS_TAG)) {
                        ChatListTopBar(
                            appState = appState,
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
        }
        composeRule.waitForIdle()

        assertUnreadDotFullyOwnedByAvatar(
            dotTag = otherAccountUnreadDotTag("account-2"),
            ownerAvatarTag = otherAccountAvatarTag("account-2"),
        )
        composeRule
            .onNode(
                hasContentDescription(context.getString(R.string.open_settings), substring = true) and
                    hasContentDescription(unreadDescription, substring = true) and
                    hasClickAction(),
            ).assertDoesNotExist()

        composeRule.runOnUiThread {
            appStateHolder.value =
                testAppState(accountCount = 3, activeAccountRef = "account-2").also { state ->
                    state.updateAccountUnreadCount("account-2", 1uL)
                }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(otherAccountUnreadDotTag("account-2")).assertDoesNotExist()
        composeRule.onNodeWithTag(otherAccountUnreadDotTag("personal")).assertDoesNotExist()
        composeRule
            .onNode(
                hasContentDescription(context.getString(R.string.open_settings), substring = true) and
                    hasContentDescription(unreadDescription, substring = true) and
                    hasClickAction(),
            ).assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun overflowUnreadDot_isNotCoveredByChip() {
        renderTopBar(
            appState =
                testAppState(accountCount = 5).also { state ->
                    state.updateAccountUnreadCount("account-4", 1uL)
                },
        )

        assertUnreadDotFullyOwnedByAvatar(
            dotTag = otherAccountUnreadDotTag("account-4"),
            ownerAvatarTag = otherAccountAvatarTag("account-4"),
            leftNeighborAvatarTag = otherAccountAvatarTag("account-3"),
            rightNeighborBounds =
                composeRule
                    .onNodeWithContentDescription(context.getString(R.string.switch_account))
                    .fetchSemanticsNode()
                    .boundsInRoot,
        )
    }

    @Test
    fun oneAccount_rendersNoOtherAccountUnreadDots() {
        renderTopBar(
            appState =
                testAppState(accountCount = 1).also { state ->
                    state.updateAccountUnreadCount("personal", 1uL)
                },
        )

        composeRule.onNodeWithTag(otherAccountUnreadDotTag("account-2")).assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(unreadDescription, substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    private fun renderTopBar(
        appState: WhiteNoiseAppState,
        rtl: Boolean = false,
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
                            onOpenSettings = {},
                            onSwitchAccount = {},
                            connectivityState = ConnectivityBannerState.Hidden,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun boundsForTag(tag: String): Rect =
        composeRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun assertUnreadDotFullyOwnedByAvatar(
        dotTag: String,
        ownerAvatarTag: String,
        leftNeighborAvatarTag: String? = null,
        rightNeighborAvatarTag: String? = null,
        rightNeighborBounds: Rect? = null,
    ) {
        val dotBounds =
            composeRule
                .onNodeWithTag(dotTag)
                .fetchSemanticsNode()
                .boundsInRoot
        val ownerBounds = boundsForTag(ownerAvatarTag)

        val minimumVisibleExtent = with(composeRule.density) { 6.dp.toPx() }
        assertTrue(
            "$dotTag must keep a visible extent",
            dotBounds.width >= minimumVisibleExtent && dotBounds.height >= minimumVisibleExtent,
        )
        assertTrue(
            "$dotTag must lie wholly inside $ownerAvatarTag",
            ownerBounds.left <= dotBounds.left &&
                ownerBounds.top <= dotBounds.top &&
                ownerBounds.right >= dotBounds.right &&
                ownerBounds.bottom >= dotBounds.bottom,
        )

        leftNeighborAvatarTag?.let { neighborTag ->
            val neighborBounds = boundsForTag(neighborTag)
            assertFalse(
                "$dotTag must not intersect left neighbor $neighborTag",
                dotBounds.overlaps(neighborBounds),
            )
        }
        rightNeighborAvatarTag?.let { neighborTag ->
            val neighborBounds = boundsForTag(neighborTag)
            assertFalse(
                "$dotTag must not intersect right neighbor $neighborTag",
                dotBounds.overlaps(neighborBounds),
            )
        }
        rightNeighborBounds?.let { neighborBounds ->
            assertFalse(
                "$dotTag must not intersect right overflow chip",
                dotBounds.overlaps(neighborBounds),
            )
        }
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
