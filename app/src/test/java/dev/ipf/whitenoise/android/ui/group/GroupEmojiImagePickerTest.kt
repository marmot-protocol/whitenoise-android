package dev.ipf.whitenoise.android.ui.group

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.ipf.whitenoise.android.media.GroupEmojiImageException
import dev.ipf.whitenoise.android.media.GroupEmojiImageRenderer
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w800dp-h1200dp")
class GroupEmojiImagePickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun catalogSelectionPreviewsAndAppliesTheExactGeneratedDraft() {
        var applied: ImageUploadDraft? = null
        var rendered: ImageUploadDraft? = null
        val used = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupEmojiImagePickerSheet(
                    applyInFlight = false,
                    recentEmojis = listOf("😀"),
                    onEmojiUsed = used::add,
                    onApply = { applied = it },
                    onDismiss = {},
                    renderer = {
                        GroupEmojiImageRenderer
                            .render(it, hasGlyph = { _, _ -> true })
                            .also { draft -> rendered = draft }
                    },
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("😀").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("😀").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule
                    .onNodeWithContentDescription("Generated group image preview using 😀")
                    .fetchSemanticsNode()
            }.isSuccess
        }

        composeRule
            .onNodeWithContentDescription("Generated group image preview using 😀")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Use emoji image").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertNotNull(applied)
            assertSame(rendered, applied)
            assertEquals(listOf("😀"), used)
        }
    }

    @Test
    fun thirdSelectionShowsLimitThenRemovalAllowsReplacement() {
        var applied: ImageUploadDraft? = null
        // Keyed by selection, not last-write-wins: a producer cancelled by a
        // newer selection still runs its non-suspending renderer to
        // completion on Dispatchers.Default, so a stale intermediate render
        // ([🚀]) can finish after the final one and must not confuse the
        // assertions. The sheet itself is safe — withContext re-checks
        // cancellation before publishing, and consumers gate on the current
        // selection — this is purely test-observation ordering.
        val renderedBySelection = ConcurrentHashMap<List<String>, ImageUploadDraft>()
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupEmojiImagePickerSheet(
                    applyInFlight = false,
                    recentEmojis = listOf("😀", "🚀", "🎉"),
                    onEmojiUsed = {},
                    onApply = { applied = it },
                    onDismiss = {},
                    renderer = { emojis ->
                        GroupEmojiImageRenderer
                            .render(emojis, hasGlyph = { _, _ -> true })
                            .also { draft -> renderedBySelection[emojis] = draft }
                    },
                )
            }
        }

        select("😀")
        select("🚀")
        select("🎉")
        composeRule.onNodeWithText("Choose at most two emoji.").assertIsDisplayed()

        composeRule.onNodeWithText("😀 ×").performClick()
        select("🎉")
        awaitPreview("Generated group image preview using 🚀 🎉")
        composeRule.onNodeWithText("Use emoji image").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertNotNull(applied)
            assertSame(renderedBySelection[listOf("🚀", "🎉")], applied)
        }
    }

    @Test
    fun rejectedThirdSelectionIsNotRecordedAsRecentEmojiUsage() {
        val used = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupEmojiImagePickerSheet(
                    applyInFlight = false,
                    recentEmojis = listOf("😀", "🚀", "🎉"),
                    onEmojiUsed = used::add,
                    onApply = {},
                    onDismiss = {},
                    renderer = { GroupEmojiImageRenderer.render(it, hasGlyph = { _, _ -> true }) },
                )
            }
        }

        select("😀")
        select("🚀")
        select("🎉")
        composeRule.onNodeWithText("Choose at most two emoji.").assertIsDisplayed()

        composeRule.runOnIdle { assertEquals(listOf("😀", "🚀"), used) }
    }

    @Test
    fun unsupportedGlyphExplainsFailureAndKeepsApplyDisabled() {
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupEmojiImagePickerSheet(
                    applyInFlight = false,
                    recentEmojis = listOf("🫨"),
                    onEmojiUsed = {},
                    onApply = {},
                    onDismiss = {},
                    renderer = { throw GroupEmojiImageException.UnsupportedGlyph },
                )
            }
        }

        select("🫨")
        composeRule.onNodeWithText("This emoji cannot be rendered on this device.").assertIsDisplayed()
        composeRule.onNodeWithText("Use emoji image").assertIsNotEnabled()
    }

    @Test
    fun inFlightApplyLocksSelectionRemovalActionsAndDismissal() {
        val applyInFlight = androidx.compose.runtime.mutableStateOf(false)
        var dismissCalls = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupEmojiImagePickerSheet(
                    applyInFlight = applyInFlight.value,
                    recentEmojis = listOf("😀", "🚀"),
                    onEmojiUsed = {},
                    onApply = {},
                    onDismiss = { dismissCalls++ },
                    renderer = { GroupEmojiImageRenderer.render(it, hasGlyph = { _, _ -> true }) },
                )
            }
        }

        select("😀")
        awaitPreview("Generated group image preview using 😀")
        composeRule.runOnIdle { applyInFlight.value = true }

        composeRule.onNodeWithText("😀 ×").assertIsNotEnabled()
        composeRule.onAllNodesWithText("🚀").onFirst().assertIsNotEnabled()
        composeRule.onNodeWithText("Cancel").assertIsNotEnabled()
        composeRule.onNodeWithText("Use emoji image").assertIsNotEnabled()
        composeRule
            .onNode(
                androidx.compose.ui.test.SemanticsMatcher
                    .keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss),
            ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss)

        composeRule.onNodeWithTag(GROUP_EMOJI_IMAGE_PICKER_TAG).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, dismissCalls) }
    }

    private fun select(emoji: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(emoji).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(emoji).onFirst().performClick()
    }

    private fun awaitPreview(description: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithContentDescription(description).fetchSemanticsNode() }.isSuccess
        }
    }
}
