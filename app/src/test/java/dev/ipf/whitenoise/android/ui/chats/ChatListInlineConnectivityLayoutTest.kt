package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.account.OTHER_ACCOUNT_STACK_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val CHAT_LIST_TOP_BAR_TAG = "chat-list-top-bar"

private const val CHAT_LIST_CONTENT_ANCHOR_TAG = "chat-list-content-anchor"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListInlineConnectivityLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun connectingAndJustConnectedDoNotShiftContentBelowTopBar() {
        val connectivityState = mutableStateOf(ConnectivityBannerState.Hidden)
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListTopBarConnectivityHarness(
                    connectivityState = connectivityState.value,
                    appState = remember { testAppState() },
                )
            }
        }
        composeRule.waitForIdle()
        val hiddenY = contentAnchorTop()

        composeRule.runOnUiThread {
            connectivityState.value = ConnectivityBannerState.Connecting
        }
        composeRule.waitForIdle()
        val connectingY = contentAnchorTop()
        assertEquals(hiddenY, connectingY, POSITION_TOLERANCE)
        composeRule.onNodeWithText(context.getString(R.string.connectivity_connecting)).assertIsDisplayed()

        composeRule.runOnUiThread {
            connectivityState.value = ConnectivityBannerState.JustConnected
        }
        composeRule.waitForIdle()
        val justConnectedY = contentAnchorTop()
        assertEquals(hiddenY, justConnectedY, POSITION_TOLERANCE)
        composeRule.onNodeWithText(context.getString(R.string.connectivity_connected)).assertIsDisplayed()
    }

    @Test
    fun connectingIndicatorRendersInsideTopBarNotBelowIt() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListTopBarConnectivityHarness(
                    connectivityState = ConnectivityBannerState.Connecting,
                    appState = remember { testAppState() },
                )
            }
        }
        composeRule.waitForIdle()

        val topBarBounds = composeRule.onNodeWithTag(CHAT_LIST_TOP_BAR_TAG).fetchSemanticsNode().boundsInRoot
        val connectingBounds =
            composeRule
                .onNodeWithTag(CHAT_LIST_INLINE_CONNECTIVITY_TAG)
                .fetchSemanticsNode()
                .boundsInRoot

        assertTrue(connectingBounds.top >= topBarBounds.top - POSITION_TOLERANCE)
        assertTrue(connectingBounds.bottom <= topBarBounds.bottom + POSITION_TOLERANCE)
        composeRule.onNodeWithText(context.getString(R.string.connectivity_connecting)).assertIsDisplayed()
    }

    @Test
    fun connectingIndicatorKeepsMinimumGapAfterSingleAccountAvatar() {
        renderConnectingTopBar(accountCount = 1)

        val activeAccountAvatar =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.open_settings), substring = true)
                .fetchSemanticsNode()
                .boundsInRoot

        assertMinimumAvatarConnectivityGap(activeAccountAvatar.right)
    }

    @Test
    fun connectingIndicatorKeepsMinimumGapWithOneOtherAccount() {
        renderConnectingTopBar(accountCount = 2)

        val otherAccountAvatars =
            composeRule
                .onNodeWithTag(CHAT_LIST_OTHER_ACCOUNT_AVATARS_TAG)
                .fetchSemanticsNode()
                .boundsInRoot

        assertMinimumAvatarConnectivityGap(otherAccountAvatars.right)
    }

    @Test
    fun connectingIndicatorKeepsMinimumGapAfterEveryAccountAvatar() {
        renderConnectingTopBar(accountCount = 3)

        val otherAccountAvatars =
            composeRule
                .onNodeWithTag(CHAT_LIST_OTHER_ACCOUNT_AVATARS_TAG)
                .fetchSemanticsNode()
                .boundsInRoot

        assertMinimumAvatarConnectivityGap(otherAccountAvatars.right)
    }

    @Test
    fun searchModeHidesInlineConnectivityChrome() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListTopBarConnectivityHarness(
                    connectivityState = ConnectivityBannerState.Connecting,
                    appState = remember { testAppState() },
                    searchOpen = true,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CHAT_LIST_INLINE_CONNECTIVITY_TAG).assertIsNotDisplayed()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun compactLargeTextKeepsAccountOverflowAndSearchUsable() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                WhiteNoiseTheme {
                    ChatListTopBarConnectivityHarness(
                        connectivityState = ConnectivityBannerState.Connecting,
                        appState = remember { testAppState(accountCount = 5) },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val connectivityBounds =
            composeRule
                .onNodeWithTag(CHAT_LIST_INLINE_CONNECTIVITY_TAG)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val overflowBounds =
            composeRule
                .onNodeWithTag(OTHER_ACCOUNT_STACK_TAG)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val searchBounds =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.chat_list_search_open))
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot

        assertTrue("account overflow must not overlap search", overflowBounds.right <= searchBounds.left)
        assertMinimumAvatarConnectivityGap(overflowBounds.right)
        assertTrue("connectivity must not overlap search", connectivityBounds.right <= searchBounds.left)
    }

    @Test
    fun offlineStateRendersFullWidthBannerBelowTopBar() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListTopBarConnectivityHarness(
                    connectivityState = ConnectivityBannerState.Offline,
                    appState = remember { testAppState() },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CHAT_LIST_OFFLINE_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.connectivity_offline)).assertIsDisplayed()
        val topBarBounds = composeRule.onNodeWithTag(CHAT_LIST_TOP_BAR_TAG).fetchSemanticsNode().boundsInRoot
        val offlineBounds =
            composeRule
                .onNodeWithTag(CHAT_LIST_OFFLINE_BANNER_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(offlineBounds.top >= topBarBounds.bottom - POSITION_TOLERANCE)
    }

    private fun renderConnectingTopBar(accountCount: Int) {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListTopBarConnectivityHarness(
                    connectivityState = ConnectivityBannerState.Connecting,
                    appState = remember { testAppState(accountCount) },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertMinimumAvatarConnectivityGap(avatarRight: Float) {
        val connectingLeft =
            composeRule
                .onNodeWithTag(CHAT_LIST_INLINE_CONNECTIVITY_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
                .left
        val minimumGap = with(composeRule.density) { 12.dp.toPx() }

        assertTrue(
            "avatar cluster must keep at least 12.dp before connectivity status",
            connectingLeft - avatarRight >= minimumGap - POSITION_TOLERANCE,
        )
    }

    private fun contentAnchorTop(): Float =
        composeRule
            .onNodeWithTag(CHAT_LIST_CONTENT_ANCHOR_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
            .top

    private fun testAppState(accountCount: Int = 1): WhiteNoiseAppState =
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
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val POSITION_TOLERANCE = 1f
    }
}

@androidx.compose.runtime.Composable
private fun ChatListTopBarConnectivityHarness(
    connectivityState: ConnectivityBannerState,
    appState: WhiteNoiseAppState,
    searchOpen: Boolean = false,
) {
    val searchFocusRequester = remember { FocusRequester() }
    Column {
        Box(Modifier.testTag(CHAT_LIST_TOP_BAR_TAG)) {
            ChatListTopBar(
                appState = appState,
                searchOpen = searchOpen,
                searchQuery = "",
                searchFocusRequester = searchFocusRequester,
                onSearchQueryChange = {},
                onSearchOpen = {},
                onSearchClose = {},
                onMic = {},
                onOpenSettings = {},
                onSwitchAccount = {},
                connectivityState = connectivityState,
            )
        }
        ChatListConnectivityBanner(displayed = connectivityState)
        Box(
            Modifier
                .testTag(CHAT_LIST_CONTENT_ANCHOR_TAG)
                .height(1.dp),
        )
    }
}
