@file:Suppress("MaxLineLength")

package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures the real entry/paging chrome at empty, partial, failure and terminal boundaries. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class MediaLibraryScreenshotTest {
    private lateinit var inputMode: InputModeManager

    @get:Rule val composeRule = createComposeRule()

    /** No loaded messages are required to discover the attachment library. */
    @Test
    fun historyEntryWithoutRecentAttachments() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SharedMediaSection(emptySharedMediaTiles(), {})
                    }
                }
            }
        }
        composeRule.onNodeWithTag("shared.category.Documents").assertIsDisplayed().assertHasClickAction()
        capture("entry_light")
    }

    /** Initial progress describes loading without briefly claiming an empty library. */
    @Test
    fun loadingDark() {
        render(GroupAttachmentState())
        capture("loading_dark")
    }

    /** Sparse pages retain a reachable continuation even with no matches in the selected category. */
    @Test
    fun partialRtlLargeText() {
        var clicks = 0
        render(GroupAttachmentState(loading = false, hasMore = true, initialized = true), rtl = true, large = true) { clicks++ }
        composeRule
            .onNodeWithTag("shared.history.more")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        composeRule
            .onNodeWithTag("shared.history.more")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()
        assertEquals(1, clicks)
        capture("partial_rtl_200")
    }

    /** A narrow AMOLED error keeps its retry fully visible and actionable. */
    @Test
    fun errorAmoledNarrowLargeText() {
        var retries = 0
        render(GroupAttachmentState(loading = false, failed = true), amoled = true, large = true) { retries++ }
        composeRule
            .onNodeWithTag("shared.history.retry")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        assertEquals(1, retries)
        capture("error_amoled_narrow_200")
    }

    /** Terminal empty uses complete local-history wording rather than loaded-chat-window wording. */
    @Test
    fun terminalEmpty() {
        render(GroupAttachmentState(loading = false, initialized = true))
        composeRule.onNodeWithText("End of local attachment history").assertIsDisplayed()
        composeRule.onNodeWithTag("shared.history.more").assertDoesNotExist()
        capture("empty_dark")
    }

    /** A populated final page retains source actions alongside an explicit end state. */
    @Test
    fun populatedEnd() {
        render(GroupAttachmentState(entries = listOf(historyEntry(1)), loading = false, initialized = true))
        composeRule.onNodeWithText("photo-1.png").assertIsDisplayed()
        capture("populated_end_dark")
    }

    /** Renders production scaffold and status components without native IO. */
    private fun render(
        state: GroupAttachmentState,
        rtl: Boolean = false,
        amoled: Boolean = false,
        large: Boolean = false,
        more: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                WhiteNoiseTheme(darkTheme = true, amoled = amoled, fontScale = if (large) 2f else 1f) {
                    inputMode = LocalInputModeManager.current
                    Column(Modifier.width(if (large) 320.dp else 360.dp)) {
                        SharedContentScaffold(
                            SharedContentCategory.Documents,
                            SharedVisualFilter.All,
                            {},
                            {},
                            loading = state.loading && !state.initialized,
                            paging = state,
                            onLoadMore = more,
                        ) {
                            if (state.entries.isEmpty()) {
                                AttachmentLibraryEmptyState(state)
                            } else {
                                MonthSectionedColumn(
                                    sections = groupIntoMonthSections(state.entries) { it.timelineAt },
                                    listState =
                                        androidx.compose.foundation.lazy
                                            .rememberLazyListState(),
                                    emptyLabel = "No documents",
                                    keyOf = { it.slotKey() },
                                    messageIdOf = { it.messageIdHex },
                                    onJumpToMessage = {},
                                ) { androidx.compose.material3.Text("photo-1.png") }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Uses committed deterministic images for review and the two-distribution visual gate. */
    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/attachment_history_$name.png")
    }
}
