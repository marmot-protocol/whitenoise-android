package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_SEARCH_FILTER_MENU_TAG
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchChatFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchFilterControlsRow
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchFilterMenu
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
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationSearchScopeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun scopeLight() = capture("conversation_search_scope_light", dark = false)

    @Test
    fun scopeDark() = capture("conversation_search_scope_dark", dark = true)

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun scopeLargeRtl() = capture("conversation_search_scope_large_rtl", dark = false, rtl = true)

    @Test
    fun filtersLight() = capture("conversation_search_filters_light", dark = false, menu = true)

    @Test
    fun filtersDark() = capture("conversation_search_filters_dark", dark = true, menu = true)

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun filtersLargeRtl() = capture("conversation_search_filters_large_rtl", dark = false, rtl = true, menu = true)

    private fun capture(
        name: String,
        dark: Boolean,
        rtl: Boolean = false,
        menu: Boolean = false,
    ) {
        val chat = GlobalSearchChatFilter("community", "Community")
        val state = GlobalSearchState(isOpen = true, chatFilters = setOf(chat))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (rtl) 2f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface {
                        if (menu) {
                            GlobalSearchFilterMenu(
                                expanded = true,
                                state = state,
                                onDismiss = {},
                                onCategory = {},
                                onClearAll = {},
                                conversationScope = true,
                            )
                        } else {
                            GlobalSearchFilterControlsRow(
                                state = state,
                                onRemoveFilter = {},
                                onClearAll = {},
                                conversationScope = chat,
                                onSearchOtherChats = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule
            .onNodeWithTag(
                if (menu) CHAT_LIST_SEARCH_FILTER_MENU_TAG else "search-scope-controls",
            ).captureRoboImage("src/test/snapshots/$name.png")
    }
}
