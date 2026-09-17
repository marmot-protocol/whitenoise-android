package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.EditVersion
import dev.ipf.whitenoise.android.ui.chats.ConversationSearchTopBar
import dev.ipf.whitenoise.android.ui.conversation.MessageSelectionBar
import dev.ipf.whitenoise.android.ui.conversation.messages.EditHistoryDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Native reader-adjacent surfaces retain real history, selection state and IME/search callbacks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationReaderChromeScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var originalZone: TimeZone

    /** Keeps timestamp PNGs deterministic while exercising real localized native epoch-second formatting. */
    @Before fun setClockZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Restores process configuration for other screenshot fixtures. */
    @After fun restoreClockZone() {
        TimeZone.setDefault(originalZone)
    }

    /**
     * MarmotKit's own history stands in for the window's edits: its revisions render newest first with no
     * Original row, because the bubble already shows the edited body.
     */
    @Test fun historyLight() = captureHistory("conversation_edit_history_light", dark = false, authoritative = true)

    /** Large RTL fallback history keeps full revision text, the Original row and a reachable native Back. */
    @Test fun historyDarkLargeRtl() = captureHistory("conversation_edit_history_dark_large_rtl", dark = true)

    /** The compact field and disabled date affordance use the prototype light header. */
    @Test fun searchLight() = captureSearch("conversation_search_header_light", largeRtl = false)

    /** Large RTL search retains the real query and exposes all native navigation targets. */
    @Test fun searchLargeRtl() = captureSearch("conversation_search_header_large_rtl", largeRtl = true)

    /** Selection uses the prototype title while native count remains an accessible state. */
    @Test fun selectionDark() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(Modifier.fillMaxWidth().testTag("reader.chrome")) {
                    MessageSelectionBar(count = 3, onClose = {})
                }
            }
        }
        composeRule.onNodeWithText("Select messages").assertIsDisplayed()
        composeRule.onNodeWithTag("reader.chrome").captureRoboImage(
            "src/test/snapshots/conversation_selection_header_dark.png",
        )
    }

    /**
     * Uses accepted native versions with exact timestamps, never a prototype history controller. With
     * [authoritative] the dialog loads MarmotKit's complete history, which differs from the window's edits
     * and carries no original body.
     */
    private fun captureHistory(
        name: String,
        dark: Boolean,
        authoritative: Boolean = false,
    ) {
        var dismissed = 0
        val first = "First accepted revision"
        val latest = "Latest accepted revision. The complete historical text stays selectable and available."
        val engineHistory =
            listOf(
                EditVersion("engine-first", "Engine revision the loaded window never saw", 1_800_000_030uL),
                EditVersion("first", first, 1_800_000_060uL),
                EditVersion("latest", latest, 1_800_000_120uL),
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (dark) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = if (dark) 2f else 1f) {
                    EditHistoryDialog(
                        original = "Original message".takeUnless { authoritative },
                        originalTimestamp = 1_800_000_000uL,
                        editState =
                            EditState(
                                latest,
                                if (authoritative) 3 else 2,
                                listOf(
                                    EditVersion("first", first, 1_800_000_060uL),
                                    EditVersion("latest", latest, 1_800_000_120uL),
                                ),
                            ),
                        onDismissRequest = { dismissed++ },
                        loadAuthoritativeHistory = if (authoritative) ({ engineHistory }) else null,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val newest = composeRule.onNodeWithTag("message.history.version.2").fetchSemanticsNode().boundsInRoot
        val older = composeRule.onNodeWithTag("message.history.version.1").fetchSemanticsNode().boundsInRoot
        assertTrue(newest.top < older.top)
        composeRule.onNodeWithTag("message.history").captureRoboImage("src/test/snapshots/$name.png")
        if (authoritative) {
            composeRule.onNodeWithTag("message.history.version.3").assertIsDisplayed()
            composeRule.onAllNodesWithTag("message.history.version.0").assertCountEquals(0)
        } else {
            composeRule.onNodeWithTag("message.history.body.0").performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, dismissed)
    }

    /** Exercises the compact field's real edit, IME-next-match, clear and close callback wiring. */
    private fun captureSearch(
        name: String,
        largeRtl: Boolean,
    ) {
        val callbacks = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(fontScale = if (largeRtl) 2f else 1f) {
                    var query by remember { mutableStateOf("Native message") }
                    Surface(Modifier.fillMaxWidth().testTag("reader.chrome")) {
                        ConversationSearchTopBar(
                            query = query,
                            onQueryChange = { query = it },
                            onClear = {
                                query = ""
                                callbacks += "clear"
                            },
                            onClose = { callbacks += "close" },
                            onSearchAction = { callbacks += "search" },
                            focusRequester = remember { FocusRequester() },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("conversation.search.calendar").assertIsNotEnabled()
        composeRule.onNodeWithTag("reader.chrome").captureRoboImage("src/test/snapshots/$name.png")
        composeRule.onNodeWithTag("conversation.searchField").performTextReplacement("Updated query")
        composeRule.onNodeWithTag("conversation.searchField").performImeAction()
        composeRule.onNodeWithContentDescription("Clear search").performClick()
        composeRule.onNodeWithContentDescription("Close search").performClick()
        assertEquals(listOf("search", "clear", "close"), callbacks)
    }
}
