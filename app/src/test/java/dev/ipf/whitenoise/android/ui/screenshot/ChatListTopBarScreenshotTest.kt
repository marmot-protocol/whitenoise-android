package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_SEARCH_FILTERS_ACTION_TAG
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.chats.ConnectivityBannerState
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchChatFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchFilterControlsRow
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchSenderFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatListTopBarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun oneAccountConnectingDark() {
        render(accountCount = 1, dark = true, amoled = false)
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_one_account_connecting_dark.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun oneAccountOfflineDark() {
        render(
            accountCount = 1,
            dark = true,
            amoled = false,
            connectivityState = ConnectivityBannerState.Offline,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_one_account_offline_dark.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun threeAccountsConnectingDark() {
        render(accountCount = 3, dark = true, amoled = false)
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_three_accounts_connecting_dark.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun searchFiltersActionLight() {
        renderSearch(dark = false, amoled = false, fontScale = 1f, rtl = false, filterActionWithCount = true)
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_search_filters_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun searchFiltersActionDark() {
        renderSearch(dark = true, amoled = false, fontScale = 1f, rtl = false, filterActionWithCount = true)
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_search_filters_dark.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun searchFiltersActionAmoled() {
        renderSearch(dark = true, amoled = true, fontScale = 1f, rtl = false, filterActionWithCount = true)
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_search_filters_amoled.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun searchActiveChipsLargeText() {
        renderSearch(
            dark = false,
            amoled = false,
            fontScale = 2f,
            rtl = false,
            withActiveFilters = true,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_search_active_chips_large_text.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w240dp-h780dp-mdpi")
    fun searchActiveChipsNarrowWidth() {
        renderSearch(
            dark = false,
            amoled = false,
            fontScale = 1f,
            rtl = false,
            withActiveFilters = true,
            width = 240.dp,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_search_active_chips_narrow.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun searchActiveChipsRtl() {
        renderSearch(dark = false, amoled = false, fontScale = 1f, rtl = true, withActiveFilters = true)
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_search_active_chips_rtl.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun threeAccountsAdjacentUnreadLight() {
        renderUnreadAccounts(
            accountCount = 3,
            dark = false,
            amoled = false,
            unreadAccountRefs = setOf("account-2", "account-3"),
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_three_accounts_adjacent_unread_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun threeAccountsAdjacentUnreadDark() {
        renderUnreadAccounts(
            accountCount = 3,
            dark = true,
            amoled = false,
            unreadAccountRefs = setOf("account-2", "account-3"),
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_three_accounts_adjacent_unread_dark.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun threeAccountsAdjacentUnreadAmoled() {
        renderUnreadAccounts(
            accountCount = 3,
            dark = true,
            amoled = true,
            unreadAccountRefs = setOf("account-2", "account-3"),
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_three_accounts_adjacent_unread_amoled.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun activeAccountUnreadLight() {
        renderUnreadAccounts(
            accountCount = 2,
            dark = false,
            amoled = false,
            unreadAccountRefs = setOf("personal"),
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_active_account_unread_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun overflowUnreadLight() {
        renderUnreadAccounts(
            accountCount = 5,
            dark = false,
            amoled = false,
            unreadAccountRefs = setOf("account-4"),
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_overflow_unread_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun threeAccountsAdjacentUnreadLargeText() {
        renderUnreadAccounts(
            accountCount = 3,
            dark = false,
            amoled = false,
            fontScale = 2f,
            unreadAccountRefs = setOf("account-2", "account-3"),
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_three_accounts_adjacent_unread_large_text.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
    fun threeAccountsAdjacentUnreadRtl() {
        renderUnreadAccounts(
            accountCount = 3,
            dark = false,
            amoled = false,
            rtl = true,
            unreadAccountRefs = setOf("account-2", "account-3"),
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_three_accounts_adjacent_unread_rtl.png")
    }

    private fun render(
        accountCount: Int,
        dark: Boolean,
        amoled: Boolean,
        connectivityState: ConnectivityBannerState = ConnectivityBannerState.Connecting,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                Surface {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(TAG),
                    ) {
                        ChatListTopBar(
                            appState = remember { testAppState(accountCount) },
                            searchOpen = false,
                            searchQuery = "",
                            searchFocusRequester = remember { FocusRequester() },
                            onSearchQueryChange = {},
                            onSearchOpen = {},
                            onSearchClose = {},
                            onMic = {},
                            onOpenSettings = {},
                            onSwitchAccount = {},
                            connectivityState = connectivityState,
                        )
                    }
                }
            }
        }
    }

    private fun renderUnreadAccounts(
        accountCount: Int,
        dark: Boolean,
        amoled: Boolean,
        unreadAccountRefs: Set<String>,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Surface {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag(TAG),
                        ) {
                            ChatListTopBar(
                                appState =
                                    remember {
                                        testAppState(accountCount).also { state ->
                                            unreadAccountRefs.forEach { ref ->
                                                state.updateAccountUnreadCount(ref, 1uL)
                                            }
                                        }
                                    },
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
        }
    }

    private fun screenshotSearchState(
        withActiveFilters: Boolean,
        filterActionWithCount: Boolean,
    ): GlobalSearchState =
        GlobalSearchState(
            isOpen = true,
            query = if (withActiveFilters) "needle" else "search",
            chatFilters =
                if (withActiveFilters) {
                    setOf(GlobalSearchChatFilter("shell-chat-alice", "Alice"))
                } else {
                    emptySet()
                },
            senderFilters =
                if (withActiveFilters) {
                    setOf(GlobalSearchSenderFilter("shell-npub-bob", "Bob"))
                } else {
                    emptySet()
                },
            dateFilterSelection =
                when {
                    withActiveFilters -> GlobalSearchDateFilterSelection.Today
                    filterActionWithCount -> GlobalSearchDateFilterSelection.Last7Days
                    else -> GlobalSearchDateFilterSelection.AnyTime
                },
            contentFilterSelection =
                when {
                    withActiveFilters ->
                        GlobalSearchContentFilterSelection(
                            selectedKinds =
                                setOf(
                                    GlobalSearchContentKind.IMAGES_VIDEO,
                                    GlobalSearchContentKind.LINKS,
                                ),
                        )
                    filterActionWithCount ->
                        GlobalSearchContentFilterSelection(setOf(GlobalSearchContentKind.TEXT))
                    else -> GlobalSearchContentFilterSelection.EMPTY
                },
        )

    private fun renderSearch(
        dark: Boolean,
        amoled: Boolean,
        fontScale: Float,
        rtl: Boolean,
        withActiveFilters: Boolean = false,
        filterActionWithCount: Boolean = false,
        width: androidx.compose.ui.unit.Dp = 360.dp,
    ) {
        val searchState = screenshotSearchState(withActiveFilters, filterActionWithCount)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Surface {
                        Column(
                            modifier =
                                Modifier
                                    .width(width)
                                    .testTag(TAG),
                        ) {
                            ChatListTopBar(
                                appState = remember { testAppState(1) },
                                searchOpen = searchState.isOpen,
                                searchQuery = searchState.query,
                                searchFocusRequester = remember { FocusRequester() },
                                onSearchQueryChange = {},
                                onSearchOpen = {},
                                onSearchClose = {},
                                onMic = {},
                                onOpenSettings = {},
                                onSwitchAccount = {},
                                connectivityState = ConnectivityBannerState.Hidden,
                            )
                            GlobalSearchFilterControlsRow(
                                state = searchState,
                                onOpenFilters = {},
                                onRemoveFilter = {},
                                onClearAll = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTERS_ACTION_TAG).assertExists()
    }

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
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val TAG = "chat-list-top-bar-screenshot"
    }
}
