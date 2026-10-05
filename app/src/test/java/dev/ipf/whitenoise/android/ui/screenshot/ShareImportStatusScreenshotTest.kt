package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.share.ShareImportError
import dev.ipf.whitenoise.android.share.ShareImportProgress
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.ui.share.ShareImportStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ShareImportStatusScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Captures the bounded byte-progress dialog in the light theme using synthetic content. */
    @Test fun progressLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                ShareImportStatus(true, ShareImportProgress(1, 2, 16, 32), null, {}) { }
            }
        }
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/share_import_progress_light.png")
    }

    /** Captures an empty interrupted batch in the dark theme, including its close-only recovery action. */
    @Test fun interruptedDark() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareImportStatus(
                    false,
                    null,
                    ShareRequest(
                        SharePayload(null, emptyList(), null, true, listOf(ShareImportError.Interrupted)),
                        null,
                        "request",
                    ),
                    {},
                ) { }
            }
        }
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/share_import_interrupted_dark.png")
    }

    /** Captures partial recovery at large font scale and RTL with both rejection explanations visible. */
    @Test
    fun partialFailureLargeFontRtl() {
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme(darkTheme = false, fontScale = 1.8f) {
                    ShareImportStatus(
                        false,
                        null,
                        ShareRequest(
                            SharePayload(
                                null,
                                listOf(android.net.Uri.parse("content://private/item")),
                                null,
                                true,
                                listOf(ShareImportError.Unreadable, ShareImportError.BatchTooLarge),
                                2,
                            ),
                            shortcutId = null,
                            requestId = "partial",
                        ),
                        {},
                    ) { }
                }
            }
        }
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/share_import_partial_large_rtl.png")
    }
}
