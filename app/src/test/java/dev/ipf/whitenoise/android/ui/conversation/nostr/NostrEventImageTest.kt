package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class NostrEventImageTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun imageMetadataRejectsUnsafeLocatorsAndUnrelatedMimeTypes() {
        val event =
            event(
                listOf(
                    listOf("imeta", "url https://images.example/photo", "m image/jpeg"),
                    listOf("imeta", "url https://127.0.0.1/photo", "m image/jpeg"),
                    listOf("imeta", "url https://images.example/audio", "m audio/ogg"),
                    listOf("image", "https://user:pass@images.example/photo"),
                ),
            )
        assertEquals(listOf("https://images.example/photo"), event.imageMetadataUrls())
    }

    @Test
    fun openingReaderDoesNotDownloadImagesUntilExplicitlyRequested() {
        var downloads = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventImagePane(url = "https://images.example/manual-only", loadImage = { _, _ ->
                    downloads++
                    ImageBitmap(10, 10)
                })
            }
        }
        composeRule.runOnIdle { assertEquals(0, downloads) }
        val label = ApplicationProvider.getApplicationContext<Context>().getString(R.string.nostr_event_view_image)
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, downloads) }
    }

    @Test
    fun failedImageCanRetryAndOpenFullscreenWithoutAnotherDownload() {
        var downloads = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventImagePane(url = "https://images.example/retry", loadImage = { _, _ ->
                    downloads++
                    if (downloads == 1) null else ImageBitmap(20, 10)
                })
            }
        }
        composeRule.onNodeWithText(string(R.string.nostr_event_view_image)).performClick()
        composeRule.onNodeWithText(string(R.string.nostr_event_image_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.retry)).performClick()
        composeRule.onNodeWithText(string(R.string.nostr_event_image_failed)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.nostr_event_view_image)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.close)).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(2, downloads) }
    }

    @Test
    fun unsafeImageNeverReachesTheLoader() {
        var downloads = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventImagePane(url = "https://127.0.0.1/private", loadImage = { _, _ ->
                    downloads++
                    ImageBitmap(10, 10)
                })
            }
        }
        composeRule.onNodeWithText(string(R.string.nostr_event_view_image)).performClick()
        composeRule.onNodeWithText(string(R.string.nostr_event_image_failed)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, downloads) }
    }

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun event(tags: List<List<String>>) =
        NostrEvent(
            "a".repeat(64),
            "b".repeat(64),
            1,
            20,
            tags,
            "",
            "0".repeat(128),
        )
}
