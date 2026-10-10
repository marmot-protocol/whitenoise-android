package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

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
    fun compactImageKeepsBoundsAcrossLoadingFailureAndRetry() {
        val pending = CompletableDeferred<ImageBitmap?>()
        var downloads = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Column(Modifier.testTag("image-envelope")) {
                    NostrEventImagePane(
                        url = "https://images.example/compact-states",
                        compact = true,
                        loadImage = { _, _ ->
                            downloads++
                            if (downloads == 1) pending.await() else ImageBitmap(20, 10)
                        },
                    )
                }
            }
        }
        val initial = composeRule.onNodeWithTag("image-envelope").fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle { assertEquals(0, downloads) }
        composeRule.onNodeWithText(string(R.string.nostr_event_view_image)).performClick()
        composeRule.runOnIdle { assertEquals(1, downloads) }
        assertEquals(initial, composeRule.onNodeWithTag("image-envelope").fetchSemanticsNode().boundsInRoot)
        pending.complete(null)
        composeRule.onNodeWithText(string(R.string.nostr_event_image_failed)).assertIsDisplayed()
        assertEquals(initial, composeRule.onNodeWithTag("image-envelope").fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithText(string(R.string.retry)).performClick()
        composeRule.waitForIdle()
        assertEquals(initial, composeRule.onNodeWithTag("image-envelope").fetchSemanticsNode().boundsInRoot)
        composeRule.runOnIdle { assertEquals(2, downloads) }
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
        val downloads = AtomicInteger()
        val recoveredImage = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).asImageBitmap()
        val failureLabel = string(R.string.nostr_event_image_failed)
        val viewLabel = string(R.string.nostr_event_view_image)
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventImagePane(url = "https://images.example/retry", loadImage = { _, _ ->
                    if (downloads.incrementAndGet() == 1) null else recoveredImage
                })
            }
        }
        composeRule.onNodeWithText(viewLabel).performClick()
        composeRule.onNodeWithText(failureLabel).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.retry)).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription(viewLabel).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle { assertEquals(2, downloads.get()) }
        composeRule.onNodeWithText(failureLabel).assertDoesNotExist()
        composeRule.onNodeWithText(viewLabel).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.close)).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(2, downloads.get()) }
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
