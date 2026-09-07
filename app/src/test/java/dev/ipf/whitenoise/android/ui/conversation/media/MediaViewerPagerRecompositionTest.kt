package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.ui.common.clampViewerPageIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MediaViewerPagerRecompositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun prependingTheLoadedGalleryNeverRebindsTheMountedViewerToTheOldIndex() {
        val newer = page("newer")
        val tapped = page("tapped")
        val older = page("older")
        var pages by mutableStateOf(listOf(tapped))
        val selectedMessageIds = mutableListOf<String>()
        val mountedMessageIds = mutableListOf<String>()

        composeRule.setContent {
            val selection = rememberMediaViewerPagerSelection(pages, startIndex = 0)
            SideEffect { selectedMessageIds += selection.currentPage.messageIdHex }
            SideEffect {
                mountedMessageIds +=
                    pages[clampViewerPageIndex(selection.pagerState.currentPage, pages.size)]
                        .messageIdHex
            }
            StableMediaViewerPager(
                pages = pages,
                selection = selection,
                modifier = Modifier.fillMaxSize(),
                pagePositionDescription = null,
                userScrollEnabled = false,
            ) { page, isCurrent ->
                if (isCurrent) {
                    Text(page.messageIdHex, Modifier.testTag(CURRENT_PAGE_TAG))
                }
            }
        }

        composeRule.runOnIdle {
            selectedMessageIds.clear()
            mountedMessageIds.clear()
            pages = listOf(newer, tapped, older)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CURRENT_PAGE_TAG).assertTextEquals("tapped")
        composeRule.runOnIdle {
            assertTrue(selectedMessageIds.isNotEmpty())
            assertEquals(setOf("tapped"), selectedMessageIds.toSet())
            assertTrue(mountedMessageIds.isNotEmpty())
            assertEquals(setOf("tapped"), mountedMessageIds.toSet())
        }
    }

    @Test
    fun pagePositionIsExposedToAccessibility() {
        val pages = listOf(page("newer"), page("current"), page("older"))

        composeRule.setContent {
            StableMediaViewerPager(
                pages = pages,
                selection = rememberMediaViewerPagerSelection(pages, startIndex = 1),
                modifier = Modifier.fillMaxSize().testTag(PAGER_TAG),
                pagePositionDescription = "Media 2 of 3",
                userScrollEnabled = false,
            ) { page, isCurrent ->
                if (isCurrent) {
                    Text(page.messageIdHex)
                }
            }
        }

        composeRule
            .onNodeWithTag(PAGER_TAG)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Media 2 of 3",
                ),
            )
    }

    /** Keeps repeated mixed-media selection stable while live references replace pager inputs. */
    @Test
    fun mixedImageVideoSelectionSurvivesMetadataAndContentUpgrades() {
        val image = page("mixed", attachmentIndex = 0)
        val video = page("mixed", attachmentIndex = 1, mediaType = "video/mp4")
        val secondImage = page("mixed", attachmentIndex = 2)
        var pages by mutableStateOf(listOf(image, video, secondImage))
        lateinit var selection: MediaViewerPagerSelection

        composeRule.setContent {
            selection = rememberMediaViewerPagerSelection(pages, startIndex = 0)
            StableMediaViewerPager(
                pages = pages,
                selection = selection,
                modifier = Modifier.fillMaxSize(),
                pagePositionDescription = null,
                userScrollEnabled = false,
            ) { page, isCurrent ->
                if (isCurrent) {
                    Text(
                        "${page.messageIdHex}:${page.attachmentIndex}:${page.reference.sourceEpoch}",
                        Modifier.testTag(CURRENT_PAGE_TAG),
                    )
                }
            }
        }

        scrollToPage(selection, 1)
        composeRule.onNodeWithTag(CURRENT_PAGE_TAG).assertTextEquals("mixed:1:1")

        composeRule.runOnIdle {
            pages =
                listOf(
                    image,
                    video.copy(reference = reference(1, "video/mp4", sourceEpoch = 9uL)),
                    secondImage,
                )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CURRENT_PAGE_TAG).assertTextEquals("mixed:1:9")

        composeRule.runOnIdle {
            pages =
                listOf(
                    image,
                    video.copy(
                        reference =
                            reference(
                                attachmentIndex = 1,
                                mediaType = "video/mp4",
                                sourceEpoch = 10uL,
                                plaintextSha256 = "dd".repeat(32),
                            ),
                    ),
                    secondImage,
                )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CURRENT_PAGE_TAG).assertTextEquals("mixed:1:10")

        scrollToPage(selection, 2)
        composeRule.onNodeWithTag(CURRENT_PAGE_TAG).assertTextEquals("mixed:2:1")
        scrollToPage(selection, 0)
        composeRule.onNodeWithTag(CURRENT_PAGE_TAG).assertTextEquals("mixed:0:1")
        scrollToPage(selection, 1)
        composeRule.onNodeWithTag(CURRENT_PAGE_TAG).assertTextEquals("mixed:1:10")
    }

    /** Scrolls the currently recreated pager and waits for its settled logical selection. */
    private fun scrollToPage(
        selection: MediaViewerPagerSelection,
        page: Int,
    ) {
        composeRule.runOnUiThread { selection.pagerState.requestScrollToPage(page) }
        composeRule.waitForIdle()
    }

    /** Creates a logical viewer page whose reference fields can change independently. */
    private fun page(
        messageIdHex: String,
        attachmentIndex: Int = 0,
        mediaType: String = "image/jpeg",
    ) = MediaViewerPage(
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference(attachmentIndex, mediaType),
        mine = false,
        sender = "sender-$messageIdHex",
        recordedAt = 1uL,
    )

    /** Creates one encrypted-media reference for pager replacement scenarios. */
    private fun reference(
        attachmentIndex: Int,
        mediaType: String,
        sourceEpoch: ULong = 1uL,
        plaintextSha256: String = "bb".repeat(32),
    ) = MediaAttachmentReferenceFfi(
        locators = emptyList(),
        ciphertextSha256 = "${attachmentIndex + 1}".repeat(64),
        plaintextSha256 = plaintextSha256,
        nonceHex = "cc".repeat(12),
        fileName = if (mediaType.startsWith("video/")) "clip.mp4" else "still-$attachmentIndex.jpg",
        mediaType = mediaType,
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = sourceEpoch,
        dim = null,
        thumbhash = null,
    )

    private companion object {
        const val CURRENT_PAGE_TAG = "current-media-viewer-page"
        const val PAGER_TAG = "media-viewer-pager"
    }
}
