package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The share picker hosts [LoadingScreen] as a lazy list item while a folder's recipients load. Lazy lists
 * measure items with unbounded height, so the loader must never be a vertical scroll container.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class LoadingScreenInLazyListTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A loader inside a lazy list item renders instead of throwing on the infinite height constraint. */
    @Test
    fun loadingScreenRendersAsALazyListItem() {
        composeRule.setContent {
            WhiteNoiseTheme {
                LazyColumn {
                    item { LoadingScreen() }
                }
            }
        }

        composeRule.onNodeWithTag(FULL_SCREEN_LOADING_TEST_TAG).assertIsDisplayed()
    }
}
