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

    private fun page(messageIdHex: String) =
        MediaViewerPage(
            messageIdHex = messageIdHex,
            attachmentIndex = 0,
            reference =
                MediaAttachmentReferenceFfi(
                    locators = emptyList(),
                    ciphertextSha256 = "aa".repeat(32),
                    plaintextSha256 = "bb".repeat(32),
                    nonceHex = "cc".repeat(12),
                    fileName = "$messageIdHex.jpg",
                    mediaType = "image/jpeg",
                    version = EncryptedMediaVersionFfi.V1,
                    sourceEpoch = 1uL,
                    dim = null,
                    thumbhash = null,
                ),
            mine = false,
            sender = "sender-$messageIdHex",
            recordedAt = 1uL,
        )

    private companion object {
        const val CURRENT_PAGE_TAG = "current-media-viewer-page"
        const val PAGER_TAG = "media-viewer-pager"
    }
}
