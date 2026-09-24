package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaInventory
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures actual category and destination chrome without platform media IO or invented library content. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SharedContentScreensScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Category rows light open their exact destination. */
    @Test
    fun categoryRowsLightOpenTheirExactDestination() {
        val opened = mutableListOf<SharedContentCategory>()
        val tile = sampleTile()
        val row = sampleRow()
        val tiles =
            emptyTiles().copy(
                visuals = listOf(tile),
                images = listOf(tile),
                urls = listOf(MediaInventory.UrlEntry("link", "alice", 1_700_000_000uL, "https://example.org")),
                files = listOf(row),
                voice = listOf(row),
            )
        renderCategories(tiles, onOpen = { opened += it })
        SharedContentCategory.entries.forEach { category ->
            composeRule.onNodeWithTag("shared.category.${category.name}").assertIsDisplayed().performClick()
        }
        assertEquals(SharedContentCategory.entries, opened)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/shared_content_categories_light.png")
    }

    /** A chat with no loaded content has neither category rows nor an empty section heading. */
    @Test
    fun emptyChatHidesSharedContentSection() {
        renderCategories(emptyTiles())
        composeRule.onNodeWithText("Shared in Chat").assertDoesNotExist()
        SharedContentCategory.entries.forEach { category ->
            composeRule.onNodeWithTag("shared.category.${category.name}").assertDoesNotExist()
        }
    }

    /** A picture-only chat has one category, including at large font scale and in RTL. */
    @Test
    fun pictureOnlyChatShowsOnlyMediaRtlLargeFont() {
        val tile = sampleTile()
        renderCategories(
            emptyTiles().copy(visuals = listOf(tile), images = listOf(tile)),
            rtl = true,
            fontScale = 2f,
        )
        composeRule.onNodeWithText("Shared in Chat").assertIsDisplayed()
        composeRule.onNodeWithTag("shared.category.Media").assertIsDisplayed()
        listOf("Links", "Documents", "Voice").forEach { category ->
            composeRule.onNodeWithTag("shared.category.$category").assertDoesNotExist()
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/shared_content_media_only_rtl_200.png")
    }

    /** Media filters dark preserve selected category and dispatch changes. */
    @Test
    fun mediaFiltersDarkPreserveSelectedCategoryAndDispatchChanges() {
        val filters = mutableListOf<SharedVisualFilter>()
        renderDestination(SharedContentCategory.Media, onFilter = { filters += it })
        composeRule.onNodeWithTag("shared.filter.Images").performClick().assertIsSelected()
        composeRule.onNodeWithTag("shared.filter.Videos").performClick().assertIsSelected()
        composeRule.onNodeWithTag("shared.filter.All").performClick().assertIsSelected()
        assertEquals(listOf(SharedVisualFilter.Images, SharedVisualFilter.Videos, SharedVisualFilter.All), filters)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/shared_content_media_empty_dark.png")
    }

    /** Voice empty amoled keeps truthful loaded scope. */
    @Test
    fun voiceEmptyAmoledKeepsTruthfulLoadedScope() {
        renderDestination(SharedContentCategory.Voice, amoled = true)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/shared_content_voice_empty_amoled.png")
    }

    /** Documents empty rtl large font stays readable. */
    @Test
    fun documentsEmptyRtlLargeFontStaysReadable() {
        renderDestination(SharedContentCategory.Documents, rtl = true, fontScale = 2f)
        composeRule.onNodeWithTag("shared.destination.Documents").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/shared_content_documents_empty_rtl_200.png")
    }

    /** Source actions in the real month list return each exact message. */
    @Test
    fun sourceActionsInTheRealMonthListReturnEachExactMessage() {
        val jumped = mutableListOf<String>()
        val entries =
            listOf(
                MediaInventory.UrlEntry("first-message", "alice", 1_700_000_000uL, "https://example.org/first"),
                MediaInventory.UrlEntry("second-message", "bob", 1_700_000_001uL, "https://example.org/second"),
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                SharedContentScaffold(
                    category = SharedContentCategory.Links,
                    filter = SharedVisualFilter.All,
                    onFilter = {},
                    onBack = {},
                    loading = false,
                ) {
                    MonthSectionedColumn(
                        sections = groupIntoMonthSections(entries) { it.recordedAt },
                        listState = rememberLazyListState(),
                        emptyLabel = "No links yet",
                        keyOf = { it.messageIdHex },
                        messageIdOf = { it.messageIdHex },
                        onJumpToMessage = { jumped += it },
                    ) { Text(it.url) }
                }
            }
        }
        composeRule.onNodeWithTag("shared.message.first-message").performClick()
        composeRule.onNodeWithTag("shared.message.second-message").performClick()
        assertEquals(listOf("first-message", "second-message"), jumped)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/shared_content_links_month_source_light.png")
    }

    /** Renders destination. */
    private fun renderDestination(
        category: SharedContentCategory,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        onFilter: (SharedVisualFilter) -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = true, amoled = amoled, fontScale = fontScale) {
                    var filter by remember { mutableStateOf(SharedVisualFilter.All) }
                    SharedContentScaffold(
                        category = category,
                        filter = filter,
                        onFilter = {
                            filter = it
                            onFilter(it)
                        },
                        onBack = {},
                        loading = false,
                    ) {
                        SharedContentEmptyState(
                            stringResource(
                                when (category) {
                                    SharedContentCategory.Media -> R.string.shared_content_empty
                                    SharedContentCategory.Links -> R.string.shared_media_empty_urls
                                    SharedContentCategory.Documents -> R.string.shared_media_empty_files
                                    SharedContentCategory.Voice -> R.string.shared_media_empty_voice
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun renderCategories(
        tiles: SharedMediaTiles,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        onOpen: (SharedContentCategory) -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = false, fontScale = fontScale) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            SharedMediaSection(tiles, onOpenCategory = onOpen)
                        }
                    }
                }
            }
        }
    }

    private fun emptyTiles(): SharedMediaTiles {
        return buildVisibleSharedMediaTiles(emptyList(), null, emptySet(), emptySet(), 1uL)
    }

    private fun sampleTile(): SharedMediaTile {
        return SharedMediaTile("picture", 0, sampleReference(), false, 1_700_000_000uL, "alice", false)
    }

    private fun sampleRow(): SharedMediaRow {
        return SharedMediaRow("attachment", 0, sampleReference(), false, 1_700_000_000uL, "alice")
    }

    private fun sampleReference(): MediaAttachmentReferenceFfi =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://example.org/attachment")),
            ciphertextSha256 = "a".repeat(64),
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = "attachment.jpg",
            mediaType = "image/jpeg",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 0uL,
            dim = null,
            thumbhash = null,
        )
}
