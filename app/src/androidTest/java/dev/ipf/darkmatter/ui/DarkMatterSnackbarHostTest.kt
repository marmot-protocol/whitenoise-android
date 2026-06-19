package dev.ipf.darkmatter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import dev.ipf.darkmatter.ui.theme.DarkMatterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DarkMatterSnackbarHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun snackbarKeepsAVisibleGapAboveBottomEdge() {
        composeRule.setContent {
            DarkMatterTheme {
                val hostState = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    hostState.showSnackbar("Lifted snackbar")
                }

                Box(Modifier.fillMaxSize()) {
                    DarkMatterSnackbarHost(
                        hostState = hostState,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        snackbar = { data ->
                            Snackbar(
                                snackbarData = data,
                                modifier = Modifier.testTag("snackbar"),
                            )
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Lifted snackbar").assertExists()

        val rootBottom = composeRule.onRoot().getUnclippedBoundsInRoot().bottom
        val snackbarBottom = composeRule.onNodeWithTag("snackbar").getUnclippedBoundsInRoot().bottom

        assertTrue(
            "Expected snackbar to be lifted above the bottom edge",
            rootBottom - snackbarBottom >= 16.dp,
        )
    }

    /**
     * Issue #352: the default snackbar must be swipe-dismissible. A horizontal
     * swipe should remove the snackbar from the host and resolve the suspending
     * showSnackbar call with [SnackbarResult.Dismissed] — never
     * [SnackbarResult.ActionPerformed], so an actionable snackbar (e.g. the
     * chat-list Undo) treats a swipe-away as "ignored", not as tapping Undo.
     */
    @Test
    fun snackbarIsSwipeDismissible() {
        val result = mutableStateOf<SnackbarResult?>(null)

        composeRule.setContent {
            DarkMatterTheme {
                val hostState = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    // actionLabel set so we can prove a swipe resolves as
                    // Dismissed rather than ActionPerformed.
                    result.value =
                        hostState.showSnackbar(
                            message = "Swipe me away",
                            actionLabel = "Undo",
                        )
                }

                Box(Modifier.fillMaxSize()) {
                    // Default snackbar slot — exercises SwipeDismissibleSnackbar.
                    DarkMatterSnackbarHost(
                        hostState = hostState,
                        modifier = Modifier.testTag("host").align(Alignment.BottomCenter),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Swipe me away").assertExists()

        composeRule.onNodeWithTag("host").performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        // The snackbar is gone from the host…
        composeRule.onNodeWithText("Swipe me away").assertDoesNotExist()
        // …and the show call resolved as a dismissal, not an action tap.
        assertEquals(SnackbarResult.Dismissed, result.value)
    }
}
