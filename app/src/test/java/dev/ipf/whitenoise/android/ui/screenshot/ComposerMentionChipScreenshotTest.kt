package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerPill
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel baselines for `@npub` mention chips in the composer pill. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ComposerMentionChipScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composerMentionActiveCompositionUsesCanonicalText() {
        val focusRequester =
            renderMentionPill(
                TextFieldValue(
                    text = MENTION_DRAFT,
                    selection = TextRange(3),
                    composition = TextRange(2, 3),
                ),
                darkTheme = false,
            )
        composeRule.runOnIdle { focusRequester.requestFocus() }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(MENTION_TAG)
            .captureRoboImage("src/test/snapshots/composer_mention_active_composition.png")
    }

    /** A committed mention is a filled chip in the exact saved accent, which would not read as Dark text. */
    @Test
    fun composerMentionChipUsesActionColorDark() {
        renderMentionPill(
            TextFieldValue(MENTION_DRAFT, selection = TextRange(MENTION_DRAFT.length)),
            darkTheme = true,
            accentArgb = NAVY_ACCENT,
            actionColors = AccountActionColors(container = Color(NAVY_ACCENT), content = Color.White),
        )
        composeRule
            .onNodeWithTag(MENTION_TAG)
            .captureRoboImage("src/test/snapshots/composer_mention_chip_action_color_dark.png")
    }

    /** Renders the composer pill with one resolved mention candidate, Alice; returns its focus requester. */
    private fun renderMentionPill(
        value: TextFieldValue,
        darkTheme: Boolean,
        accentArgb: Long? = null,
        actionColors: AccountActionColors? = null,
    ): FocusRequester {
        val focusRequester = FocusRequester()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, accentColorArgb = accentArgb) {
                Surface(Modifier.width(360.dp).testTag(MENTION_TAG)) {
                    ComposerPill(
                        actionColors = actionColors ?: accountActionColors(appState = null),
                        textFieldValue = value,
                        composerFocus = focusRequester,
                        emojiPickerOpen = false,
                        onValueChange = {},
                        onEmojiPickerToggle = {},
                        onAttachmentsToggle = {},
                        attachmentSheetOpen = false,
                        onPickFromGallery = null,
                        onPickDocument = null,
                        highlightMentionChips = true,
                        mentionCandidates =
                            listOf(
                                MentionComposer.Candidate(
                                    accountIdHex = "aa".repeat(32),
                                    npub = MENTION_NPUB,
                                    displayName = "Alice",
                                ),
                            ),
                    )
                }
            }
        }
        return focusRequester
    }

    private companion object {
        const val MENTION_TAG = "composer-mention"
        val MENTION_NPUB = "npub1" + "q".repeat(58)
        val MENTION_DRAFT = "@$MENTION_NPUB "

        /** A saved accent that fails 4.5:1 as text on the Dark surfaces (#1D4ED8). */
        const val NAVY_ACCENT = 0xFF1D4ED8
    }
}
