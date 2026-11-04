package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.media.AttachmentPreparationStatus
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerAttachmentCardKind
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerAttachmentShelfCard
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPreviewSendBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Records only the changed attachment-preparation surface, including explicit light and large RTL choices. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AttachmentPreparationScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Black surfaces, compact progress and a visibly disabled send arrow remain legible together. */
    @Test fun preparingAmoled() = capture("amoled", true, false)

    /** The status wraps in a narrow RTL layout with large type without covering the send target. */
    @Test fun preparingRtlLarge() = capture("rtl_large", true, true)

    /** An explicit light theme retains its colors and disabled-action distinction. */
    @Test fun preparingLight() = capture("light", false, false)

    /** The legacy staging bar keeps its loading label and disabled arrow legible on black. */
    @Test fun legacyPreparingAmoled() = capture("legacy_amoled", true, false, true)

    /** The legacy caption and progress fit at large RTL type without covering Send. */
    @Test fun legacyPreparingRtlLarge() = capture("legacy_rtl_large", true, true, true)

    /** Legacy preparation respects an explicit light theme. */
    @Test fun legacyPreparingLight() = capture("legacy_light", false, false, true)

    private fun capture(
        name: String,
        dark: Boolean,
        large: Boolean,
        legacy: Boolean = false,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { preparingSurface(dark, large, legacy) }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule
            .onNodeWithTag("preparation.surface")
            .captureRoboImage("src/test/snapshots/attachment_preparation_$name.png")
    }

    @Composable
    @Suppress("FunctionNaming")
    private fun preparingSurface(
        dark: Boolean,
        large: Boolean,
        legacy: Boolean,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides if (large) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            WhiteNoiseTheme(darkTheme = dark, amoled = dark, fontScale = if (large) 2f else 1f) {
                Surface(Modifier.width(if (large) 320.dp else 360.dp).testTag("preparation.surface")) {
                    if (legacy) {
                        MediaPreviewSendBar(
                            initialCaption = "Photo caption",
                            sending = false,
                            sendEnabled = false,
                            preparing = true,
                            onSend = {},
                        )
                    } else {
                        Column {
                            ComposerAttachmentShelfCard(
                                label = "Photo.jpg",
                                kind = ComposerAttachmentCardKind.Visual,
                                video = false,
                                bitmap = ImageBitmap(144, 112),
                                onPreview = {},
                                onRemove = {},
                                modifier = Modifier.padding(8.dp),
                            )
                            AttachmentPreparationStatus()
                            ComposerBar(
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { _, _ -> },
                                initialDraft = TextFieldValue("Photo caption"),
                                hasPendingAttachments = true,
                                attachmentsPreparing = true,
                                onSendAttachments = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }
    }
}
