package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.TimelineReplyDisplay
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baseline for the composer bar leaf in its idle (empty) and drafted
 * states. App state and the voice controller stay null, so only the pure
 * input surface is pinned.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ComposerBarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composerBarIdleLight() {
        render(darkTheme = false, amoled = false, draft = "")
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_bar_idle_light.png")
    }

    @Test
    fun composerBarIdleDark() {
        render(darkTheme = true, amoled = false, draft = "")
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_bar_idle_dark.png")
    }

    @Test
    fun composerBarIdleAmoled() {
        render(darkTheme = true, amoled = true, draft = "")
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_bar_idle_amoled.png")
    }

    @Test
    fun composerBarDraftLight() {
        render(darkTheme = false, amoled = false, draft = "Draft message text")
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_bar_draft_light.png")
    }

    @Test
    fun composerBarLongDraftLight() {
        renderLongComposer(darkTheme = false)
        composeRule.onNodeWithTag(LONG_TAG).captureRoboImage("src/test/snapshots/composer_bar_long_draft_light.png")
    }

    @Test
    fun composerBarLongDraftDark() {
        renderLongComposer(darkTheme = true)
        composeRule.onNodeWithTag(LONG_TAG).captureRoboImage("src/test/snapshots/composer_bar_long_draft_dark.png")
    }

    @Test
    fun composerBarFullScreenLargeRtl() {
        renderLongComposer(darkTheme = true, largeRtl = true)
        composeRule.onNodeWithContentDescription("Drag to resize message composer").performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(LONG_TAG)
            .captureRoboImage("src/test/snapshots/composer_bar_full_screen_large_rtl.png")
    }

    @Test
    fun composerReplyShowsConvergenceWarning() {
        val warning = "May not be visible to everyone"

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                ComposerBar(
                    replyingTo = replyRecord(),
                    replyingToDisplay =
                        TimelineReplyDisplay(
                            sender = "alice",
                            body = "Parent message",
                            warning = warning,
                        ),
                    messageTextCopy = MessageTextCopy.Default,
                    onCancelReply = {},
                    onSend = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText(warning).assertIsDisplayed()
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
        draft: String,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        initialDraft = TextFieldValue(draft),
                    )
                }
            }
        }
    }

    private fun renderLongComposer(
        darkTheme: Boolean,
        largeRtl: Boolean = false,
    ) {
        val draft =
            "A thoughtful long message starts here.\n" +
                "It keeps growing naturally line by line.\n" +
                "The controls remain easy to reach.\n" +
                "Nothing in the draft is replaced.\n" +
                "The final paragraph stays visible while editing."
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, if (largeRtl) 1.45f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.width(360.dp).height(720.dp)) {
                        Box {
                            ComposerBar(
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { _, _ -> },
                                onPickFromGallery = {},
                                onPickDocument = {},
                                initialDraft = TextFieldValue(draft),
                                modifier = Modifier.testTag(LONG_TAG),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun replyRecord() =
        AppMessageRecordFfi(
            messageIdHex = "parent",
            direction = "received",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "Parent message",
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    private companion object {
        const val TAG = "composer-bar"
        const val LONG_TAG = "long-composer-bar"
    }
}
