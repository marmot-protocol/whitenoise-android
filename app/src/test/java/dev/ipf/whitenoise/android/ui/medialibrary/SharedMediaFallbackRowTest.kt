package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class SharedMediaFallbackRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun linkFallbackRendersPluralCountAndOpensLibrary() {
        var clicks = 0
        render(SharedMediaFallback(SharedMediaFallbackType.Urls, count = 3)) { clicks++ }

        composeRule.onNodeWithText("3 links shared").assertIsDisplayed().performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun fileFallbackRendersPluralCountAndOpensLibrary() {
        var clicks = 0
        render(SharedMediaFallback(SharedMediaFallbackType.Files, count = 2)) { clicks++ }

        composeRule.onNodeWithText("2 files shared").assertIsDisplayed().performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun voiceFallbackRendersSingularCountAndOpensLibrary() {
        var clicks = 0
        render(SharedMediaFallback(SharedMediaFallbackType.Voice, count = 1)) { clicks++ }

        composeRule.onNodeWithText("1 voice message shared").assertIsDisplayed().performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun genericFallbackKeepsExistingLabelAndOpensLibrary() {
        var clicks = 0
        render(SharedMediaFallback(SharedMediaFallbackType.Generic)) { clicks++ }

        composeRule.onNodeWithText("View shared media").assertIsDisplayed().performClick()

        assertEquals(1, clicks)
    }

    private fun render(
        fallback: SharedMediaFallback,
        onSeeAll: () -> Unit,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                SharedMediaFallbackRow(fallback = fallback, onSeeAll = onSeeAll)
            }
        }
    }
}
