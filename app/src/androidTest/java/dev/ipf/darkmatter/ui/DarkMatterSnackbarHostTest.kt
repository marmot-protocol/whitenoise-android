package dev.ipf.darkmatter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.ipf.darkmatter.ui.theme.DarkMatterTheme
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
}
