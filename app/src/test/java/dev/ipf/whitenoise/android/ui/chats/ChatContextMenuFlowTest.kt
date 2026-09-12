package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.captureClickCallbackForReplay
import dev.ipf.whitenoise.android.ui.common.dispatchNativePopupBack
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual native rows, menu anchors and selection owner share one pointer lifecycle and canonical action targets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatContextMenuFlowTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The popup cannot steal the held pointer before it becomes the existing native range gesture. */
    @Test fun heldMenuThenRangeKeepsNativeSelectionAndNeverOpensChat() =
        withScreen { opens, _, _ ->
            val row = composeRule.onNodeWithTag("chat.row.a")
            row.performTouchInput {
                down(center)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
                moveTo(center)
            }
            composeRule.onNodeWithTag("chat.menu.a").assertIsDisplayed()
            row.assertIsNotSelected()
            // The held native range requests frames for edge scrolling until pointer release.
            composeRule.mainClock.autoAdvance = false
            row.performTouchInput { moveTo(Offset(center.x, center.y + height)) }
            composeRule.mainClock.advanceTimeUntil {
                composeRule.onAllNodesWithTag("chat.menu.a").fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithTag("chat.menu.a").assertDoesNotExist()
            row.performTouchInput { up() }
            composeRule.mainClock.autoAdvance = true
            composeRule.onNodeWithTag("chat.row.a").assertIsSelected()
            composeRule.onNodeWithTag("chat.row.b").assertIsSelected()
            assertEquals(0, opens())
        }

    /** Accessibility long-click opens commands without pretending that the row is already selected. */
    @Test fun accessibleLongClickHighlightsWithoutSelectionUntilSelectCommand() =
        withScreen { opens, _, _ ->
            openMenu()
            composeRule.onNodeWithTag("chat.row.a").assertIsNotSelected()
            composeRule.onNodeWithTag("chat.action.Select").performClick()
            composeRule.onNodeWithTag("chat.menu.a").assertDoesNotExist()
            composeRule.onNodeWithTag("chat.row.a").assertIsSelected()
            assertEquals(0, opens())
        }

    /** Lazy removal of the physical anchor closes its popup, even while the underlying native row still exists. */
    @Test fun scrollingAnchorOutOfCompositionDismissesMenu() =
        withScreen { opens, _, _ ->
            openMenu()
            composeRule
                .onNode(hasScrollToIndexAction() and hasAnyDescendant(hasTestTag("chat.row.a")))
                .performScrollToIndex(19)
            composeRule.onNodeWithTag("chat.menu.a").assertDoesNotExist()
            assertEquals(0, opens())
        }

    /** Leaving the screen invalidates its anchor and cannot leave a detached popup over the next route. */
    @Test fun screenDisposalDismissesItsMenu() =
        withScreen { opens, hide, _ ->
            openMenu()
            composeRule.runOnIdle { hide(false) }
            composeRule.onNodeWithTag("chat.menu.a").assertDoesNotExist()
            assertEquals(0, opens())
        }

    /** Delete still enters the shared confirmation; cancellation retains the exact native history. */
    @Test fun deleteCancelRetainsCanonicalRow() =
        withScreen { opens, _, _ ->
            openMenu()
            composeRule.onNodeWithTag("chat.action.Delete").performClick()
            composeRule.onNodeWithTag("chat.menu.a").assertDoesNotExist()
            composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
            composeRule.onNodeWithTag("chat.row.a").assertExists()
            assertEquals(0, opens())
        }

    /** Actual row callback rejects a still-visible command once native sign-out owns teardown. */
    @Test fun signOutBlocksDisplayedSelect() =
        withScreen { opens, _, app ->
            openMenu()
            composeRule.runOnIdle { app.signOutInProgress = true }
            composeRule.onNodeWithTag("chat.action.Select").performClick()
            composeRule.onNodeWithTag("chat.row.a").assertIsNotSelected()
            assertEquals(0, opens())
        }

    /** Wipe admission is checked independently from ordinary sign-out by the actual screen callback. */
    @Test fun wipeBlocksDisplayedSelect() =
        withScreen { opens, _, app ->
            openMenu()
            composeRule.runOnIdle { app.wipeInProgress = true }
            composeRule.onNodeWithTag("chat.action.Select").performClick()
            composeRule.onNodeWithTag("chat.row.a").assertIsNotSelected()
            assertEquals(0, opens())
        }

    /** A captured native item callback cannot act on or dismiss a later opening on the same row. */
    @Test fun oldItemCallbackCannotSelectOrDismissSameRowReopening() =
        withScreen { opens, _, _ ->
            openMenu()
            val oldSelect =
                composeRule.onNodeWithTag("chat.action.Select").captureClickCallbackForReplay()
            composeRule.onNode(isPopup()).dispatchNativePopupBack()
            composeRule.onNodeWithTag("chat.menu.a").assertDoesNotExist()
            openMenu()
            composeRule.runOnIdle { oldSelect() }
            composeRule.onNodeWithTag("chat.menu.a").assertIsDisplayed()
            composeRule.onNodeWithTag("chat.row.a").assertIsNotSelected()
            composeRule.onNodeWithTag("chat.action.Select").performClick()
            composeRule.onNodeWithTag("chat.row.a").assertIsSelected()
            assertEquals(0, opens())
        }

    /** Same-account route recreation must not revive the previous presentation's captured callback. */
    @Test fun disposedItemCannotAffectRecreatedSameAccountMenu() =
        withScreen { opens, show, _ ->
            openMenu()
            val oldSelect =
                composeRule.onNodeWithTag("chat.action.Select").captureClickCallbackForReplay()
            composeRule.runOnIdle { show(false) }
            composeRule.onNodeWithTag("chat.menu.a").assertDoesNotExist()
            composeRule.runOnIdle { show(true) }
            openMenu()
            composeRule.runOnIdle { oldSelect() }
            composeRule.onNodeWithTag("chat.menu.a").assertIsDisplayed()
            composeRule.onNodeWithTag("chat.row.a").assertIsNotSelected()
            composeRule.onNodeWithTag("chat.action.Select").performClick()
            composeRule.onNodeWithTag("chat.row.a").assertIsSelected()
            assertEquals(0, opens())
        }

    private fun openMenu() {
        composeRule.onNodeWithTag("chat.row.a").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        composeRule.onNodeWithTag("chat.menu.a").assertIsDisplayed()
    }

    private fun withScreen(assertions: (() -> Int, (Boolean) -> Unit, WhiteNoiseAppState) -> Unit) {
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val app = ChatRowPortFixtures.state(context)
        val rows =
            ('a'..'z').mapIndexed { index, id ->
                val row = leftScopeRow(id.toString())
                row.copy(projection = checkNotNull(row.projection).copy(activitySortAt = (1000 - index).toULong()))
            }
        val controller = leftScopeController(app, rows)
        var visible by mutableStateOf(true)
        var opens = 0
        try {
            composeRule.setContent {
                WhiteNoiseTheme {
                    if (visible) ChatsScreen(app, controller, {}, { _, _, _, _ -> opens++ })
                }
            }
            assertions({ opens }, { visible = it }, app)
        } finally {
            controller.onCleared()
            app.mutationsScope.cancel()
        }
    }
}
