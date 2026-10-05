package dev.ipf.whitenoise.android.media

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress
import dev.ipf.whitenoise.android.ui.conversation.media.MediaFileBubbleContent
import dev.ipf.whitenoise.android.ui.conversation.media.formatAttachmentProgressSize
import dev.ipf.whitenoise.android.ui.conversation.media.resolveAttachmentPresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Opt-in generated native download and real platform semantics; ordinary runs skip the guarded probe. */
@RunWith(AndroidJUnit4::class)
@ManualDeviceFixture
class UnknownLengthAttachmentDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Native unknown totals produce a byte-only accessible control at 200% font scale in RTL. */
    @Test
    fun nativeUnknownLengthProgressHasAccessibleByteOnlyControl() =
        runBlocking {
            ControlledAttachmentProbe.run(::assertProgressControl)
        }

    /** Renders the actual native observation while the ciphertext body remains incomplete and held. */
    private fun assertProgressControl(
        progress: NativeAttachmentProgress,
        reference: MediaAttachmentReferenceFfi,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme(darkTheme = true) {
                    MediaFileBubbleContent(
                        reference,
                        resolveAttachmentPresentation(reference.mediaType, reference.fileName),
                        AttachmentTransferState.Downloading,
                        nativeProgress = progress,
                        onCancelTransfer = {},
                    )
                }
            }
        }
        val locale = composeRule.activity.resources.configuration.locales[0]
        val description =
            composeRule.activity.getString(
                R.string.media_download_body_unknown,
                formatAttachmentProgressSize(progress.received, locale),
            )
        val control = composeRule.onNodeWithContentDescription(description).assertHasClickAction()
        val bounds = control.fetchSemanticsNode().boundsInRoot
        val minimumPixels = with(composeRule.density) { 48.dp.roundToPx() }
        assertTrue("native progress control width: $bounds", bounds.width.roundToInt() >= minimumPixels)
        assertTrue("native progress control height: $bounds", bounds.height.roundToInt() >= minimumPixels)
    }
}
