package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.speakingTts
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.ui.common.ErrorContent
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
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
private const val HISTORY_NOTICE_TAG = "chat-list-history-notice"
private const val CHAT_ROWS_TAG = "chat-list-tts-rows"

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

    /** Active transport and newest row remain usable at large text in rtl. */
    @Test
    fun activeTransportAndNewestRowRemainUsableAtLargeTextInRtl() {
        var opened = 0
        render(fontScale = 1.5f, layoutDirection = LayoutDirection.Rtl, onFirstRowClick = { opened++ })
        val transport = composeRule.onNodeWithTag(TRANSPORT_TAG).getUnclippedBoundsInRoot()
        val firstRow = composeRule.onNodeWithTag(FIRST_ROW_TAG)
        val rowBounds = firstRow.getUnclippedBoundsInRoot()
        assertTrue(transport.bottom <= rowBounds.top)
        assertTrue(rowBounds.bottom <= composeRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot().bottom)
        firstRow.assertIsDisplayed().performClick()
        assertEquals(1, opened)

        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/chat_list_tts_transport_large_rtl_light.png")
    }

    @Test
    fun activeTransportRemainsInFlowWhileChatsLoad() {
        render(state = ChatListFixtureState.Loading)

        capture("chat_list_tts_transport_loading_light.png")
    }

    @Test
    fun activeTransportRemainsInFlowAboveEmptyStateAtLargeTextInRtl() {
        render(
            state = ChatListFixtureState.Empty,
            fontScale = 1.5f,
            layoutDirection = LayoutDirection.Rtl,
        )

        capture("chat_list_tts_transport_empty_large_rtl.png")
    }

    @Test
    fun activeTransportRemainsInFlowAboveLoadErrorInDarkTheme() {
        render(state = ChatListFixtureState.Error, darkTheme = true)

        capture("chat_list_tts_transport_error_dark.png")
    }

    /** The notice remains visible above the empty state at large RTL text. */
    @Test
    fun historyNoticeAppearsAboveEmptyList() {
        render(
            state = ChatListFixtureState.Empty,
            showNotice = true,
            fontScale = 1.5f,
            layoutDirection = LayoutDirection.Rtl,
        )
        composeRule.onNodeWithTag(HISTORY_NOTICE_TAG).assertIsDisplayed()
        capture("chat_list_history_notice_empty_large_rtl.png")
    }

    /** Scrolling chat rows cannot move the account-wide notice out of view. */
    @Test
    fun historyNoticeStaysVisibleWhenRowsScroll() {
        render(showNotice = true)
        composeRule.onNodeWithTag(CHAT_ROWS_TAG).performScrollToIndex(10)
        composeRule.onNodeWithTag(HISTORY_NOTICE_TAG).assertIsDisplayed()
    }

    /** Composes the surface under test with the given fixture. */
    private fun render(
        state: ChatListFixtureState = ChatListFixtureState.Loaded,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        onFirstRowClick: () -> Unit = {},
        showNotice: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, fontScale = fontScale) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                        ChatListFixture(state, onFirstRowClick, showNotice)
                    }
                }
            }
        }
    }

    private fun capture(fileName: String) {
        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/$fileName")
    }
}

/** Arranges the transport, optional notice, and chat-list state for layout checks. */
@Composable
private fun ChatListFixture(
    state: ChatListFixtureState,
    onFirstRowClick: () -> Unit,
    showNotice: Boolean,
) {
    ChatListBodyFrame(
        modifier =
            Modifier
                .size(width = 360.dp, height = 520.dp)
                .background(MaterialTheme.colorScheme.background)
                .testTag(ROOT_TAG),
        ttsTransport = {
            TtsFixtureTransport()
        },
        notice = {
            if (showNotice) {
                AccountHistoryNoticeBanner(
                    dismissing = false,
                    onDismiss = {},
                    modifier = Modifier.testTag(HISTORY_NOTICE_TAG),
                )
            }
        },
    ) {
        when (state) {
            ChatListFixtureState.Loading -> LoadingScreen()
            ChatListFixtureState.Empty -> EmptyChats(onCreate = {})
            ChatListFixtureState.Error ->
                ErrorContent(
                    title = stringResource(R.string.couldnt_load_chats),
                    error =
                        ErrorPresentation(
                            message = AppText.Resource(R.string.error_try_again),
                            report = "CHAT_LIST_LOAD\ncategory=connectivity",
                        ),
                    onRetry = {},
                )
            ChatListFixtureState.Loaded -> LoadedChatList(onFirstRowClick, showNotice)
        }
    }
}

/** Adds offscreen rows only when testing whether scrolling leaves the notice visible. */
@Composable
private fun LoadedChatList(
    onFirstRowClick: () -> Unit,
    includeExtraRows: Boolean,
) {
    val chatTitles =
        buildList {
            addAll(listOf("Design team", "Family", "Weekend plans"))
            if (includeExtraRows) addAll((4..20).map { "Chat $it" })
        }
    LazyColumn(Modifier.fillMaxSize().testTag(CHAT_ROWS_TAG)) {
        items(
            items = chatTitles,
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

private enum class ChatListFixtureState {
    Loaded,
    Loading,
    Empty,
    Error,
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
