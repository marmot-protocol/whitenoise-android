package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h1600dp-mdpi")
class NostrEventPreviewGeometryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun nostrEventPreviewStateTransitionsKeepMessageBoundsStable() {
        val state = mutableStateOf<NostrEventCardState>(NostrEventCardState.Loading)
        val configuration = mutableStateOf(GeometryConfiguration())
        composeRule.setContent {
            val current = configuration.value
            CompositionLocalProvider(
                LocalDensity provides Density(1f, current.fontScale),
                LocalLayoutDirection provides current.direction,
            ) {
                WhiteNoiseTheme(darkTheme = current.dark, amoled = current.amoled) {
                    Column(Modifier.width(current.width.dp).testTag("message")) {
                        Box(Modifier.fillMaxWidth().height(24.dp).testTag("previous-row"))
                        NostrEventCard(
                            state = state.value,
                            authorDisplayName = { "A contact" },
                            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                            onRetry = {},
                            onCopy = {},
                            onOpen = {},
                            referenceLabel = "note1…reference",
                        )
                        Box(Modifier.fillMaxWidth().height(24.dp).testTag("next-row"))
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag("composer"))
                    }
                }
            }
        }
        configurations().forEach { current ->
            composeRule.runOnIdle {
                configuration.value = current
                state.value = NostrEventCardState.Loading
            }
            composeRule.waitForIdle()
            val initial = bounds()
            states().forEach { next ->
                composeRule.runOnIdle { state.value = next }
                composeRule.waitForIdle()
                assertEquals("$current / $next", initial, bounds())
                if (next is NostrEventCardState.Loaded && next.card.imageUrls.isNotEmpty()) {
                    val label = ApplicationProvider.getApplicationContext<Context>().getString(R.string.nostr_event_view_image)
                    composeRule.onNodeWithText(label).assertIsDisplayed().assertHeightIsAtLeast(48.dp * current.fontScale)
                }
            }
        }
    }

    private fun bounds(): List<Rect> =
        listOf("message", NOSTR_EVENT_CARD_BOUNDS_TAG, "previous-row", "next-row", "composer").map {
            composeRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
        }

    private fun states(): List<NostrEventCardState> =
        listOf(NostrEventCardState.NotFound, NostrEventCardState.Invalid, NostrEventCardState.Failed) +
            NostrEventCardKind.entries.flatMap { kind ->
                val card =
                    NostrEventCardModel(
                        kind = kind,
                        eventIdHex = "a".repeat(64),
                        authorPubkeyHex = "b".repeat(64),
                        createdAt = 1L,
                        eventKind = 1,
                        title = "A long title that wraps across the available reading width",
                        summary = "A readable summary with enough words to fill all three preview lines.",
                        metadata = listOf("image/jpeg", "1280×720"),
                        readerBody = "Full readable body",
                        imageUrls = listOf("https://images.example/geometry-only"),
                    )
                listOf(
                    NostrEventCardState.Loaded(card),
                    NostrEventCardState.Loaded(card.copy(authorMetadata = NostrEventAuthorMetadata("New author", null))),
                    NostrEventCardState.Loaded(card.copy(summary = null, imageUrls = emptyList())),
                )
            }

    private fun configurations(): List<GeometryConfiguration> =
        listOf(
            GeometryConfiguration(),
            GeometryConfiguration(dark = true),
            GeometryConfiguration(dark = true, amoled = true),
            GeometryConfiguration(width = 240, fontScale = 2f, direction = LayoutDirection.Rtl),
        )
}

private data class GeometryConfiguration(
    val width: Int = 360,
    val fontScale: Float = 1f,
    val direction: LayoutDirection = LayoutDirection.Ltr,
    val dark: Boolean = false,
    val amoled: Boolean = false,
)
