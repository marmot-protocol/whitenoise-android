package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StickyFormActionBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersPrimaryActionInTheStickyImeAwareActionBar() {
        composeRule.setContent {
            WhiteNoiseTheme {
                StickyFormActionBar {
                    Button(onClick = {}) {
                        Text("Save group")
                    }
                }
            }
        }

        composeRule.onNodeWithText("Save group").assertIsDisplayed()
    }

    @Test
    fun reservesBottomSpaceForSystemTransientOverlays() {
        composeRule.setContent {
            WhiteNoiseTheme {
                StickyFormActionBar(modifier = Modifier.testTag("action-bar")) {
                    Box(Modifier.fillMaxWidth().height(20.dp)) {
                        Text("Save group")
                    }
                }
            }
        }

        // The bar must be tall enough to include the action row plus the
        // Android clipboard-access toast clearance below it.
        composeRule.onNodeWithTag("action-bar").assertHeightIsAtLeast(100.dp)
    }

    @Test
    fun raisesAndRestoresSnackbarInsetWhileMounted() {
        val baselineInset = 8.dp
        val snackbarInset = mutableStateOf(baselineInset)
        val showBar = mutableStateOf(true)

        composeRule.setContent {
            CompositionLocalProvider(LocalSnackbarBottomInset provides snackbarInset) {
                WhiteNoiseTheme {
                    if (showBar.value) {
                        StickyFormActionBar(modifier = Modifier.testTag("action-bar")) {
                            Box(Modifier.fillMaxWidth().height(20.dp)) {
                                Text("Save group")
                            }
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        assertTrue(snackbarInset.value > baselineInset)

        showBar.value = false
        composeRule.waitForIdle()

        assertEquals(baselineInset, snackbarInset.value)
    }
}
