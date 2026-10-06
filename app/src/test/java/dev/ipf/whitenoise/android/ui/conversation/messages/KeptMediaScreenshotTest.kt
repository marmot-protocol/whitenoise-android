package dev.ipf.whitenoise.android.ui.conversation.messages

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Deterministic kept-media card coverage across type, availability, themes and typography. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class KeptMediaScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Audio without a caption has a useful compact reference in light theme. */
    @Test
    fun audioOnlyLight() = capture("kept_audio_light", "audio/ogg", "Voice note.ogg")

    /** A captioned image uses only the supplied cached preview. */
    @Test
    fun captionedLocalImageDark() = capture("kept_image_dark", "image/png", "Sketch.png", dark = true, local = true)

    /** A failed file remains recognizable in AMOLED instead of becoming a blank card. */
    @Test
    fun unavailableFileAmoled() =
        capture(
            "kept_file_failed_amoled",
            "application/pdf",
            "Release notes.pdf",
            dark = true,
            failed = true,
        )

    /** Narrow RTL at large font keeps the media type and source action readable. */
    @Test
    fun audioRtlLargeText() =
        capture(
            "kept_audio_rtl_large",
            "audio/ogg",
            "Voice note with a long title.ogg",
            rtl = true,
        )

    /** Hosts a live projected fixture while making cached/error presentation deterministic. */
    @Suppress("LongParameterList") // Independent fixture choices cover the visual matrix without shared mutable state.
    private fun capture(
        name: String,
        mediaType: String,
        fileName: String,
        dark: Boolean = false,
        local: Boolean = false,
        failed: Boolean = false,
        rtl: Boolean = false,
    ) {
        val item = keptMediaTestMessage(mediaType, fileName, if (local) "The revised sketch for tomorrow" else "")
        val thumbnail =
            if (local) {
                Bitmap
                    .createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                    .apply {
                        eraseColor(Color.rgb(76, 130, 160))
                    }.asImageBitmap()
            } else {
                null
            }
        composeRule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = dark, amoled = failed, fontScale = if (rtl) 2f else 1f) {
                    KeptMediaTestHost(item, presentationOverride = { presentation ->
                        presentation.copy(
                            attachments =
                                presentation.attachments.map {
                                    it.copy(
                                        thumbnail = thumbnail,
                                        statusLabel =
                                            when {
                                                failed -> "Download failed. Open the original to retry"
                                                local -> "Available on this device"
                                                else -> it.statusLabel
                                            },
                                    )
                                },
                        )
                    })
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(KEPT_MESSAGE_CARD_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }
}
