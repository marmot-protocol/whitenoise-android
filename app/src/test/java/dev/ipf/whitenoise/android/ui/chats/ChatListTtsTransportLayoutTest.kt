package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.tts.speakingTts
import dev.ipf.whitenoise.android.ui.conversation.TtsTransportBarContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val ROOT_TAG = "chat-list-tts-root"
private const val TRANSPORT_TAG = "chat-list-tts-transport"
private const val FIRST_ROW_TAG = "chat-list-tts-first-row"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListTtsTransportLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun transportOwnsSpaceAboveTheFirstClickableChatRow() {
        var opened = 0
        render(onFirstRowClick = { opened += 1 })

        val transportBounds = composeRule.onNodeWithTag(TRANSPORT_TAG).getUnclippedBoundsInRoot()
        val firstRow = composeRule.onNodeWithTag(FIRST_ROW_TAG)
        val firstRowBounds = firstRow.getUnclippedBoundsInRoot()

        assertTrue(transportBounds.bottom <= firstRowBounds.top)
        firstRow.assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun activeTransportRemainsInFlowInDarkChatList() {
        render(darkTheme = true)

        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/chat_list_tts_transport_dark.png")
    }

    @Test
    fun activeTransportAndNewestRowRemainUsableAtLargeTextInRtl() {
        render(fontScale = 1.5f, layoutDirection = LayoutDirection.Rtl)

        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/chat_list_tts_transport_large_rtl_light.png")
    }

    private fun render(
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        onFirstRowClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    ChatListFixture(onFirstRowClick)
                }
            }
        }
    }
}

@Composable
private fun ChatListFixture(onFirstRowClick: () -> Unit) {
    ChatListBodyFrame(
        modifier =
            Modifier
                .size(width = 360.dp, height = 520.dp)
                .background(MaterialTheme.colorScheme.background)
                .testTag(ROOT_TAG),
        ttsTransport = {
            TtsFixtureTransport()
        },
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(
                items = listOf("Design team", "Family", "Weekend plans"),
                key = { it },
            ) { title ->
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = { Text("A recent message preview") },
                    leadingContent = {
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        )
                    },
                    modifier =
                        if (title == "Design team") {
                            Modifier
                                .testTag(FIRST_ROW_TAG)
                                .clickable(onClick = onFirstRowClick)
                        } else {
                            Modifier
                        },
                )
            }
        }
    }
}

@Composable
private fun TtsFixtureTransport() {
    TtsTransportBarContent(
        state =
            speakingTts(
                chunkIndex = 2,
                chunkCount = 8,
                messageIndex = 1,
                messageCount = 4,
                messagePreview = "The active message is still being read aloud",
                sentenceIndex = 2,
                sentenceCount = 5,
            ),
        rateOverride = 1f,
        activeRate = 1f,
        onPause = {},
        onResume = {},
        onPreviousSentence = {},
        onNextSentence = {},
        onPreviousMessage = {},
        onNextMessage = {},
        onRateSelected = {},
        onStop = {},
        onBodyClick = {},
        modifier = Modifier.testTag(TRANSPORT_TAG),
    )
}
