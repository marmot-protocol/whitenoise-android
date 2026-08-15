package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.common.ErrorContent
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.conversation.ConversationTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.conversation.DaySeparator
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
class MediaSaveConfirmationScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mediaSaveConfirmationGlobal() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                ShellTransientNoticeLayout(
                    notice = TransientNotice(id = 1L, title = AppText.Resource(R.string.shared_media_saved)),
                    modifier = Modifier.testTag(GLOBAL_TAG),
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize()) {
                            Text(
                                "Media library",
                                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag(GLOBAL_TAG).captureRoboImage("src/test/snapshots/media_save_confirmation_global.png")
    }

    @Test
    fun mediaSaveConfirmationConversation() {
        renderConversationConfirmation()

        composeRule
            .onNodeWithTag(CONVERSATION_TAG)
            .captureRoboImage("src/test/snapshots/media_save_confirmation_conversation.png")
    }

    @Test
    fun mediaSaveConfirmationConversationLargeTextDarkRtl() {
        renderConversationConfirmation(
            darkTheme = true,
            fontScale = 1.5f,
            layoutDirection = LayoutDirection.Rtl,
            contentHeight = 200,
        )

        composeRule
            .onNodeWithTag(CONVERSATION_TAG)
            .captureRoboImage("src/test/snapshots/media_save_confirmation_conversation_large_dark_rtl.png")
    }

    @Test
    fun mediaSaveConfirmationConversationAmoledImeConstrained() {
        // The real conversation scaffold gives this layout only the viewport
        // left above the IME. Keep that remaining height deliberately tight so
        // the notice, day separator, and latest row cannot hide an overlap.
        renderConversationConfirmation(
            darkTheme = true,
            amoled = true,
            contentHeight = 144,
        )

        composeRule
            .onNodeWithTag(CONVERSATION_TAG)
            .captureRoboImage("src/test/snapshots/media_save_confirmation_conversation_amoled_ime.png")
    }

    // Pin every full-screen state that now shares the transient-notice frame.
    @Test
    fun conversationTransientLoading() =
        captureConversationState(
            state = ConversationFixtureState.Loading,
            fileName = "conversation_transient_loading.png",
        )

    @Test
    fun conversationTransientEmpty() =
        captureConversationState(
            state = ConversationFixtureState.Empty,
            fileName = "conversation_transient_empty.png",
        )

    @Test
    fun conversationTransientControllerError() =
        captureConversationState(
            state = ConversationFixtureState.ControllerError,
            fileName = "conversation_transient_controller_error.png",
        )

    @Test
    fun conversationTransientInitialBackfillError() =
        captureConversationState(
            state = ConversationFixtureState.InitialBackfillError,
            fileName = "conversation_transient_initial_backfill_error.png",
        )

    private fun renderConversationConfirmation(
        darkTheme: Boolean = false,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        contentHeight: Int = 160,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(modifier = Modifier.fillMaxWidth().testTag(CONVERSATION_TAG)) {
                        Column {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text("Alpine group", style = MaterialTheme.typography.titleMedium)
                            }
                            ConversationTransientNoticeLayout(
                                notice =
                                    TransientNotice(
                                        id = 1L,
                                        title = AppText.Resource(R.string.shared_media_saved),
                                        conversation = ConversationNoticeDestination("account-a", "group-a"),
                                    ),
                                accountRef = "account-a",
                                groupIdHex = "group-a",
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(contentHeight.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Column {
                                    DaySeparator("Today")
                                    Text(
                                        "Latest message remains visible",
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun captureConversationState(
        state: ConversationFixtureState,
        fileName: String,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxWidth().testTag(CONVERSATION_STATE_TAG)) {
                    Column {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text("Alpine group", style = MaterialTheme.typography.titleMedium)
                        }
                        ConversationTransientNoticeLayout(
                            notice =
                                TransientNotice(
                                    id = 2L,
                                    title = AppText.Resource(R.string.shared_media_saved),
                                    conversation = ConversationNoticeDestination("account-a", "group-a"),
                                ),
                            accountRef = "account-a",
                            groupIdHex = "group-a",
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                        ) {
                            ConversationFixtureContent(state)
                        }
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(CONVERSATION_STATE_TAG)
            .captureRoboImage("src/test/snapshots/$fileName")
    }

    private companion object {
        const val GLOBAL_TAG = "media-save-global-confirmation"
        const val CONVERSATION_TAG = "media-save-conversation-confirmation"
        const val CONVERSATION_STATE_TAG = "conversation-transient-state"
    }
}

private enum class ConversationFixtureState {
    Loading,
    Empty,
    ControllerError,
    InitialBackfillError,
}

@Composable
private fun ConversationFixtureContent(state: ConversationFixtureState) {
    when (state) {
        ConversationFixtureState.Loading -> LoadingScreen()
        ConversationFixtureState.Empty ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        ConversationFixtureState.ControllerError ->
            ConversationFixtureError("Couldn't refresh this conversation")
        ConversationFixtureState.InitialBackfillError ->
            ConversationFixtureError("Couldn't find a visible message")
    }
}

@Composable
private fun ConversationFixtureError(message: String) {
    ErrorContent(
        title = "Couldn't load conversation",
        error =
            ErrorPresentation(
                message = AppText.Plain(message),
                report = "operation=CONVERSATION_INITIAL_LOAD\nerror=CONNECTIVITY",
            ),
        onRetry = {},
    )
}
