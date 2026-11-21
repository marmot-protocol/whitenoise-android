package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.ConversationDictationDeliveryMode
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationPersistentControl
import dev.ipf.whitenoise.android.ui.conversation.composer.DictationControlFacts
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
internal class DictationFollowUpScreenshotTest : DictationNavigationRenderFixture() {
    @Test
    fun followUpLight() = capture(dark = false)

    @Test
    fun followUpAmoledLargeRtl() = capture(dark = true)

    private fun capture(dark: Boolean) {
        val fixture = Fixture()
        val controller = fixture.controller
        controller.requestStart(
            DictationNavigationFixtureValues.ACCOUNT,
            DictationNavigationFixtureValues.GROUP,
            fixture.draft,
        )
        val state = ConversationDictationState.Processing(1L, requireNotNull(controller.state.target))
        val processing =
            DictationControlFacts(
                captureInProgress = false,
                deliveryInProgress = false,
                completionActionsEnabled = false,
                processingDeliveryMode = ConversationDictationDeliveryMode.PasteIntoDraft,
                recoveryHandedToComposer = false,
                continueAvailable = true,
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (dark) 2f else 1f),
                LocalLayoutDirection provides if (dark) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = dark) {
                    Surface {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ConversationDictationPersistentControl(state, controller, facts = processing)
                            ConversationDictationPersistentControl(
                                state,
                                controller,
                                facts = processing.copy(processingDeliveryMode = ConversationDictationDeliveryMode.SendOnFinish),
                            )
                            ConversationDictationPersistentControl(
                                state,
                                controller,
                                facts =
                                    processing.copy(
                                        captureInProgress = true,
                                        completionActionsEnabled = true,
                                        processingDeliveryMode = null,
                                        continueAvailable = false,
                                        previousPending = true,
                                    ),
                            )
                        }
                    }
                }
            }
        }
        val variant = if (dark) "amoled_large_rtl" else "light"
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_follow_up_$variant.png")
    }
}
